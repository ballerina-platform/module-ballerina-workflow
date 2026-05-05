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
import io.ballerina.compiler.api.symbols.ConstantSymbol;
import io.ballerina.compiler.api.symbols.FunctionSymbol;
import io.ballerina.compiler.api.symbols.FunctionTypeSymbol;
import io.ballerina.compiler.api.symbols.FutureTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ParameterKind;
import io.ballerina.compiler.api.symbols.ParameterSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TupleTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.DefaultableParameterNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FieldAccessExpressionNode;
import io.ballerina.compiler.syntax.tree.ForkStatementNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.ListConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.NamedWorkerDeclarationNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeVisitor;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.ParameterNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.RemoteMethodCallActionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SpecificFieldNode;
import io.ballerina.compiler.syntax.tree.StartActionNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TupleTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.TypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.TypedBindingPatternNode;
import io.ballerina.compiler.syntax.tree.VariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.WaitFieldsListNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

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
            // Validate no direct @Activity function calls are made
            validateNoDirectActivityCalls(functionNode, context);
            // Validate no time:utcNow() calls are made (non-deterministic)
            validateNoUtcNowCalls(functionNode, context);
            // Validate no wait { ... } (multiple wait) is used on event futures
            validateNoWaitMultiple(functionNode, context);
            // Validate ctx->await usage: futures from events param, type order matches
            validateAwaitUsage(functionNode, context);
            // Validate no concurrency primitives (worker/fork/start) inside @Workflow
            validateNoConcurrencyPrimitives(functionNode, context);
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
                if (!WorkflowPluginUtils.isSubtypeOfAnydata(paramType, semanticModel)
                        && !WorkflowPluginUtils.isClientObjectType(paramType)) {
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
            // Names of parameters whose declared type is a client object —
            // arguments at these names must be simple module-level final
            // client references (validated below).
            Set<String> clientParamNames = new HashSet<>();
            
            for (ParameterSymbol param : expectedParams) {
                // Skip typedesc parameters — they are type metadata provided by
                // the compiler's dependent-typing mechanism, not user-supplied data.
                if (param.typeDescriptor().typeKind() == TypeDescKind.TYPEDESC) {
                    continue;
                }
                Optional<String> nameOpt = param.getName();
                if (nameOpt.isPresent()) {
                    String pName = normalizeIdentifier(nameOpt.get());
                    expectedParamNames.add(pName);
                    // Required if not default-able
                    if (param.paramKind() == ParameterKind.REQUIRED) {
                        requiredParamNames.add(pName);
                    }
                    if (WorkflowPluginUtils.isClientObjectType(param.typeDescriptor())) {
                        clientParamNames.add(pName);
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

            // Validate that arguments bound to client-object parameters are
            // simple references to module-level final client variables.
            validateClientArgs(paramsArg, clientParamNames);
        }

        /**
         * Iterates the args mapping constructor and, for every field whose
         * key matches a client-object activity parameter, asserts that the
         * field value is a simple name reference resolving to a module-level
         * {@code final} {@code client object} variable. Reports
         * {@code WORKFLOW_124} (shape) or {@code WORKFLOW_125} (target).
         */
        private void validateClientArgs(FunctionArgumentNode paramsArg,
                                        Set<String> clientParamNames) {
            if (clientParamNames.isEmpty()) {
                return;
            }
            if (!(paramsArg instanceof PositionalArgumentNode posArg)) {
                return;
            }
            ExpressionNode expr = posArg.expression();
            if (expr.kind() != SyntaxKind.MAPPING_CONSTRUCTOR) {
                return;
            }
            MappingConstructorExpressionNode mapping = (MappingConstructorExpressionNode) expr;
            for (MappingFieldNode field : mapping.fields()) {
                if (!(field instanceof SpecificFieldNode specific)) {
                    continue;
                }
                String fieldName = extractFieldName(specific);
                if (fieldName == null || !clientParamNames.contains(fieldName)) {
                    continue;
                }
                if (specific.valueExpr().isEmpty()) {
                    // Shorthand mapping field: `{connection}`.
                    // Resolve the shorthand identifier symbol and enforce the
                    // same module-level final client constraint.
                    Optional<Symbol> symbolOpt = semanticModel.symbol(specific.fieldName());
                    if (symbolOpt.isEmpty()
                            || !WorkflowPluginUtils.isModuleLevelFinalClient(symbolOpt.get())) {
                        DiagnosticInfo info = new DiagnosticInfo(
                                WorkflowDiagnostic.WORKFLOW_125.getCode(),
                                WorkflowDiagnostic.WORKFLOW_125.getMessage(fieldName, fieldName),
                                WorkflowDiagnostic.WORKFLOW_125.getSeverity());
                        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                                info, specific.fieldName().location()));
                    }
                    continue;
                }
                ExpressionNode valueExpr = specific.valueExpr().get();
                if (!(valueExpr instanceof SimpleNameReferenceNode nameRef)) {
                    DiagnosticInfo info = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_124.getCode(),
                            WorkflowDiagnostic.WORKFLOW_124.getMessage(
                                    fieldName, valueExpr.toSourceCode().trim()),
                            WorkflowDiagnostic.WORKFLOW_124.getSeverity());
                    context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                            info, valueExpr.location()));
                    continue;
                }
                Optional<Symbol> symbolOpt = semanticModel.symbol(nameRef);
                if (symbolOpt.isEmpty()
                        || !WorkflowPluginUtils.isModuleLevelFinalClient(symbolOpt.get())) {
                    DiagnosticInfo info = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_125.getCode(),
                            WorkflowDiagnostic.WORKFLOW_125.getMessage(
                                    fieldName, nameRef.name().text()),
                            WorkflowDiagnostic.WORKFLOW_125.getSeverity());
                    context.reportDiagnostic(DiagnosticFactory.createDiagnostic(
                            info, nameRef.location()));
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
         * Normalizes an identifier by removing the leading quote from escaped identifiers.
         * For example, 'from -> from, 'type -> type, etc.
         */
        private String normalizeIdentifier(String identifier) {
            if (identifier != null && identifier.startsWith("'")) {
                return identifier.substring(1);
            }
            return identifier;
        }

        /**
         * Extracts the field name from a SpecificFieldNode.
         * Handles both string literal keys ("fieldName") and identifier keys (fieldName).
         * Also normalizes escaped identifiers (e.g., 'from -> from).
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
                String text = fieldName.toString().trim();
                // Normalize escaped identifiers (e.g., 'from -> from)
                return normalizeIdentifier(text);
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
            ModuleSymbol moduleSymbol = moduleOpt.get();
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
     * Validates that no {@code wait { ... }} (multiple wait) expressions are used
     * inside @Workflow functions. The runtime does not support
     * {@code handleWaitMultiple} for Temporal event futures. Users should use
     * {@code wait f1}, {@code wait f1|f2}, or {@code ctx->await()} instead.
     */
    private void validateNoWaitMultiple(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        WaitMultipleValidator validator = new WaitMultipleValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that detects {@code wait { f1, f2 }} expressions inside
     * workflow functions and reports them as compile-time errors.
     */
    private static class WaitMultipleValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;

        WaitMultipleValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
        }

        @Override
        public void visit(WaitFieldsListNode waitFieldsListNode) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_115.getCode(),
                    WorkflowDiagnostic.WORKFLOW_115.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_115.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    waitFieldsListNode.location()));
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

    // =========================================================================
    // ctx->await usage validation
    // =========================================================================

    /**
     * Extracts the events record parameter name from a @Workflow function, or null if absent.
     * The events parameter is the record parameter whose every field is a {@code future<T>}.
     */
    private String getEventsParameterName(FunctionDefinitionNode functionNode, SemanticModel semanticModel) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(functionNode);
        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return null;
        }
        FunctionTypeSymbol typeSymbol = ((FunctionSymbol) symbolOpt.get()).typeDescriptor();
        Optional<List<ParameterSymbol>> paramsOpt = typeSymbol.params();
        if (paramsOpt.isEmpty()) {
            return null;
        }
        for (ParameterSymbol param : paramsOpt.get()) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(param.typeDescriptor());
            if (resolved.typeKind() == TypeDescKind.RECORD && resolved instanceof RecordTypeSymbol rec) {
                boolean allFutures = !rec.fieldDescriptors().isEmpty()
                        && rec.fieldDescriptors().values().stream()
                        .allMatch(f -> WorkflowPluginUtils.resolveTypeReference(
                                f.typeDescriptor()).typeKind() == TypeDescKind.FUTURE);
                if (allFutures) {
                    return param.getName().orElse(null);
                }
            }
        }
        return null;
    }

    /**
     * Validates uses of {@code ctx->await()} inside a @Workflow function:
     * <ol>
     *   <li>Every future in the array literal must come from the workflow's events parameter
     *       (field access on the events record).</li>
     *   <li>When the declared return type is a tuple, each tuple element type must match
     *       the inner type of the corresponding future.</li>
     * </ol>
     */
    private void validateAwaitUsage(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context) {
        String eventsParamName = getEventsParameterName(functionNode, context.semanticModel());
        AwaitValidator validator = new AwaitValidator(context, eventsParamName);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that validates {@code ctx->await()} calls.
     */
    private static class AwaitValidator extends NodeVisitor {
        private static final Pattern DECIMAL_INT_LITERAL = Pattern.compile("[+-]?[0-9][0-9_]*");
        private static final Pattern HEX_INT_LITERAL = Pattern.compile("[+-]?0[xX][0-9a-fA-F][0-9a-fA-F_]*");
        private static final Pattern BINARY_INT_LITERAL = Pattern.compile("[+-]?0[bB][01][01_]*");

        private final SyntaxNodeAnalysisContext context;
        private final SemanticModel semanticModel;
        private final String eventsParamName; // may be null if no events param

        AwaitValidator(SyntaxNodeAnalysisContext context, String eventsParamName) {
            this.context = context;
            this.semanticModel = context.semanticModel();
            this.eventsParamName = eventsParamName;
        }

        @Override
        public void visit(RemoteMethodCallActionNode callNode) {
            if (WorkflowConstants.AWAIT_METHOD.equals(callNode.methodName().name().text())) {
                validateAwaitArgs(callNode);
            }
            callNode.arguments().forEach(arg -> arg.accept(this));
        }

        private void validateAwaitArgs(RemoteMethodCallActionNode callNode) {
            SeparatedNodeList<FunctionArgumentNode> args = callNode.arguments();
            if (args.isEmpty()) {
                return;
            }
            FunctionArgumentNode firstArg = args.get(0);
            if (!(firstArg instanceof PositionalArgumentNode posArg)) {
                return;
            }
            ExpressionNode arrayExpr = posArg.expression();
            if (arrayExpr.kind() != SyntaxKind.LIST_CONSTRUCTOR) {
                return;
            }
            ListConstructorExpressionNode listNode = (ListConstructorExpressionNode) arrayExpr;

            // 1. Validate all futures come from the events parameter
            if (eventsParamName != null) {
                for (Node element : listNode.expressions()) {
                    if (element instanceof ExpressionNode elemExpr
                            && !isFutureFromEventsParam(elemExpr)) {
                        reportFutureNotFromEvents(elemExpr);
                    }
                }
            }

            // 2. Validate type order against the inferred return tuple (if present)
            validateTypeOrder(callNode, listNode);
        }

        /**
         * Returns true when {@code expr} is a plain field access on the events parameter,
         * e.g. {@code events.approverA}.
         */
        private boolean isFutureFromEventsParam(ExpressionNode expr) {
            if (expr.kind() != SyntaxKind.FIELD_ACCESS) {
                return false;
            }
            ExpressionNode receiver = ((FieldAccessExpressionNode) expr).expression();
            return receiver.kind() == SyntaxKind.SIMPLE_NAME_REFERENCE
                    && eventsParamName.equals(receiver.toString().trim());
        }

        /**
         * When the call's inferred return type is a tuple whose arity equals the futures
         * array size, verifies that each tuple element type matches the corresponding
         * future's inner type. When T is a non-tuple scalar, validates it against the
         * futures array: for a single future the scalar must match the future's inner type,
         * and for multiple futures a tuple type is required.
         * <p>
         * Errors are reported at the LHS type descriptor location when available:
         * for inline tuple types, each member's location is used; for named type references,
         * the reference itself is highlighted.
         */
        private void validateTypeOrder(RemoteMethodCallActionNode callNode,
                                        ListConstructorExpressionNode listNode) {
            Optional<TypeSymbol> callTypeOpt = semanticModel.typeOf(callNode);
            if (callTypeOpt.isEmpty()) {
                return;
            }
            // Strip |error to get the data type T
            TypeSymbol dataType = stripError(callTypeOpt.get());
            if (dataType == null) {
                return;
            }
            dataType = WorkflowPluginUtils.resolveTypeReference(dataType);
            SeparatedNodeList<Node> expressions = listNode.expressions();
            int futureCount = expressions.size();

            if (dataType.typeKind() != TypeDescKind.TUPLE) {
                // Allow array types and broadly-typed returns (anydata, json, etc.)
                TypeDescKind kind = dataType.typeKind();
                if (kind == TypeDescKind.ARRAY || kind == TypeDescKind.ANYDATA
                        || kind == TypeDescKind.JSON || kind == TypeDescKind.ANY
                        || kind == TypeDescKind.UNION) {
                    return;
                }
                // T is a definite scalar type — validate against the futures array
                if (futureCount == 1) {
                    validateScalarAgainstSingleFuture(dataType, expressions.get(0), callNode);
                } else if (futureCount > 1) {
                    DiagnosticInfo info = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_122.getCode(),
                            WorkflowDiagnostic.WORKFLOW_122.getMessage(
                                    futureCount, dataType.signature()),
                            WorkflowDiagnostic.WORKFLOW_122.getSeverity());
                    context.reportDiagnostic(
                            DiagnosticFactory.createDiagnostic(info, callNode.location()));
                }
                return;
            }
            TupleTypeSymbol tupleType = (TupleTypeSymbol) dataType;
            List<TypeSymbol> memberTypes = tupleType.memberTypeDescriptors();
            // Only validate when arity matches (full "wait-all" typed pattern)
            if (memberTypes.size() != expressions.size()) {
                return;
            }

            // Extract minCount to determine if partial-wait nilability is required
            OptionalInt minCountOpt = extractMinCount(callNode);

            // Resolve LHS type descriptor for precise error locations
            LhsTypeInfo lhsInfo = resolveLhsTypeInfo(callNode);

            for (int i = 0; i < memberTypes.size(); i++) {
                Node element = expressions.get(i);
                if (!(element instanceof ExpressionNode elemExpr)) {
                    continue;
                }
                Optional<TypeSymbol> elemTypeOpt = semanticModel.typeOf(elemExpr);
                if (elemTypeOpt.isEmpty()) {
                    continue;
                }
                TypeSymbol elemType = WorkflowPluginUtils.resolveTypeReference(elemTypeOpt.get());
                if (elemType.typeKind() != TypeDescKind.FUTURE) {
                    continue;
                }
                Optional<TypeSymbol> innerTypeOpt = ((FutureTypeSymbol) elemType).typeParameter();
                if (innerTypeOpt.isEmpty()) {
                    continue;
                }
                TypeSymbol innerType = WorkflowPluginUtils.resolveTypeReference(innerTypeOpt.get());
                TypeSymbol expectedType = WorkflowPluginUtils.resolveTypeReference(memberTypes.get(i));

                // Determine the best error location for this position
                Location errorLocation = lhsInfo.locationForMember(i, elemExpr.location());

                // When minCount < futureCount, each tuple member must be nilable
                if (minCountOpt.isPresent() && minCountOpt.getAsInt() < futureCount
                        && !isNilable(expectedType)) {
                    DiagnosticInfo info = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_123.getCode(),
                            WorkflowDiagnostic.WORKFLOW_123.getMessage(
                                    i, innerType.signature(),
                                    minCountOpt.getAsInt(), futureCount),
                            WorkflowDiagnostic.WORKFLOW_123.getSeverity());
                    context.reportDiagnostic(
                            DiagnosticFactory.createDiagnostic(info, errorLocation));
                    continue;
                }

                // Strip nil from expected type to support partial-wait nullable tuples
                // (e.g. [T1?, T2?, T3?] where T? = T|())
                TypeSymbol compareType = stripNil(expectedType);
                if (compareType == null) {
                    compareType = expectedType;
                }
                // Check bidirectional subtyping (structural equivalence)
                if (!innerType.subtypeOf(compareType) || !compareType.subtypeOf(innerType)) {
                    DiagnosticInfo info = new DiagnosticInfo(
                            WorkflowDiagnostic.WORKFLOW_117.getCode(),
                            WorkflowDiagnostic.WORKFLOW_117.getMessage(
                                    i, expectedType.signature(), innerType.signature()),
                            WorkflowDiagnostic.WORKFLOW_117.getSeverity());
                    context.reportDiagnostic(
                            DiagnosticFactory.createDiagnostic(info, errorLocation));
                }
            }
        }

        /**
         * Validates that a scalar (non-tuple) return type {@code T} matches the inner type
         * of a single future in the {@code ctx->await()} call.
         */
        private void validateScalarAgainstSingleFuture(TypeSymbol scalarType, Node futureNode,
                                                        RemoteMethodCallActionNode callNode) {
            if (!(futureNode instanceof ExpressionNode elemExpr)) {
                return;
            }
            Optional<TypeSymbol> elemTypeOpt = semanticModel.typeOf(elemExpr);
            if (elemTypeOpt.isEmpty()) {
                return;
            }
            TypeSymbol elemType = WorkflowPluginUtils.resolveTypeReference(elemTypeOpt.get());
            if (elemType.typeKind() != TypeDescKind.FUTURE) {
                return;
            }
            Optional<TypeSymbol> innerTypeOpt = ((FutureTypeSymbol) elemType).typeParameter();
            if (innerTypeOpt.isEmpty()) {
                return;
            }
            TypeSymbol innerType = WorkflowPluginUtils.resolveTypeReference(innerTypeOpt.get());
            if (!innerType.subtypeOf(scalarType) || !scalarType.subtypeOf(innerType)) {
                DiagnosticInfo info = new DiagnosticInfo(
                        WorkflowDiagnostic.WORKFLOW_121.getCode(),
                        WorkflowDiagnostic.WORKFLOW_121.getMessage(
                                scalarType.signature(), innerType.signature()),
                        WorkflowDiagnostic.WORKFLOW_121.getSeverity());
                context.reportDiagnostic(
                        DiagnosticFactory.createDiagnostic(info, callNode.location()));
            }
        }

        /**
         * Strips {@code |error} from a union type and returns the remaining single member,
         * or {@code null} if the result is ambiguous.
         */
        private static TypeSymbol stripError(TypeSymbol type) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(type);
            if (resolved.typeKind() == TypeDescKind.ERROR) {
                return null;
            }
            if (resolved.typeKind() != TypeDescKind.UNION) {
                return resolved;
            }
            List<TypeSymbol> nonErrors = ((UnionTypeSymbol) resolved).memberTypeDescriptors().stream()
                    .filter(m -> {
                        TypeSymbol r = WorkflowPluginUtils.resolveTypeReference(m);
                        return r.typeKind() != TypeDescKind.ERROR;
                    })
                    .toList();
            return nonErrors.size() == 1 ? nonErrors.get(0) : null;
        }

        /**
         * Strips {@code ()} (nil) from a union type, returning the remaining single member.
         * Used to compare nullable tuple members (e.g. {@code T?} = {@code T|()}) against
         * non-nullable future inner types. Returns {@code null} if no nil was found or
         * the result is ambiguous.
         */
        private static TypeSymbol stripNil(TypeSymbol type) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(type);
            if (resolved.typeKind() != TypeDescKind.UNION) {
                return null; // not a union — nothing to strip
            }
            List<TypeSymbol> nonNils = ((UnionTypeSymbol) resolved).memberTypeDescriptors().stream()
                    .filter(m -> {
                        TypeSymbol r = WorkflowPluginUtils.resolveTypeReference(m);
                        return r.typeKind() != TypeDescKind.NIL;
                    })
                    .toList();
            return nonNils.size() == 1 ? WorkflowPluginUtils.resolveTypeReference(nonNils.get(0)) : null;
        }

        /**
         * Checks whether a type is nilable (includes {@code ()} in a union, or is nil itself).
         */
        private static boolean isNilable(TypeSymbol type) {
            TypeSymbol resolved = WorkflowPluginUtils.resolveTypeReference(type);
            if (resolved.typeKind() == TypeDescKind.NIL) {
                return true;
            }
            if (resolved.typeKind() == TypeDescKind.UNION) {
                return ((UnionTypeSymbol) resolved).memberTypeDescriptors().stream()
                        .anyMatch(m -> WorkflowPluginUtils.resolveTypeReference(m)
                                .typeKind() == TypeDescKind.NIL);
            }
            return false;
        }

        /**
         * Extracts the {@code minCount} value from the {@code ctx->await()} call arguments,
         * if it is a literal integer. Returns empty if not provided or not a literal.
         */
        private OptionalInt extractMinCount(RemoteMethodCallActionNode callNode) {
            SeparatedNodeList<FunctionArgumentNode> args = callNode.arguments();
            for (int i = 0; i < args.size(); i++) {
                FunctionArgumentNode arg = args.get(i);
                // Named argument: minCount = <value>
                if (arg instanceof NamedArgumentNode namedArg
                        && "minCount".equals(namedArg.argumentName().name().text())) {
                    return extractLiteralInt(namedArg.expression());
                }
                // Second positional argument is minCount
                if (i == 1 && arg instanceof PositionalArgumentNode posArg) {
                    return extractLiteralInt(posArg.expression());
                }
            }
            return OptionalInt.empty();
        }

        /**
         * Attempts to parse an expression node as an integer literal.
         * Returns empty for non-literal expressions (variables, method calls, etc.).
         */
        private OptionalInt extractLiteralInt(ExpressionNode expr) {
            OptionalInt constValue = extractConstInt(expr);
            if (constValue.isPresent()) {
                return constValue;
            }
            String text = expr.toSourceCode().trim();
            return parseBallerinaIntLiteral(text);
        }

        private OptionalInt extractConstInt(ExpressionNode expr) {
            Optional<Symbol> symbolOpt = semanticModel.symbol(expr);
            if (symbolOpt.isPresent() && symbolOpt.get().kind() == SymbolKind.CONSTANT
                    && symbolOpt.get() instanceof ConstantSymbol constantSymbol) {
                Optional<String> constValue = constantSymbol.resolvedValue();
                if (constValue.isPresent()) {
                    return parseBallerinaIntLiteral(constValue.get().trim());
                }
            }
            return OptionalInt.empty();
        }

        private static OptionalInt parseBallerinaIntLiteral(String text) {
            if (text.isEmpty()) {
                return OptionalInt.empty();
            }

            if (!DECIMAL_INT_LITERAL.matcher(text).matches()
                    && !HEX_INT_LITERAL.matcher(text).matches()
                    && !BINARY_INT_LITERAL.matcher(text).matches()) {
                return OptionalInt.empty();
            }

            boolean negative = text.charAt(0) == '-';
            boolean hasSign = text.charAt(0) == '-' || text.charAt(0) == '+';
            String unsignedText = hasSign ? text.substring(1) : text;

            int radix = 10;
            if (unsignedText.startsWith("0x") || unsignedText.startsWith("0X")) {
                radix = 16;
                unsignedText = unsignedText.substring(2);
            } else if (unsignedText.startsWith("0b") || unsignedText.startsWith("0B")) {
                radix = 2;
                unsignedText = unsignedText.substring(2);
            }

            String normalizedDigits = unsignedText.replace("_", "");
            try {
                long value = Long.parseLong(normalizedDigits, radix);
                if (negative) {
                    value = -value;
                }
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of((int) value);
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }

        /**
         * Walks up from the {@code ctx->await()} call node through the parent chain
         * to find the enclosing variable declaration's type descriptor. Returns an
         * {@link LhsTypeInfo} that can produce the best error location for each
         * tuple member position.
         *
         * <p>Parent chain example:
         * {@code RemoteMethodCallActionNode -> CheckExpressionNode -> VariableDeclarationNode}
         */
        private static LhsTypeInfo resolveLhsTypeInfo(RemoteMethodCallActionNode callNode) {
            NonTerminalNode cursor = callNode.parent();
            // Walk up past expressions (check, parenthesized, etc.) to the statement
            while (cursor != null
                    && cursor.kind() != SyntaxKind.LOCAL_VAR_DECL
                    && cursor.kind() != SyntaxKind.ASSIGNMENT_STATEMENT) {
                cursor = cursor.parent();
            }
            if (cursor instanceof VariableDeclarationNode varDecl) {
                TypedBindingPatternNode typedBinding = varDecl.typedBindingPattern();
                TypeDescriptorNode typeDesc = typedBinding.typeDescriptor();
                if (typeDesc instanceof TupleTypeDescriptorNode tupleDesc) {
                    return new LhsTypeInfo(tupleDesc.memberTypeDesc(), null);
                }
                // Named type reference (e.g. MyTuple results = ...) — point to the reference
                if (typeDesc instanceof SimpleNameReferenceNode) {
                    return new LhsTypeInfo(null, typeDesc.location());
                }
            }
            return new LhsTypeInfo(null, null);
        }

        /**
         * Holds the resolved LHS type descriptor information for error location reporting.
         * For inline tuple types, provides per-member locations. For named type references,
         * provides the reference location.
         *
         * @param tupleMemberNodes  member nodes of an inline tuple type descriptor, or null
         * @param typeRefLocation   location of a named type reference, or null
         */
        private record LhsTypeInfo(
                SeparatedNodeList<Node> tupleMemberNodes,
                Location typeRefLocation
        ) {
            /**
             * Returns the best error location for the tuple member at {@code index}.
             * <ol>
             *   <li>If inline tuple: the member type node at that position</li>
             *   <li>If named type ref: the type reference name</li>
             *   <li>Otherwise: the fallback (future expression location)</li>
             * </ol>
             */
            Location locationForMember(int index, Location fallback) {
                if (tupleMemberNodes != null && index < tupleMemberNodes.size()) {
                    return tupleMemberNodes.get(index).location();
                }
                if (typeRefLocation != null) {
                    return typeRefLocation;
                }
                return fallback;
            }
        }

        private void reportFutureNotFromEvents(ExpressionNode expr) {
            DiagnosticInfo info = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_116.getCode(),
                    WorkflowDiagnostic.WORKFLOW_116.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_116.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info, expr.location()));
        }
    }

    // =========================================================================
    // Concurrency primitive restrictions
    // =========================================================================

    /**
     * Validates that no concurrency primitives are used inside a @Workflow function.
     * Worker declarations, fork statements, and start actions all run code on separate
     * strands and bypass the workflow scheduler, breaking determinism.
     */
    private void validateNoConcurrencyPrimitives(FunctionDefinitionNode functionNode,
                                                  SyntaxNodeAnalysisContext context) {
        ConcurrencyPrimitivesValidator validator = new ConcurrencyPrimitivesValidator(context);
        functionNode.functionBody().accept(validator);
    }

    /**
     * Node visitor that reports errors for worker declarations, fork statements, and
     * start actions found inside @Workflow functions.
     */
    private static class ConcurrencyPrimitivesValidator extends NodeVisitor {
        private final SyntaxNodeAnalysisContext context;

        ConcurrencyPrimitivesValidator(SyntaxNodeAnalysisContext context) {
            this.context = context;
        }

        @Override
        public void visit(NamedWorkerDeclarationNode workerNode) {
            DiagnosticInfo info = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_118.getCode(),
                    WorkflowDiagnostic.WORKFLOW_118.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_118.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info,
                    workerNode.workerKeyword().location()));
            // Do NOT visit children — this worker body may itself contain further nodes
            // but the outer error is sufficient; inner code is also invalid but would spam.
        }

        @Override
        public void visit(ForkStatementNode forkNode) {
            DiagnosticInfo info = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_119.getCode(),
                    WorkflowDiagnostic.WORKFLOW_119.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_119.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info,
                    forkNode.forkKeyword().location()));
        }

        @Override
        public void visit(StartActionNode startNode) {
            DiagnosticInfo info = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_120.getCode(),
                    WorkflowDiagnostic.WORKFLOW_120.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_120.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(info,
                    startNode.startKeyword().location()));
        }
    }
}
