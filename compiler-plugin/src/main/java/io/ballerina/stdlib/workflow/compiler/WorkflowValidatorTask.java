/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.AnnotationSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.List;
import java.util.Optional;

/**
 * Validation task for workflow @Process and @Activity function signatures.
 * <p>
 * Validates:
 * <ul>
 *   <li>@Process functions have valid signature: (Context?, anydata input, record{future<anydata>...} events?)</li>
 *   <li>@Activity functions have anydata parameters and anydata|error return type</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class WorkflowValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionDefinitionNode)) {
            return;
        }

        FunctionDefinitionNode functionNode = (FunctionDefinitionNode) context.node();
        SemanticModel semanticModel = context.semanticModel();

        // Check if function has @Process annotation
        if (hasAnnotation(functionNode, semanticModel, WorkflowConstants.PROCESS_ANNOTATION)) {
            validateProcessFunction(functionNode, context);
            // Validate callActivity calls within the process function
            validateCallActivityUsage(functionNode, context);
            // Validate no direct @Activity function calls are made
            validateNoDirectActivityCalls(functionNode, context);
        }

        // Check if function has @Activity annotation
        if (hasAnnotation(functionNode, semanticModel, WorkflowConstants.ACTIVITY_ANNOTATION)) {
            validateActivityFunction(functionNode, context);
        }
    }

    private boolean hasAnnotation(FunctionDefinitionNode functionNode, SemanticModel semanticModel,
                                   String annotationName) {
        Optional<MetadataNode> metadataOpt = functionNode.metadata();
        if (metadataOpt.isEmpty()) {
            return false;
        }

        NodeList<AnnotationNode> annotations = metadataOpt.get().annotations();
        for (AnnotationNode annotation : annotations) {
            if (isWorkflowAnnotation(annotation, semanticModel, annotationName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWorkflowAnnotation(AnnotationNode annotation, SemanticModel semanticModel,
                                         String expectedName) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(annotation);
        if (symbolOpt.isEmpty()) {
            return false;
        }

        Symbol symbol = symbolOpt.get();
        if (symbol.kind() != SymbolKind.ANNOTATION) {
            return false;
        }

        AnnotationSymbol annotationSymbol = (AnnotationSymbol) symbol;
        Optional<String> nameOpt = annotationSymbol.getName();
        if (nameOpt.isEmpty() || !expectedName.equals(nameOpt.get())) {
            return false;
        }

        Optional<ModuleSymbol> moduleOpt = annotationSymbol.getModule();
        if (moduleOpt.isEmpty()) {
            return false;
        }

        ModuleSymbol module = moduleOpt.get();
        Optional<String> moduleNameOpt = module.getName();
        return moduleNameOpt.isPresent() && WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get());
    }

    /**
     * Validates @Process function signature according to Agent.md semantics.
     * <ul>
     *   <li>Optional first parameter: workflow:Context</li>
     *   <li>Required input parameter: subtype of anydata</li>
     *   <li>Optional events parameter: record with future anydata fields</li>
     *   <li>Return type: subtype of anydata|error</li>
     * </ul>
     */
    private void validateProcessFunction(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        SemanticModel semanticModel = context.semanticModel();
        Optional<Symbol> symbolOpt = semanticModel.symbol(functionNode);

        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return;
        }

        FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
        FunctionTypeSymbol typeSymbol = functionSymbol.typeDescriptor();

        // Get parameters
        Optional<List<ParameterSymbol>> paramsOpt = typeSymbol.params();
        if (paramsOpt.isEmpty()) {
            return; // No parameters is valid (though unusual)
        }

        List<ParameterSymbol> params = paramsOpt.get();
        int paramIndex = 0;

        // Check first parameter - could be Context
        if (!params.isEmpty()) {
            ParameterSymbol firstParam = params.get(paramIndex);
            TypeSymbol firstParamType = firstParam.typeDescriptor();

            if (isContextType(firstParamType)) {
                paramIndex++;
            } else if (looksLikeContextType(firstParamType)) {
                // First param looks like it should be Context but isn't the right type
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_100,
                        WorkflowConstants.PROCESS_INVALID_CONTEXT_PARAM);
                return;
            }
        }

        // Check input parameter (if exists after Context)
        if (paramIndex < params.size()) {
            ParameterSymbol inputParam = params.get(paramIndex);
            TypeSymbol inputType = inputParam.typeDescriptor();

            if (!isSubtypeOfAnydata(inputType)) {
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_101,
                        WorkflowConstants.PROCESS_INVALID_INPUT_TYPE);
                return;
            }
            paramIndex++;
        }

        // Check events parameter (if exists)
        if (paramIndex < params.size()) {
            ParameterSymbol eventsParam = params.get(paramIndex);
            TypeSymbol eventsType = eventsParam.typeDescriptor();

            if (!isValidEventsType(eventsType)) {
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_102,
                        WorkflowConstants.PROCESS_INVALID_EVENTS_TYPE);
                return;
            }
            paramIndex++;
        }

        // Check for excess parameters (max 3: Context, input, events)
        if (paramIndex < params.size()) {
            reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_106,
                    WorkflowConstants.PROCESS_TOO_MANY_PARAMS);
            return;
        }

        // Check return type
        Optional<TypeSymbol> returnTypeOpt = typeSymbol.returnTypeDescriptor();
        if (returnTypeOpt.isPresent()) {
            TypeSymbol returnType = returnTypeOpt.get();
            if (!isValidReturnType(returnType)) {
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_105,
                        WorkflowConstants.PROCESS_INVALID_RETURN_TYPE);
            }
        }
    }

    /**
     * Validates @Activity function signature according to Agent.md semantics.
     * <ul>
     *   <li>All parameters must be subtypes of anydata</li>
     *   <li>Return type must be subtype of anydata|error</li>
     * </ul>
     */
    private void validateActivityFunction(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        SemanticModel semanticModel = context.semanticModel();
        Optional<Symbol> symbolOpt = semanticModel.symbol(functionNode);

        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return;
        }

        FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
        FunctionTypeSymbol typeSymbol = functionSymbol.typeDescriptor();

        // Validate all parameters are subtypes of anydata
        Optional<List<ParameterSymbol>> paramsOpt = typeSymbol.params();
        if (paramsOpt.isPresent()) {
            for (ParameterSymbol param : paramsOpt.get()) {
                TypeSymbol paramType = param.typeDescriptor();
                if (!isSubtypeOfAnydata(paramType)) {
                    reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_103,
                            WorkflowConstants.ACTIVITY_INVALID_PARAM_TYPE);
                    return;
                }
            }
        }

        // Validate return type is subtype of anydata|error
        Optional<TypeSymbol> returnTypeOpt = typeSymbol.returnTypeDescriptor();
        if (returnTypeOpt.isPresent()) {
            TypeSymbol returnType = returnTypeOpt.get();
            if (!isValidReturnType(returnType)) {
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_104,
                        WorkflowConstants.ACTIVITY_INVALID_RETURN_TYPE);
            }
        }
    }

    /**
     * Validates that ctx->callActivity() calls have a function with @Activity annotation
     * as the first argument.
     */
    private void validateCallActivityUsage(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        CallActivityValidator validator = new CallActivityValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that validates ctx->callActivity() calls.
     * Ensures the first argument is a function with @Activity annotation.
     */
    private static class CallActivityValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;

        CallActivityValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
            this.semanticModel = context.semanticModel();
        }

        @Override
        public void visit(RemoteMethodCallActionNode remoteCallNode) {
            String methodName = remoteCallNode.methodName().name().text();
            
            // Check if this is a callActivity call
            if (WorkflowConstants.CALL_ACTIVITY_FUNCTION.equals(methodName)) {
                // Verify the expression is a Context type (optional, for better error messages)
                // Validate the first argument has @Activity annotation
                SeparatedNodeList<FunctionArgumentNode> arguments = remoteCallNode.arguments();
                if (!arguments.isEmpty()) {
                    FunctionArgumentNode firstArg = arguments.get(0);
                    if (firstArg instanceof PositionalArgumentNode) {
                        PositionalArgumentNode posArg = (PositionalArgumentNode) firstArg;
                        ExpressionNode expression = posArg.expression();
                        
                        // Check if the function reference has @Activity annotation
                        if (!hasActivityAnnotation(expression)) {
                            reportCallActivityDiagnostic(remoteCallNode);
                        }
                    }
                }
            }

            // Continue visiting child nodes
            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Checks if the given expression references a function with @Activity annotation.
         */
        private boolean hasActivityAnnotation(ExpressionNode expression) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(expression);
            if (symbolOpt.isEmpty()) {
                return false;
            }

            Symbol symbol = symbolOpt.get();
            if (symbol.kind() != SymbolKind.FUNCTION) {
                return false;
            }

            FunctionSymbol functionSymbol = (FunctionSymbol) symbol;
            List<AnnotationSymbol> annotations = functionSymbol.annotations();

            for (AnnotationSymbol annotation : annotations) {
                Optional<String> nameOpt = annotation.getName();
                if (nameOpt.isEmpty()) {
                    continue;
                }

                if (WorkflowConstants.ACTIVITY_ANNOTATION.equals(nameOpt.get())) {
                    // Verify it's from the workflow module
                    Optional<ModuleSymbol> moduleOpt = annotation.getModule();
                    if (moduleOpt.isPresent()) {
                        ModuleSymbol module = moduleOpt.get();
                        Optional<String> moduleNameOpt = module.getName();
                        if (moduleNameOpt.isPresent() &&
                                WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private void reportCallActivityDiagnostic(RemoteMethodCallActionNode node) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_107,
                    WorkflowConstants.CALL_ACTIVITY_MISSING_ACTIVITY_ANNOTATION,
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.methodName().location()));
        }
    }

    /**
     * Validates that no direct @Activity function calls are made within @Process functions.
     * Users must use ctx->callActivity(activityFunc, args...) pattern.
     */
    private void validateNoDirectActivityCalls(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        DirectActivityCallValidator validator = new DirectActivityCallValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that detects direct calls to @Activity annotated functions.
     * Reports an error for each direct call found.
     */
    private static class DirectActivityCallValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;

        DirectActivityCallValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
            this.semanticModel = context.semanticModel();
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            // Check if this is a call to an @Activity function
            if (isActivityFunction(callNode)) {
                reportDirectActivityCallError(callNode);
            }

            // Continue visiting child nodes (arguments may contain nested calls)
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Checks if the function call is to an @Activity annotated function.
         */
        private boolean isActivityFunction(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
            if (symbolOpt.isEmpty()) {
                return false;
            }

            Symbol symbol = symbolOpt.get();
            if (symbol.kind() != SymbolKind.FUNCTION) {
                return false;
            }

            FunctionSymbol functionSymbol = (FunctionSymbol) symbol;
            List<AnnotationSymbol> annotations = functionSymbol.annotations();

            for (AnnotationSymbol annotation : annotations) {
                Optional<String> nameOpt = annotation.getName();
                if (nameOpt.isEmpty()) {
                    continue;
                }

                if (WorkflowConstants.ACTIVITY_ANNOTATION.equals(nameOpt.get())) {
                    // Verify it's from the workflow module
                    Optional<ModuleSymbol> moduleOpt = annotation.getModule();
                    if (moduleOpt.isPresent()) {
                        ModuleSymbol module = moduleOpt.get();
                        Optional<String> moduleNameOpt = module.getName();
                        if (moduleNameOpt.isPresent() &&
                                WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get())) {
                            return true;
                        }
                    }
                }
            }

            return false;
        }

        private void reportDirectActivityCallError(FunctionCallExpressionNode callNode) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_108,
                    WorkflowConstants.DIRECT_ACTIVITY_CALL_NOT_ALLOWED,
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    callNode.functionName().location()));
        }
    }

    /**
     * Checks if the type is workflow:Context.
     */
    private boolean isContextType(TypeSymbol typeSymbol) {
        if (typeSymbol.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            Optional<String> nameOpt = typeRef.getName();
            if (nameOpt.isPresent() && WorkflowConstants.CONTEXT_TYPE.equals(nameOpt.get())) {
                Optional<ModuleSymbol> moduleOpt = typeRef.getModule();
                if (moduleOpt.isPresent()) {
                    ModuleSymbol module = moduleOpt.get();
                    Optional<String> moduleNameOpt = module.getName();
                    return moduleNameOpt.isPresent() && WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get());
                }
            }
        }
        return false;
    }

    /**
     * Checks if the type looks like it's intended to be a Context type
     * (e.g., named "ctx" or "context") but isn't the actual workflow:Context type.
     */
    private boolean looksLikeContextType(TypeSymbol typeSymbol) {
        // For now, we don't do heuristic matching
        // This could be enhanced to check parameter names like "ctx" or "context"
        return false;
    }

    /**
     * Checks if the type is a subtype of anydata.
     */
    private boolean isSubtypeOfAnydata(TypeSymbol typeSymbol) {
        TypeDescKind kind = typeSymbol.typeKind();

        // Handle type references
        if (kind == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            return isSubtypeOfAnydata(typeRef.typeDescriptor());
        }

        // anydata includes: (), boolean, int, float, decimal, string, xml, 
        // anydata[], map<anydata>, table<map<anydata>>, record types
        switch (kind) {
            case NIL:
            case BOOLEAN:
            case INT:
            case FLOAT:
            case DECIMAL:
            case STRING:
            case XML:
            case ANYDATA:
            case JSON:
            case BYTE:
            case ARRAY:
            case MAP:
            case RECORD:
            case TABLE:
            case TUPLE:
                return true;
            case UNION:
                // Check if all members are subtypes of anydata
                UnionTypeSymbol unionType = (UnionTypeSymbol) typeSymbol;
                return unionType.memberTypeDescriptors().stream()
                        .allMatch(this::isSubtypeOfAnydata);
            default:
                return false;
        }
    }

    /**
     * Checks if the type is a subtype of anydata or error.
     * Handles union types like `string|error` where each member must be anydata or error.
     */
    private boolean isSubtypeOfAnydataOrError(TypeSymbol typeSymbol) {
        TypeDescKind kind = typeSymbol.typeKind();

        // Handle type references
        if (kind == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            return isSubtypeOfAnydataOrError(typeRef.typeDescriptor());
        }

        if (kind == TypeDescKind.ERROR) {
            return true;
        }

        // Handle union types like `string|error` - each member must be anydata or error
        if (kind == TypeDescKind.UNION) {
            UnionTypeSymbol unionType = (UnionTypeSymbol) typeSymbol;
            return unionType.memberTypeDescriptors().stream()
                    .allMatch(this::isSubtypeOfAnydataOrError);
        }

        return isSubtypeOfAnydata(typeSymbol);
    }

    /**
     * Validates the events parameter type - should be a record with future<anydata> fields.
     */
    private boolean isValidEventsType(TypeSymbol typeSymbol) {
        TypeDescKind kind = typeSymbol.typeKind();

        // Handle type references
        if (kind == TypeDescKind.TYPE_REFERENCE) {
            TypeReferenceTypeSymbol typeRef = (TypeReferenceTypeSymbol) typeSymbol;
            return isValidEventsType(typeRef.typeDescriptor());
        }

        // Must be a record type
        if (kind != TypeDescKind.RECORD) {
            return false;
        }

        // For now, we accept any record type
        // A more strict validation would check that all fields are future<anydata>
        return true;
    }

    /**
     * Validates return type is subtype of anydata|error.
     */
    private boolean isValidReturnType(TypeSymbol typeSymbol) {
        return isSubtypeOfAnydataOrError(typeSymbol);
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, FunctionDefinitionNode functionNode,
                                   String code, String message) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(code, message, DiagnosticSeverity.ERROR);
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                functionNode.functionName().location()));
    }
}
