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
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (!(context.node() instanceof FunctionDefinitionNode functionNode)) {
            return;
        }

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
     * Validates @Process function signature.
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
        TypeSymbol inputType = null;
        TypeSymbol eventsType = null;

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
                eventsType = paramType;
                paramIndex++;
            } else {
                // No input yet - this could be input or events
                if (isValidEventsType(paramType)) {
                    // This is an events parameter (can come without input)
                    hasEvents = true;
                    eventsType = paramType;
                    paramIndex++;
                } else if (WorkflowPluginUtils.isSubtypeOfAnydata(paramType, semanticModel)) {
                    // This is an input parameter
                    if (hasEvents) {
                        // Input must come before events
                        reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_101);
                        return;
                    }
                    hasInput = true;
                    inputType = paramType;
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
        
        // Validate correlation keys if both input and events are present
        if (hasInput && hasEvents) {
            validateCorrelationKeys(functionNode, context, inputType, eventsType);
        }
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
     * Validates correlation key consistency between process input and signal types.
     * <p>
     * Rules:
     * <ul>
     *   <li>If process has events, input MUST have readonly fields for correlation</li>
     *   <li>All signal types must have the SAME readonly fields as the input</li>
     * </ul>
     * <p>
     * Note: The 'id' field fallback is no longer supported. Correlation is done
     * exclusively via readonly fields which become Temporal Search Attributes.
     */
    private void validateCorrelationKeys(FunctionDefinitionNode functionNode, SyntaxNodeAnalysisContext context,
                                          TypeSymbol inputType, TypeSymbol eventsType) {
        // Resolve the input type to get the record type symbol
        TypeSymbol resolvedInputType = WorkflowPluginUtils.resolveTypeReference(inputType);
        if (!(resolvedInputType instanceof RecordTypeSymbol inputRecordType)) {
            // Input is not a record type - this is valid for simple anydata types
            // In this case, we can't validate correlation keys
            return;
        }

        // Extract readonly fields from input type
        Map<String, TypeSymbol> inputReadonlyFields = extractReadonlyFields(inputRecordType);

        // Get signal types from events record
        List<RecordTypeSymbol> signalTypes = extractSignalTypes(eventsType);

        // If process has events but no readonly fields, report error
        if (inputReadonlyFields.isEmpty() && !signalTypes.isEmpty()) {
            reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_116);
            return;
        }

        // Validate each signal type has matching readonly fields
        for (RecordTypeSymbol signalType : signalTypes) {
            Map<String, TypeSymbol> signalReadonlyFields = extractReadonlyFields(signalType);
            String signalTypeName = signalType.getName().orElse("anonymous");

            // Check all input readonly fields exist in signal type
            for (Map.Entry<String, TypeSymbol> entry : inputReadonlyFields.entrySet()) {
                String fieldName = entry.getKey();
                TypeSymbol inputFieldType = entry.getValue();

                if (!signalReadonlyFields.containsKey(fieldName)) {
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_114,
                            signalTypeName, fieldName);
                    continue;
                }

                TypeSymbol signalFieldType = signalReadonlyFields.get(fieldName);
                if (!typesAreEqual(inputFieldType, signalFieldType)) {
                    reportDiagnostic(context, functionNode, WorkflowDiagnostic.WORKFLOW_115,
                            fieldName,
                            inputFieldType.signature(),
                            signalTypeName,
                            signalFieldType.signature());
                }
            }
        }
    }

    /**
     * Extracts readonly fields from a record type.
     *
     * @param recordType the record type symbol
     * @return map of readonly field names to their type symbols
     */
    private Map<String, TypeSymbol> extractReadonlyFields(RecordTypeSymbol recordType) {
        Map<String, TypeSymbol> readonlyFields = new LinkedHashMap<>();

        for (Map.Entry<String, RecordFieldSymbol> entry : recordType.fieldDescriptors().entrySet()) {
            RecordFieldSymbol field = entry.getValue();
            // Check if field has READONLY qualifier
            if (field.qualifiers().contains(io.ballerina.compiler.api.symbols.Qualifier.READONLY)) {
                readonlyFields.put(entry.getKey(), field.typeDescriptor());
            }
        }

        return readonlyFields;
    }

    /**
     * Extracts signal types from the events record type.
     * <p>
     * The events record contains future<T> fields where T is the signal type.
     *
     * @param eventsType the events record type symbol
     * @return list of signal record type symbols
     */
    private List<RecordTypeSymbol> extractSignalTypes(TypeSymbol eventsType) {
        List<RecordTypeSymbol> signalTypes = new ArrayList<>();

        TypeSymbol resolvedEventsType = WorkflowPluginUtils.resolveTypeReference(eventsType);
        if (!(resolvedEventsType instanceof RecordTypeSymbol eventsRecordType)) {
            return signalTypes;
        }

        for (RecordFieldSymbol field : eventsRecordType.fieldDescriptors().values()) {
            TypeSymbol fieldType = WorkflowPluginUtils.resolveTypeReference(field.typeDescriptor());

            // Field should be future<T>
            if (fieldType.typeKind() == TypeDescKind.FUTURE) {
                // Get the type parameter of the future
                if (fieldType instanceof io.ballerina.compiler.api.symbols.FutureTypeSymbol futureType) {
                    Optional<TypeSymbol> typeParam = futureType.typeParameter();
                    if (typeParam.isPresent()) {
                        TypeSymbol signalType = WorkflowPluginUtils.resolveTypeReference(typeParam.get());
                        if (signalType instanceof RecordTypeSymbol) {
                            signalTypes.add((RecordTypeSymbol) signalType);
                        }
                    }
                }
            }
        }

        return signalTypes;
    }

    /**
     * Compares two type symbols for equality.
     *
     * @param type1 the first type
     * @param type2 the second type
     * @return true if the types are equal
     */
    private boolean typesAreEqual(TypeSymbol type1, TypeSymbol type2) {
        // Compare by signature for simplicity
        return type1.signature().equals(type2.signature());
    }

    /**
     * Validates @Activity function signature.
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
                if (param.paramKind() == ParameterKind.REQUIRED) {
                    Optional<String> nameOpt = param.getName();
                    String paramName = nameOpt.orElse("unnamed");
                    reportMissingRequiredParam(callNode, paramName);
                }
            }
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

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, FunctionDefinitionNode functionNode,
                                   WorkflowDiagnostic diagnostic, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                functionNode.functionName().location()));
    }
}
