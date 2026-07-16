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
import io.ballerina.compiler.api.symbols.FutureTypeSymbol;
import io.ballerina.compiler.api.symbols.RecordFieldSymbol;
import io.ballerina.compiler.api.symbols.RecordTypeSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.BasicLiteralNode;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.Location;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Validation task for {@code workflow:sendData} function calls.
 * <p>
 * Validates:
 * <ul>
 *   <li>The first argument is a function with the @Workflow annotation</li>
 *   <li>The target workflow function declares an events record parameter</li>
 *   <li>The {@code dataName} argument (when a literal or constant) matches a field
 *       of the workflow function's events record</li>
 *   <li>The {@code data} argument type matches the matched event future's inner type</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class SendEventValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private static final String WORKFLOW_PARAM_NAME = "workflow";
    private static final String DATA_NAME_PARAM_NAME = "dataName";
    private static final String DATA_PARAM_NAME = "data";

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionCallExpressionNode callNode)) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();

        if (!WorkflowFunctionCallUtils.isWorkflowModuleFunctionCall(callNode, semanticModel,
                WorkflowConstants.SEND_DATA_FUNCTION)) {
            return;
        }

        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        ExpressionNode workflowFuncExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 0, WORKFLOW_PARAM_NAME);
        if (workflowFuncExpr == null) {
            return;
        }

        Optional<FunctionSymbol> workflowFuncOpt =
                WorkflowPluginUtils.getWorkflowFunctionSymbol(workflowFuncExpr, semanticModel);
        if (workflowFuncOpt.isEmpty()) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_130, workflowFuncExpr.location(),
                    WorkflowConstants.SEND_DATA_FUNCTION);
            return;
        }
        FunctionSymbol workflowFunc = workflowFuncOpt.get();
        String workflowName = workflowFunc.getName().orElse("");

        // The target workflow must declare an events record to receive data.
        Optional<RecordTypeSymbol> eventsRecordOpt = WorkflowPluginUtils.getEventsRecordType(workflowFunc);
        if (eventsRecordOpt.isEmpty()) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_133, callNode.location(),
                    workflowName);
            return;
        }
        RecordTypeSymbol eventsRecord = eventsRecordOpt.get();

        // Validate the event name when it is statically resolvable
        // (string literal or constant reference).
        ExpressionNode dataNameExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 2, DATA_NAME_PARAM_NAME);
        if (dataNameExpr == null) {
            return;
        }
        String eventName = resolveStringValue(dataNameExpr, semanticModel);
        if (eventName == null) {
            // Dynamic event names cannot be validated at compile time.
            return;
        }

        Map<String, RecordFieldSymbol> eventFields = eventsRecord.fieldDescriptors();
        RecordFieldSymbol eventField = eventFields.get(eventName);
        if (eventField == null) {
            String availableNames = String.join(", ", new TreeSet<>(eventFields.keySet()));
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_134, dataNameExpr.location(),
                    eventName, workflowName, availableNames);
            return;
        }

        // Validate the data argument type against the event future's inner type.
        validateDataType(context, arguments, eventName, workflowName, eventField, semanticModel);
    }

    /**
     * Validates that the {@code data} argument's static type is a subtype of the
     * matched event future's inner type. Constructor expressions are skipped because
     * their static type is derived from the contextually expected type ({@code anydata}),
     * which would produce false positives.
     */
    private void validateDataType(SyntaxNodeAnalysisContext context,
                                  SeparatedNodeList<FunctionArgumentNode> arguments,
                                  String eventName, String workflowName,
                                  RecordFieldSymbol eventField, SemanticModel semanticModel) {
        ExpressionNode dataExpr = WorkflowFunctionCallUtils.getArgumentExpression(
                arguments, 3, DATA_PARAM_NAME);
        if (dataExpr == null) {
            return;
        }

        TypeSymbol fieldType = WorkflowPluginUtils.resolveTypeReference(eventField.typeDescriptor());
        if (fieldType.typeKind() != TypeDescKind.FUTURE) {
            return;
        }
        Optional<TypeSymbol> innerTypeOpt = ((FutureTypeSymbol) fieldType).typeParameter();
        if (innerTypeOpt.isEmpty()) {
            return;
        }
        TypeSymbol expectedType = innerTypeOpt.get();

        // Constructor expressions (mapping/list/table) are typed by their contextually
        // expected type (`anydata` here), so their static type cannot be compared with
        // subtypeOf without false positives. Validate the shape only: the event's inner
        // type must be able to accept a value of the constructor's kind. Member-level
        // conversion is handled by the runtime.
        if (dataExpr.kind() == SyntaxKind.MAPPING_CONSTRUCTOR
                || dataExpr.kind() == SyntaxKind.LIST_CONSTRUCTOR
                || dataExpr.kind() == SyntaxKind.TABLE_CONSTRUCTOR) {
            if (!WorkflowPluginUtils.canAcceptConstructorExpression(expectedType, dataExpr.kind())) {
                reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_135, dataExpr.location(),
                        eventName, workflowName, expectedType.signature(),
                        WorkflowPluginUtils.describeConstructorExpression(dataExpr.kind()));
            }
            return;
        }

        Optional<TypeSymbol> dataTypeOpt = semanticModel.typeOf(dataExpr);
        if (dataTypeOpt.isEmpty()) {
            return;
        }
        TypeSymbol dataType = dataTypeOpt.get();
        if (!dataType.subtypeOf(expectedType)) {
            reportDiagnostic(context, WorkflowDiagnostic.WORKFLOW_135, dataExpr.location(),
                    eventName, workflowName, expectedType.signature(), dataType.signature());
        }
    }

    /**
     * Resolves an expression to a compile-time string value. Supports string literals
     * and references to string constants; returns {@code null} otherwise.
     */
    private String resolveStringValue(ExpressionNode expression, SemanticModel semanticModel) {
        if (expression.kind() == SyntaxKind.STRING_LITERAL
                && expression instanceof BasicLiteralNode literal) {
            String text = literal.literalToken().text();
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length() - 1);
            }
            return null;
        }
        Optional<Symbol> symbolOpt = semanticModel.symbol(expression);
        if (symbolOpt.isPresent() && symbolOpt.get().kind() == SymbolKind.CONSTANT
                && symbolOpt.get() instanceof ConstantSymbol constantSymbol) {
            Optional<String> valueOpt = constantSymbol.resolvedValue();
            if (valueOpt.isPresent()) {
                String value = valueOpt.get().trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return value.substring(1, value.length() - 1);
                }
            }
        }
        return null;
    }

    private void reportDiagnostic(SyntaxNodeAnalysisContext context, WorkflowDiagnostic diagnostic,
                                  Location location, Object... args) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                diagnostic.getCode(), diagnostic.getMessage(args), diagnostic.getSeverity());
        context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, location));
    }
}
