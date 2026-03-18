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

package io.ballerina.lib.workflow.compiler;

import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.MethodSymbol;
import io.ballerina.compiler.api.symbols.ObjectTypeSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.Qualifier;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.ResourceMethodSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.DefaultableParameterNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.ParameterNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validation task for workflow @Workflow and @Activity function signatures.
 * <p>
 * Validates:
 * <ul>
 *   <li>@Workflow functions have valid signature: (Context?, anydata input, record{future<anydata>...} events?)</li>
 *   <li>@Activity functions have anydata parameters and anydata|error return type</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class WorkflowValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionDefinitionNode functionNode)) {
            return;
        }

        SemanticModel semanticModel = context.semanticModel();

        // Check if function has @Workflow annotation
        if (hasAnnotation(functionNode, semanticModel, WorkflowConstants.PROCESS_ANNOTATION)) {
            validateProcessFunction(functionNode, context);
            // Validate callActivity calls within the process function
            validateCallActivityUsage(functionNode, context);
            // Validate callRemoteActivity and callResourceActivity calls
            validateClientActivityUsage(functionNode, context);
            // Validate no direct @Activity function calls are made
            validateNoDirectActivityCalls(functionNode, context);
            // Validate no time:utcNow() calls are made (non-deterministic)
            validateNoUtcNowCalls(functionNode, context);
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
     * Validates @Workflow function signature.
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
            reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_106);
            return;
        }

        int paramIndex = 0;
        boolean hasInput = false;
        boolean hasEvents = false;

        // Check first parameter - could be Context, input, or events
        ParameterSymbol firstParam = params.get(paramIndex);
        TypeSymbol firstParamType = firstParam.typeDescriptor();

        if (WorkflowPluginUtils.isContextType(firstParamType)) {
            paramIndex++;
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
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_102);
                    return;
                }
                if (hasEvents) {
                    // Already have events parameter, this is an error
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_106);
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
                        reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_101);
                        return;
                    }
                    hasInput = true;
                    paramIndex++;
                } else {
                    // Parameter is neither anydata nor events record - error
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_101);
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
            if (!WorkflowPluginUtils.isSubtypeOfAnydataOrError(returnType, context.semanticModel())) {
                reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_105);
            }
        }
    }

    /**
     * Validates @Activity function signature.
     * <ul>
     *   <li>All parameters must be subtypes of anydata</li>
     *   <li>typedesc parameters are only allowed for dependently-typed external
     *       functions with inferred default {@code <>}. Required or explicitly
     *       defaultable typedesc parameters are rejected.</li>
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

        // Validate all parameters are subtypes of anydata.
        // typedesc parameters are only allowed for dependently-typed functions
        // (i.e., with inferred default <>).
        Optional<List<ParameterSymbol>> paramsOpt = typeSymbol.params();
        if (paramsOpt.isPresent()) {
            List<ParameterSymbol> params = paramsOpt.get();
            SeparatedNodeList<ParameterNode> syntaxParams =
                    functionNode.functionSignature().parameters();

            for (int i = 0; i < params.size(); i++) {
                TypeSymbol paramType = params.get(i).typeDescriptor();
                if (paramType.typeKind() == TypeDescKind.TYPEDESC) {
                    // Only allow typedesc if it uses the inferred default <>
                    // (dependently-typed function). Reject required or
                    // explicitly defaultable typedesc params.
                    if (!isInferredTypedescDefault(syntaxParams, i)) {
                        reportDiagnostic(context, functionNode,
                                WorkflowDiagnostic.WORKFLOW_114);
                        return;
                    }
                    continue;
                }
                if (!WorkflowPluginUtils.isSubtypeOfAnydata(paramType, semanticModel)) {
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_103);
                    return;
                }
            }
        }

        // Validate return type is subtype of anydata|error
        Optional<TypeSymbol> returnTypeOpt = typeSymbol.returnTypeDescriptor();
        if (returnTypeOpt.isPresent()) {
            TypeSymbol returnType = returnTypeOpt.get();
            if (!WorkflowPluginUtils.isSubtypeOfAnydataOrError(returnType, semanticModel)) {
                reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_104);
            }
        }
    }

    /**
     * Checks whether the syntax-tree parameter at {@code index} is a
     * {@link DefaultableParameterNode} whose default expression is the
     * inferred typedesc default {@code <>}.
     */
    private static boolean isInferredTypedescDefault(
            SeparatedNodeList<ParameterNode> syntaxParams, int index) {
        if (index >= syntaxParams.size()) {
            return false;
        }
        ParameterNode paramNode = syntaxParams.get(index);
        if (paramNode instanceof DefaultableParameterNode defaultParam) {
            return defaultParam.expression().kind()
                    == SyntaxKind.INFERRED_TYPEDESC_DEFAULT;
        }
        return false;
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
     * Validates that the args map keys match the activity function parameters.
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
                    if (firstArg instanceof PositionalArgumentNode posArg) {
                        ExpressionNode expression = posArg.expression();
                        
                        // Check if the function reference has @Activity annotation
                        if (!hasActivityAnnotation(expression)) {
                            reportCallActivityDiagnostic(remoteCallNode);
                        } else {
                            // Validate parameters match
                            // If second argument is provided, validate it matches activity params
                            // If no second argument, validate activity has no required params
                            if (arguments.size() >= 2) {
                                FunctionArgumentNode secondArg = arguments.get(1);
                                validateParametersMatch(remoteCallNode, expression, secondArg);
                            } else {
                                // No args provided - validate activity has no required parameters
                                validateNoArgsActivity(remoteCallNode, expression);
                            }
                        }
                    }
                }
            }

            // Continue visiting child nodes
            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Validates that an activity function with no args provided has no required parameters.
         */
        private void validateNoArgsActivity(RemoteMethodCallActionNode callNode,
                                            ExpressionNode activityFuncExpr) {
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
            if (paramsOpt.isEmpty() || paramsOpt.get().isEmpty()) {
                // Activity has no parameters - valid for no-args call
                return;
            }

            // Check for required parameters
            List<ParameterSymbol> expectedParams = paramsOpt.get();
            for (ParameterSymbol param : expectedParams) {
                // Skip typedesc parameters — not user-supplied data
                if (param.typeDescriptor().typeKind() == TypeDescKind.TYPEDESC) {
                    continue;
                }
                if (param.paramKind() == ParameterKind.REQUIRED) {
                    Optional<String> nameOpt = param.getName();
                    String paramName = nameOpt.orElse("unnamed");
                    reportMissingRequiredParam(callNode, paramName);
                }
            }
        }

        /**
         * Validates that the args map keys match the activity function's parameter names.
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
                // Skip typedesc parameters — they are type metadata provided by
                // the compiler's dependent-typing mechanism, not user-supplied data.
                if (param.typeDescriptor().typeKind() == TypeDescKind.TYPEDESC) {
                    continue;
                }
                Optional<String> nameOpt = param.getName();
                if (nameOpt.isPresent()) {
                    expectedParamNames.add(nameOpt.get());
                    // Required if not default-able
                    if (param.paramKind() == ParameterKind.REQUIRED) {
                        requiredParamNames.add(nameOpt.get());
                    }
                }
            }

            // Extract parameter names from the args map argument
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
         * Extracts parameter names from the args map (second argument).
         */
        private Set<String> extractProvidedParamNames(FunctionArgumentNode paramsArg) {
            Set<String> paramNames = new HashSet<>();
            
            if (!(paramsArg instanceof PositionalArgumentNode posArg)) {
                return paramNames;
            }

            ExpressionNode expr = posArg.expression();
            
            // Check if it's a mapping constructor expression like {"param1": value1, "param2": value2}
            if (expr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR) {
                MappingConstructorExpressionNode mappingExpr = (MappingConstructorExpressionNode) expr;
                SeparatedNodeList<MappingFieldNode> fields = mappingExpr.fields();
                
                for (MappingFieldNode field : fields) {
                    if (field instanceof SpecificFieldNode specificField) {
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
                    WorkflowDiagnostic.WORKFLOW_107.getCode(),
                    WorkflowDiagnostic.WORKFLOW_107.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_107.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.methodName().location()));
        }

        private void reportMissingRequiredParam(RemoteMethodCallActionNode node, String paramName) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_109.getCode(),
                    WorkflowDiagnostic.WORKFLOW_109.getMessage(paramName),
                    WorkflowDiagnostic.WORKFLOW_109.getSeverity());
            // Report on the second argument if it exists, otherwise on the method name
            var location = node.arguments().size() > 1 
                    ? node.arguments().get(1).location() 
                    : node.methodName().location();
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
        }

        private void reportExtraParam(RemoteMethodCallActionNode node, String paramName) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_110.getCode(),
                    WorkflowDiagnostic.WORKFLOW_110.getMessage(paramName),
                    WorkflowDiagnostic.WORKFLOW_110.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.arguments().get(1).location()));
        }

        private void reportRestParamsNotSupported(RemoteMethodCallActionNode node) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_111.getCode(),
                    WorkflowDiagnostic.WORKFLOW_111.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_111.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.arguments().get(0).location()));
        }
    }

    /**
     * Validates callRemoteActivity() and callResourceActivity() calls within @Workflow functions.
     * Checks that the client has the specified method and that method params/return types are valid.
     */
    private void validateClientActivityUsage(FunctionDefinitionNode functionNode,
                                              SyntaxNodeAnalysisContext context) {
        ClientActivityValidator validator = new ClientActivityValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that validates ctx->callRemoteActivity() and ctx->callResourceActivity() calls.
     * Ensures:
     * <ul>
     *   <li>First argument is a client object</li>
     *   <li>The client has the specified remote/resource method</li>
     *   <li>Method parameters are subtypes of anydata</li>
     *   <li>Method return type is a subtype of anydata|error</li>
     * </ul>
     */
    private static class ClientActivityValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;

        ClientActivityValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
            this.semanticModel = context.semanticModel();
        }

        @Override
        public void visit(RemoteMethodCallActionNode remoteCallNode) {
            String methodName = remoteCallNode.methodName().name().text();

            if (WorkflowConstants.CALL_REMOTE_ACTIVITY_FUNCTION.equals(methodName)) {
                validateCallRemoteActivity(remoteCallNode);
            } else if (WorkflowConstants.CALL_RESOURCE_ACTIVITY_FUNCTION.equals(methodName)) {
                validateCallResourceActivity(remoteCallNode);
            }

            // Continue visiting child nodes
            remoteCallNode.arguments().forEach(arg -> arg.accept(this));
        }

        /**
         * Validates a ctx->callRemoteActivity(connection, "methodName", args) call.
         */
        private void validateCallRemoteActivity(RemoteMethodCallActionNode callNode) {
            SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
            if (arguments.size() < 2) {
                return; // Let the type checker handle missing arguments
            }

            // First argument: must be a client object
            FunctionArgumentNode firstArg = arguments.get(0);
            if (!(firstArg instanceof PositionalArgumentNode posArg)) {
                return;
            }

            ExpressionNode connectionExpr = posArg.expression();
            Optional<TypeSymbol> connectionTypeOpt = semanticModel.typeOf(connectionExpr);
            if (connectionTypeOpt.isEmpty()) {
                return;
            }

            TypeSymbol connectionType = WorkflowPluginUtils.resolveTypeReference(
                    connectionTypeOpt.get());
            if (!isClientObjectType(connectionType)) {
                reportDiagnosticOnNode(callNode, WorkflowDiagnostic.WORKFLOW_115);
                return;
            }

            // Second argument: remote method name (string literal)
            FunctionArgumentNode secondArg = arguments.get(1);
            if (!(secondArg instanceof PositionalArgumentNode posArg2)) {
                return;
            }
            String remoteMethodName = extractStringLiteral(posArg2.expression());
            if (remoteMethodName == null) {
                return; // Non-literal string — skip compile-time validation
            }

            // Look up the remote method on the client type
            ObjectTypeSymbol objectType = (ObjectTypeSymbol) connectionType;
            Optional<MethodSymbol> remoteMethodOpt = findRemoteMethod(objectType, remoteMethodName);
            if (remoteMethodOpt.isEmpty()) {
                reportDiagnosticOnNode(callNode, WorkflowDiagnostic.WORKFLOW_116,
                        remoteMethodName);
            }
            // Note: parameter and return type validation is intentionally omitted.
            // Arguments are passed through map<anydata> (inherently anydata), and the
            // return value is converted via cloneWithType at runtime. Checking the
            // target method's declared types would be too restrictive for real clients
            // (e.g. http:Client's post() accepts http:RequestMessage which includes
            // non-anydata types like http:Request).
        }

        /**
         * Validates a ctx->callResourceActivity(connection, "get", "/path", args) call.
         */
        private void validateCallResourceActivity(RemoteMethodCallActionNode callNode) {
            SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
            if (arguments.size() < 3) {
                return; // Let the type checker handle missing arguments
            }

            // First argument: must be a client object
            FunctionArgumentNode firstArg = arguments.get(0);
            if (!(firstArg instanceof PositionalArgumentNode posArg)) {
                return;
            }

            ExpressionNode connectionExpr = posArg.expression();
            Optional<TypeSymbol> connectionTypeOpt = semanticModel.typeOf(connectionExpr);
            if (connectionTypeOpt.isEmpty()) {
                return;
            }

            TypeSymbol connectionType = WorkflowPluginUtils.resolveTypeReference(
                    connectionTypeOpt.get());
            if (!isClientObjectType(connectionType)) {
                reportDiagnosticOnNode(callNode, WorkflowDiagnostic.WORKFLOW_119);
                return;
            }

            // Second argument: accessor (string literal like "get", "post")
            FunctionArgumentNode secondArg = arguments.get(1);
            if (!(secondArg instanceof PositionalArgumentNode posArg2)) {
                return;
            }
            String accessor = extractStringLiteral(posArg2.expression());
            if (accessor == null) {
                return; // Non-literal string — skip compile-time validation
            }

            // Third argument: resource path (string literal)
            FunctionArgumentNode thirdArg = arguments.get(2);
            if (!(thirdArg instanceof PositionalArgumentNode posArg3)) {
                return;
            }
            String resourcePath = extractStringLiteral(posArg3.expression());
            if (resourcePath == null) {
                return; // Non-literal string — skip compile-time validation
            }

            // Look up the resource method on the client type.
            // For clients with rest path parameters (e.g. http:Client's
            // resource function get [string... path](...)), we match by
            // accessor only — the exact path is validated at runtime.
            ObjectTypeSymbol objectType = (ObjectTypeSymbol) connectionType;
            Optional<ResourceMethodSymbol> resourceMethodOpt =
                    findResourceMethod(objectType, accessor, resourcePath);
            if (resourceMethodOpt.isEmpty()) {
                reportDiagnosticOnNode(callNode, WorkflowDiagnostic.WORKFLOW_120,
                        accessor, resourcePath);
            }
            // Note: parameter and return type validation is intentionally omitted
            // (same reasoning as callRemoteActivity above).
        }

        /**
         * Checks if a type is a client object type.
         */
        private boolean isClientObjectType(TypeSymbol typeSymbol) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(typeSymbol);
            if (resolved.typeKind() != TypeDescKind.OBJECT) {
                return false;
            }
            if (resolved instanceof ObjectTypeSymbol objectType) {
                return objectType.qualifiers().contains(Qualifier.CLIENT);
            }
            return false;
        }

        /**
         * Finds a remote method on a client object type by name.
         */
        private Optional<MethodSymbol> findRemoteMethod(ObjectTypeSymbol objectType,
                                                         String methodName) {
            Map<String, MethodSymbol> methods = objectType.methods();
            MethodSymbol method = methods.get(methodName);
            if (method != null && method.qualifiers().contains(Qualifier.REMOTE)) {
                return Optional.of(method);
            }
            return Optional.empty();
        }

        /**
         * Finds a resource method on a client object type by accessor and path.
         * Handles rest path parameters: if the client has a resource method with the
         * matching accessor and a rest path parameter (e.g. [string... path]), it is
         * considered a match for any path.
         */
        private Optional<ResourceMethodSymbol> findResourceMethod(ObjectTypeSymbol objectType,
                                                                    String accessor,
                                                                    String resourcePath) {
            // Normalize the path: remove leading slash, split into segments
            String normalizedPath = resourcePath.startsWith("/")
                    ? resourcePath.substring(1) : resourcePath;

            for (MethodSymbol method : objectType.methods().values()) {
                if (!(method instanceof ResourceMethodSymbol resourceMethod)) {
                    continue;
                }
                // Check accessor matches
                Optional<String> nameOpt = resourceMethod.getName();
                if (nameOpt.isEmpty()) {
                    continue;
                }
                String methodAccessor = nameOpt.get();
                if (!accessor.equals(methodAccessor)) {
                    continue;
                }

                // Check resource path
                String methodPathSig = resourceMethod.resourcePath().signature();
                String normalizedMethodPath = methodPathSig.startsWith("/")
                        ? methodPathSig.substring(1) : methodPathSig;

                // Exact match
                if (normalizedPath.equals(normalizedMethodPath)) {
                    return Optional.of(resourceMethod);
                }

                // Rest path match: if the method has a rest path param like
                // [string... path] or [PathParamType... path], it matches any path
                if (methodPathSig.contains("...")) {
                    return Optional.of(resourceMethod);
                }
            }
            return Optional.empty();
        }

        /**
         * Extracts a string literal value from an expression node.
         *
         * @return the string value without quotes, or null if not a string literal
         */
        private String extractStringLiteral(ExpressionNode expr) {
            if (expr.kind() == SyntaxKind.STRING_LITERAL) {
                String text = expr.toString().trim();
                if (text.startsWith("\"") && text.endsWith("\"")) {
                    return text.substring(1, text.length() - 1);
                }
            }
            return null;
        }

        private void reportDiagnosticOnNode(RemoteMethodCallActionNode node,
                                             WorkflowDiagnostic diagnostic, Object... args) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    diagnostic.getCode(),
                    diagnostic.getMessage(args),
                    diagnostic.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    node.methodName().location()));
        }
    }

    /**
     * Validates that no time:utcNow() calls are made within @Workflow functions.
     * time:utcNow() is non-deterministic and should not be used inside workflows.
     * Users should use ctx.currentTime() instead.
     */
    private void validateNoUtcNowCalls(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        UtcNowCallValidator validator = new UtcNowCallValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that detects calls to time:utcNow() inside workflow functions.
     */
    private static class UtcNowCallValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;
        private static final String TIME_MODULE_ORG = "ballerina";
        private static final String TIME_MODULE_NAME = "time";
        private static final String UTC_NOW_FUNCTION = "utcNow";

        UtcNowCallValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
            this.semanticModel = context.semanticModel();
        }

        @Override
        public void visit(FunctionCallExpressionNode callNode) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
            if (symbolOpt.isPresent() && symbolOpt.get().kind() == SymbolKind.FUNCTION) {
                FunctionSymbol funcSymbol = (FunctionSymbol) symbolOpt.get();
                if (isTimeUtcNow(funcSymbol)) {
                    DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_113.getCode(),
                            WorkflowDiagnostic.WORKFLOW_113.getMessage(),
                            WorkflowDiagnostic.WORKFLOW_113.getSeverity());
                    context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                            callNode.functionName().location()));
                }
            }
            // Continue visiting child nodes
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        private boolean isTimeUtcNow(FunctionSymbol funcSymbol) {
            if (!UTC_NOW_FUNCTION.equals(funcSymbol.getName().orElse(""))) {
                return false;
            }
            var moduleOpt = funcSymbol.getModule();
            if (moduleOpt.isEmpty()) {
                return false;
            }
            var moduleSymbol = moduleOpt.get();
            return TIME_MODULE_ORG.equals(moduleSymbol.id().orgName())
                    && TIME_MODULE_NAME.equals(moduleSymbol.id().moduleName());
        }
    }

    /**
     * Validates that no direct @Activity function calls are made within @Workflow functions.
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
                    WorkflowDiagnostic.WORKFLOW_108.getCode(),
                    WorkflowDiagnostic.WORKFLOW_108.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_108.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    callNode.functionName().location()));
        }
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
        if (resolvedType instanceof RecordTypeSymbol recordType) {

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

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, FunctionDefinitionNode functionNode,
                                   WorkflowDiagnostic diagnostic) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                functionNode.functionName().location()));
    }
}
