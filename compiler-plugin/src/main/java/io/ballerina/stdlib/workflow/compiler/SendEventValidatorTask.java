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
import io.ballerina.compiler.api.symbols.FutureTypeSymbol;
import io.ballerina.compiler.api.symbols.ModuleSymbol;
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
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validation task for sendEvent function calls.
 * <p>
 * Validates that sendEvent calls provide an explicit signalName when the process
 * has structurally equivalent signal types in its events record.
 *
 * @since 0.1.0
 */
public class SendEventValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionCallExpressionNode)) {
            return;
        }

        FunctionCallExpressionNode callNode = (FunctionCallExpressionNode) context.node();
        
        // Check if this is a sendEvent call
        if (!isSendEventCall(callNode, context.semanticModel())) {
            return;
        }
        
        // Check if signalName is provided (3rd argument)
        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        boolean hasSignalName = arguments.size() >= 3;
        
        if (hasSignalName) {
            // signalName is provided, no need to check for ambiguity
            return;
        }
        
        // Get the process function from the first argument
        if (arguments.isEmpty()) {
            return;
        }
        
        FunctionArgumentNode firstArg = arguments.get(0);
        if (!(firstArg instanceof PositionalArgumentNode)) {
            return;
        }
        
        ExpressionNode processExpr = ((PositionalArgumentNode) firstArg).expression();
        
        // Get the type of the process function to check its events record
        Optional<TypeSymbol> typeOpt = context.semanticModel().typeOf(processExpr);
        if (typeOpt.isEmpty()) {
            return;
        }
        
        TypeSymbol processType = typeOpt.get();
        if (processType.typeKind() != TypeDescKind.FUNCTION) {
            return;
        }
        
        // Get the function type to find the events parameter
        if (!(processType instanceof FunctionTypeSymbol)) {
            return;
        }
        
        FunctionTypeSymbol funcType = (FunctionTypeSymbol) processType;
        Optional<List<ParameterSymbol>> paramsOpt = funcType.params();
        if (paramsOpt.isEmpty()) {
            return;
        }
        
        // Find the events record parameter
        TypeSymbol eventsType = findEventsRecordType(paramsOpt.get());
        if (eventsType == null) {
            return;
        }
        
        // Check for structurally equivalent signal types
        String[] ambiguousSignals = findAmbiguousSignals(eventsType);
        if (ambiguousSignals.length > 0) {
            // Get the process name for the error message
            String processName = getProcessName(processExpr, context.semanticModel());
            
            // Report error: sendEvent called without signalName but events are ambiguous
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowConstants.WORKFLOW_112,
                    String.format(WorkflowConstants.SEND_EVENT_AMBIGUOUS_SIGNAL, 
                            processName, ambiguousSignals[0], ambiguousSignals[1]),
                    DiagnosticSeverity.ERROR);
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    callNode.functionName().location()));
        }
    }
    
    /**
     * Checks if the function call is a call to workflow:sendEvent.
     */
    private boolean isSendEventCall(FunctionCallExpressionNode callNode, SemanticModel semanticModel) {
        NameReferenceNode funcName = callNode.functionName();
        
        // Check for qualified name (workflow:sendEvent)
        if (funcName instanceof QualifiedNameReferenceNode) {
            QualifiedNameReferenceNode qualifiedName = (QualifiedNameReferenceNode) funcName;
            String moduleName = qualifiedName.modulePrefix().text();
            String functionName = qualifiedName.identifier().text();
            
            if (WorkflowConstants.PACKAGE_NAME.equals(moduleName) && 
                    WorkflowConstants.SEND_EVENT_FUNCTION.equals(functionName)) {
                return true;
            }
        }
        
        // Check for simple name (sendEvent) - need to verify it's from workflow module
        if (funcName instanceof SimpleNameReferenceNode) {
            SimpleNameReferenceNode simpleName = (SimpleNameReferenceNode) funcName;
            if (!WorkflowConstants.SEND_EVENT_FUNCTION.equals(simpleName.name().text())) {
                return false;
            }
            
            // Verify it's from workflow module using semantic model
            Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
            if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
                return false;
            }
            
            FunctionSymbol funcSymbol = (FunctionSymbol) symbolOpt.get();
            Optional<ModuleSymbol> moduleOpt = funcSymbol.getModule();
            if (moduleOpt.isEmpty()) {
                return false;
            }
            
            ModuleSymbol module = moduleOpt.get();
            Optional<String> moduleNameOpt = module.getName();
            return moduleNameOpt.isPresent() && WorkflowConstants.PACKAGE_NAME.equals(moduleNameOpt.get());
        }
        
        return false;
    }
    
    /**
     * Finds the events record type from the process function parameters.
     */
    private TypeSymbol findEventsRecordType(List<ParameterSymbol> params) {
        for (ParameterSymbol param : params) {
            TypeSymbol paramType = param.typeDescriptor();
            TypeSymbol actualType = WorkflowPluginUtils.resolveTypeReference(paramType);
            
            if (actualType.typeKind() == TypeDescKind.RECORD) {
                // Check if this record contains future fields (events record signature)
                if (actualType instanceof RecordTypeSymbol) {
                    RecordTypeSymbol recordType = (RecordTypeSymbol) actualType;
                    if (containsFutureFields(recordType)) {
                        return paramType;
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * Checks if a record type contains at least one future field.
     */
    private boolean containsFutureFields(RecordTypeSymbol recordType) {
        Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        
        for (RecordFieldSymbol field : fields.values()) {
            TypeSymbol fieldType = WorkflowPluginUtils.resolveTypeReference(field.typeDescriptor());
            if (fieldType.typeKind() == TypeDescKind.FUTURE) {
                return true;
            }
        }
        return false;
    }
    
    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    
    /**
     * Finds structurally equivalent signals in the events record.
     * Returns the names of two ambiguous signals, or empty array if no ambiguity.
     */
    private String[] findAmbiguousSignals(TypeSymbol eventsType) {
        TypeSymbol actualType = WorkflowPluginUtils.resolveTypeReference(eventsType);
        
        if (actualType.typeKind() != TypeDescKind.RECORD) {
            return EMPTY_STRING_ARRAY;
        }
        
        if (!(actualType instanceof RecordTypeSymbol)) {
            return EMPTY_STRING_ARRAY;
        }
        
        RecordTypeSymbol recordType = (RecordTypeSymbol) actualType;
        Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
        
        if (fields.size() < 2) {
            return EMPTY_STRING_ARRAY;
        }
        
        // Map type signatures to field names
        Map<String, List<String>> typeSignatureToFields = new HashMap<>();
        
        for (Map.Entry<String, RecordFieldSymbol> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            RecordFieldSymbol field = entry.getValue();
            TypeSymbol fieldType = field.typeDescriptor();
            
            String typeSignature = getSignalTypeSignature(fieldType);
            typeSignatureToFields.computeIfAbsent(typeSignature, k -> new ArrayList<>()).add(fieldName);
        }
        
        // Find any duplicate type signatures
        for (List<String> fieldNames : typeSignatureToFields.values()) {
            if (fieldNames.size() > 1) {
                return new String[]{fieldNames.get(0), fieldNames.get(1)};
            }
        }
        
        return EMPTY_STRING_ARRAY;
    }
    
    /**
     * Gets a string signature representing the constraint type of a future field.
     */
    private String getSignalTypeSignature(TypeSymbol futureType) {
        TypeSymbol actualType = WorkflowPluginUtils.resolveTypeReference(futureType);
        
        if (actualType.typeKind() != TypeDescKind.FUTURE) {
            return actualType.signature();
        }
        
        if (actualType instanceof FutureTypeSymbol) {
            FutureTypeSymbol futureTypeSymbol = (FutureTypeSymbol) actualType;
            Optional<TypeSymbol> constraintOpt = futureTypeSymbol.typeParameter();
            if (constraintOpt.isPresent()) {
                return getStructuralSignature(constraintOpt.get());
            }
        }
        
        return actualType.signature();
    }
    
    /**
     * Gets a structural signature for a type.
     */
    private String getStructuralSignature(TypeSymbol typeSymbol) {
        TypeSymbol actualType = WorkflowPluginUtils.resolveTypeReference(typeSymbol);
        
        if (actualType.typeKind() == TypeDescKind.RECORD && actualType instanceof RecordTypeSymbol) {
            RecordTypeSymbol recordType = (RecordTypeSymbol) actualType;
            Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
            
            StringBuilder signature = new StringBuilder("record{");
            fields.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        signature.append(entry.getKey())
                                .append(":")
                                .append(getStructuralSignature(entry.getValue().typeDescriptor()))
                                .append(";");
                    });
            signature.append("}");
            return signature.toString();
        }
        
        return actualType.signature();
    }
    
    /**
     * Gets the process name from the expression.
     */
    private String getProcessName(ExpressionNode expr, SemanticModel semanticModel) {
        Optional<Symbol> symbolOpt = semanticModel.symbol(expr);
        if (symbolOpt.isPresent()) {
            Optional<String> nameOpt = symbolOpt.get().getName();
            if (nameOpt.isPresent()) {
                return nameOpt.get();
            }
        }
        return "unknown";
    }
}
