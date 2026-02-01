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
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        return WorkflowPluginUtils.hasWorkflowAnnotation(functionNode, semanticModel, annotationName);
    }

    /**
     * Validates @Process function signature according to Agent.md semantics.
     * <ul>
     *   <li>Optional first parameter: workflow:Context</li>
     *   <li>Optional input parameter: subtype of anydata</li>
     *   <li>Optional events parameter: record with future anydata fields</li>
     *   <li>Return type: subtype of anydata|error</li>
     * </ul>
     * <p>
     * Valid signatures:
     * <ul>
     *   <li>function process() returns R|error</li>
     *   <li>function process(Context ctx) returns R|error</li>
     *   <li>function process(Input input) returns R|error</li>
     *   <li>function process(Events events) returns R|error</li>
     *   <li>function process(Context ctx, Input input) returns R|error</li>
     *   <li>function process(Context ctx, Events events) returns R|error</li>
     *   <li>function process(Input input, Events events) returns R|error</li>
     *   <li>function process(Context ctx, Input input, Events events) returns R|error</li>
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
        if (paramsOpt.isEmpty() || paramsOpt.get().isEmpty()) {
            // No parameters is valid - validate return type only
            validateReturnType(functionNode, context, typeSymbol);
            return;
        }

        List<ParameterSymbol> params = paramsOpt.get();
        
        // Check for excess parameters (max 3: Context, input, events)
        if (params.size() > 3) {
            reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_106,
                    WorkflowConstants.PROCESS_TOO_MANY_PARAMS);
            return;
        }

        int paramIndex = 0;
        boolean hasContext = false;
        boolean hasInput = false;
        boolean hasEvents = false;

        // Check first parameter - could be Context, input, or events
        if (paramIndex < params.size()) {
            ParameterSymbol firstParam = params.get(paramIndex);
            TypeSymbol firstParamType = firstParam.typeDescriptor();

            if (WorkflowPluginUtils.isContextType(firstParamType)) {
                hasContext = true;
                paramIndex++;
            } else if (looksLikeContextType(firstParamType)) {
                // First param looks like it should be Context but isn't the right type
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_100,
                        WorkflowConstants.PROCESS_INVALID_CONTEXT_PARAM);
                return;
            }
        }

        // Check remaining parameters - they can be input and/or events
        // The order should be: [Context], [input], [events]
        while (paramIndex < params.size()) {
            ParameterSymbol param = params.get(paramIndex);
            TypeSymbol paramType = param.typeDescriptor();

            // Determine expected parameter type based on position
            // After context (or at start), next should be input or events
            // After input, next should be events
            
            if (hasInput) {
                // Already have input, so this parameter MUST be events
                if (!isValidEventsType(paramType)) {
                    reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_102,
                            WorkflowConstants.PROCESS_INVALID_EVENTS_TYPE);
                    return;
                }
                if (hasEvents) {
                    // Already have events parameter, this is an error
                    reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_106,
                            WorkflowConstants.PROCESS_TOO_MANY_PARAMS);
                    return;
                }
                hasEvents = true;
                paramIndex++;
            } else {
                // No input yet - this could be input or events
                if (isValidEventsType(paramType)) {
                    // This is an events parameter (can come without input)
                    hasEvents = true;
                    paramIndex++;
                } else if (WorkflowPluginUtils.isSubtypeOfAnydata(paramType, semanticModel)) {
                    // This is an input parameter
                    if (hasEvents) {
                        // Input must come before events
                        reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_101,
                                "@Process function's input parameter must come before events parameter");
                        return;
                    }
                    hasInput = true;
                    paramIndex++;
                } else {
                    // Parameter is neither anydata nor events record - error
                    reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_101,
                            WorkflowConstants.PROCESS_INVALID_INPUT_TYPE);
                    return;
                }
            }
        }

        // Validate return type
        validateReturnType(functionNode, context, typeSymbol);
    }

    /**
     * Validates the return type of a process or activity function.
     */
    private void validateReturnType(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context,
                                     FunctionTypeSymbol typeSymbol) {
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
                if (!WorkflowPluginUtils.isSubtypeOfAnydata(paramType, semanticModel)) {
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
            if (!WorkflowPluginUtils.isSubtypeOfAnydataOrError(returnType, semanticModel)) {
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
     * Validates that the Parameters record keys match the activity function parameters.
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
                        } else {
                            // Validate parameters match if second argument is provided
                            if (arguments.size() >= 2) {
                                FunctionArgumentNode secondArg = arguments.get(1);
                                validateParametersMatch(remoteCallNode, expression, secondArg);
                            }
                        }
                    }
                }
            }

            // Continue visiting child nodes
            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Validates that the Parameters record keys match the activity function's parameter names.
         */
        private void validateParametersMatch(RemoteMethodCallActionNode callNode,
                                             ExpressionNode activityFuncExpr,
                                             FunctionArgumentNode paramsArg) {
            // Get the activity function symbol to extract parameters
            Optional<Symbol> funcSymbolOpt = semanticModel.symbol(activityFuncExpr);
            if (funcSymbolOpt.isEmpty() || funcSymbolOpt.get().kind() != SymbolKind.FUNCTION) {
                return;
            }

            FunctionSymbol functionSymbol = (FunctionSymbol) funcSymbolOpt.get();
            FunctionTypeSymbol funcTypeSymbol = functionSymbol.typeDescriptor();
            
            // Check if the function has rest parameters
            if (funcTypeSymbol.restParam().isPresent()) {
                reportRestParamsNotSupported(callNode);
                return;
            }

            // Get expected parameters from activity function
            Optional<List<ParameterSymbol>> paramsOpt = funcTypeSymbol.params();
            if (paramsOpt.isEmpty()) {
                return;
            }

            List<ParameterSymbol> expectedParams = paramsOpt.get();
            Set<String> expectedParamNames = new HashSet<>();
            Set<String> requiredParamNames = new HashSet<>();
            
            for (ParameterSymbol param : expectedParams) {
                Optional<String> nameOpt = param.getName();
                if (nameOpt.isPresent()) {
                    expectedParamNames.add(nameOpt.get());
                    // Required if not default-able
                    if (param.paramKind() == ParameterKind.REQUIRED) {
                        requiredParamNames.add(nameOpt.get());
                    }
                }
            }

            // Extract parameter names from the Parameters record argument
            Set<String> providedParamNames = extractProvidedParamNames(paramsArg);

            // Check for missing required parameters
            for (String required : requiredParamNames) {
                if (!providedParamNames.contains(required)) {
                    reportMissingRequiredParam(callNode, required);
                }
            }

            // Check for extra parameters not in the function signature
            for (String provided : providedParamNames) {
                if (!expectedParamNames.contains(provided)) {
                    reportExtraParam(callNode, provided);
                }
            }
        }

        /**
         * Extracts parameter names from the Parameters record (second argument).
         */
        private Set<String> extractProvidedParamNames(FunctionArgumentNode paramsArg) {
            Set<String> paramNames = new HashSet<>();
            
            if (!(paramsArg instanceof PositionalArgumentNode)) {
                return paramNames;
            }

            PositionalArgumentNode posArg = (PositionalArgumentNode) paramsArg;
            ExpressionNode expr = posArg.expression();
            
            // Check if it's a mapping constructor expression like {"param1": value1, "param2": value2}
            if (expr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR) {
                MappingConstructorExpressionNode mappingExpr = (MappingConstructorExpressionNode) expr;
                SeparatedNodeList<MappingFieldNode> fields = mappingExpr.fields();
                
                for (MappingFieldNode field : fields) {
                    if (field instanceof SpecificFieldNode) {
                        SpecificFieldNode specificField = (SpecificFieldNode) field;
                        String fieldName = extractFieldName(specificField);
                        if (fieldName != null) {
                            paramNames.add(fieldName);
                        }
                    }
                }
            }
            
            return paramNames;
        }

        /**
         * Extracts the field name from a SpecificFieldNode.
         * Handles both string literal keys ("fieldName") and identifier keys (fieldName).
         */
        private String extractFieldName(SpecificFieldNode field) {
            var fieldName = field.fieldName();
            if (fieldName.kind() == SyntaxKind.STRING_LITERAL) {
                // String literal like "fieldName" - remove quotes
                String text = fieldName.toString().trim();
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    return text.substring(1, text.length() - 1);
                }
            } else if (fieldName.kind() == SyntaxKind.IDENTIFIER_TOKEN) {
                return fieldName.toString().trim();
            }
            return null;
        }

        /**
         * Checks if the given expression references a function with @Activity annotation.
         */
        private boolean hasActivityAnnotation(ExpressionNode expression) {
            return WorkflowPluginUtils.hasWorkflowAnnotation(expression, semanticModel, 
                    WorkflowConstants.ACTIVITY_ANNOTATION);
        }

        private void reportCallActivityDiagnostic(RemoteMethodCallActionNode node) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_107,
                    WorkflowConstants.CALL_ACTIVITY_MISSING_ACTIVITY_ANNOTATION,
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.methodName().location()));
        }

        private void reportMissingRequiredParam(RemoteMethodCallActionNode node, String paramName) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_109,
                    String.format(WorkflowConstants.CALL_ACTIVITY_MISSING_REQUIRED_PARAM, paramName),
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.arguments().get(1).location()));
        }

        private void reportExtraParam(RemoteMethodCallActionNode node, String paramName) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_110,
                    String.format(WorkflowConstants.CALL_ACTIVITY_EXTRA_PARAM, paramName),
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.arguments().get(1).location()));
        }

        private void reportRestParamsNotSupported(RemoteMethodCallActionNode node) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_111,
                    WorkflowConstants.CALL_ACTIVITY_REST_PARAMS_NOT_SUPPORTED,
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.arguments().get(0).location()));
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
            if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
                return false;
            }
            FunctionSymbol functionSymbol = (FunctionSymbol) symbolOpt.get();
            return WorkflowPluginUtils.hasWorkflowAnnotation(functionSymbol, 
                    WorkflowConstants.ACTIVITY_ANNOTATION);
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
     * Checks if the type looks like it's intended to be a Context type
     * (e.g., named "ctx" or "context") but isn't the actual workflow:Context type.
     */
    private boolean looksLikeContextType(TypeSymbol typeSymbol) {
        // For now, we don't do heuristic matching
        // This could be enhanced to check parameter names like "ctx" or "context"
        return false;
    }

    /**
     * Validates the events parameter type - should be a record with future<anydata> fields.
     * All fields in the record must be future types.
     */
    private boolean isValidEventsType(TypeSymbol typeSymbol) {
        TypeSymbol resolvedType = WorkflowPluginUtils.resolveTypeReference(typeSymbol);
        TypeDescKind kind = resolvedType.typeKind();

        // Must be a record type
        if (kind != TypeDescKind.RECORD) {
            return false;
        }

        // Check that it's a RecordTypeSymbol and all fields are future types
        if (resolvedType instanceof io.ballerina.compiler.api.symbols.RecordTypeSymbol) {
            io.ballerina.compiler.api.symbols.RecordTypeSymbol recordType = 
                    (io.ballerina.compiler.api.symbols.RecordTypeSymbol) resolvedType;
            
            // Get all record fields and validate each is a future type
            java.util.Map<String, io.ballerina.compiler.api.symbols.RecordFieldSymbol> fields = 
                    recordType.fieldDescriptors();
            
            if (fields.isEmpty()) {
                // Empty record is not a valid events record
                return false;
            }
            
            for (io.ballerina.compiler.api.symbols.RecordFieldSymbol field : fields.values()) {
                TypeSymbol fieldType = WorkflowPluginUtils.resolveTypeReference(field.typeDescriptor());
                
                // Each field must be a future type
                if (fieldType.typeKind() != TypeDescKind.FUTURE) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Validates return type is subtype of anydata|error.
     */
    private boolean isValidReturnType(TypeSymbol typeSymbol) {
        // Use kind-based checking as fallback since we don't have SemanticModel here
        return WorkflowPluginUtils.isSubtypeOfAnydataOrError(typeSymbol, null);
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, FunctionDefinitionNode functionNode,
                                   String code, String message) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(code, message, DiagnosticSeverity.ERROR);
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                functionNode.functionName().location()));
    }
}
