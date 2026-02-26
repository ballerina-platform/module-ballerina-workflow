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
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validation task for sendData function calls.
 * <p>
 * Validates:
 * <ul>
 *   <li>sendData calls provide at least workflowId+signalName or signalName+signalData</li>
 *   <li>If workflowId is provided, signalName must also be provided</li>
 *   <li>If workflowId is not provided, process must have @CorrelationKey fields</li>
 *   <li>Ambiguous signal types require explicit signalName</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class SendEventValidatorTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof FunctionCallExpressionNode callNode)) {
            return;
        }

        // Check if this is a sendData call
        if (!isSendDataCall(callNode, context.semanticModel())) {
            return;
        }
        
        SeparatedNodeList<FunctionArgumentNode> arguments = callNode.arguments();
        
        // Parse named arguments to determine which optional params are provided
        boolean hasWorkflowId = false;
        boolean hasSignalName = false;
        boolean hasSignalData = false;
        
        for (int i = 0; i < arguments.size(); i++) {
            FunctionArgumentNode arg = arguments.get(i);
            if (arg instanceof NamedArgumentNode namedArg) {
                String argName = namedArg.argumentName().name().text();
                switch (argName) {
                    case "workflowId" -> hasWorkflowId = true;
                    case "signalName" -> hasSignalName = true;
                    case "signalData" -> hasSignalData = true;
                    default -> { /* ignore unknown */ }
                }
            }
        }
        
        // Case 1: All three optional parameters are missing → error
        if (!hasWorkflowId && !hasSignalName && !hasSignalData) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_118.getCode(),
                    WorkflowDiagnostic.WORKFLOW_118.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_118.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    callNode.functionName().location()));
            return;
        }
        
        // Case 2: If workflowId is provided, signalName must also be provided
        if (hasWorkflowId && !hasSignalName) {
            DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                    WorkflowDiagnostic.WORKFLOW_119.getCode(),
                    WorkflowDiagnostic.WORKFLOW_119.getMessage(),
                    WorkflowDiagnostic.WORKFLOW_119.getSeverity());
            context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                    callNode.functionName().location()));
            return;
        }
        
        // Case 3: If workflowId is not provided, check correlation requirements
        if (!hasWorkflowId) {
            // Must have signalName and signalData for correlation-based routing  
            // Get the process function from the first argument to check @CorrelationKey fields
            if (arguments.isEmpty()) {
                return;
            }
            
            FunctionArgumentNode firstArg = arguments.get(0);
            if (!(firstArg instanceof PositionalArgumentNode)) {
                return;
            }
            
            ExpressionNode processExpr = ((PositionalArgumentNode) firstArg).expression();
            String processName = getProcessName(processExpr, context.semanticModel());
            
            // Check if the process has @CorrelationKey fields
            boolean hasCorrelationKeys = processHasCorrelationKeys(processExpr, context.semanticModel());
            if (!hasCorrelationKeys) {
                DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                        WorkflowDiagnostic.WORKFLOW_120.getCode(),
                        WorkflowDiagnostic.WORKFLOW_120.getMessage(processName),
                        WorkflowDiagnostic.WORKFLOW_120.getSeverity());
                context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                        callNode.functionName().location()));
                return;
            }
        }
        
        // If signalName is not provided, check for ambiguous signal types
        if (!hasSignalName) {
            if (arguments.isEmpty()) {
                return;
            }
            
            FunctionArgumentNode firstArg = arguments.get(0);
            if (!(firstArg instanceof PositionalArgumentNode)) {
                return;
            }
            
            ExpressionNode processExpr = ((PositionalArgumentNode) firstArg).expression();
            
            // Get the function type to find the events parameter
            Optional<TypeSymbol> typeOpt = context.semanticModel().typeOf(processExpr);
            if (typeOpt.isEmpty() || typeOpt.get().typeKind() != TypeDescKind.FUNCTION) {
                return;
            }
            
            if (!(typeOpt.get() instanceof FunctionTypeSymbol funcType)) {
                return;
            }

            Optional<List<ParameterSymbol>> paramsOpt = funcType.params();
            if (paramsOpt.isEmpty()) {
                return;
            }
            
            TypeSymbol eventsType = findEventsRecordType(paramsOpt.get());
            if (eventsType == null) {
                return;
            }
            
            String[] ambiguousSignals = findAmbiguousSignals(eventsType);
            if (ambiguousSignals.length > 0) {
                String processName = getProcessName(processExpr, context.semanticModel());
                DiagnosticInfo diagnosticInfo = new DiagnosticInfo(
                        WorkflowDiagnostic.WORKFLOW_112.getCode(),
                        WorkflowDiagnostic.WORKFLOW_112.getMessage(
                                processName, ambiguousSignals[0], ambiguousSignals[1]),
                        WorkflowDiagnostic.WORKFLOW_112.getSeverity());
                context.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo,
                        callNode.functionName().location()));
            }
        }
    }
    
    /**
     * Checks if the function call is a call to workflow:sendData.
     */
    private boolean isSendDataCall(FunctionCallExpressionNode callNode, SemanticModel semanticModel) {
        NameReferenceNode funcName = callNode.functionName();
        
        // Check for qualified name (workflow:sendData)
        if (funcName instanceof QualifiedNameReferenceNode qualifiedName) {
            String moduleName = qualifiedName.modulePrefix().text();
            String functionName = qualifiedName.identifier().text();
            
            if (WorkflowConstants.PACKAGE_NAME.equals(moduleName) && 
                    WorkflowConstants.SEND_SIGNAL_FUNCTION.equals(functionName)) {
                return true;
            }
        }
        
        // Check for simple name (sendData) - need to verify it's from workflow module
        if (funcName instanceof SimpleNameReferenceNode simpleName) {
            if (!WorkflowConstants.SEND_SIGNAL_FUNCTION.equals(simpleName.name().text())) {
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
     * Checks if a process function has @CorrelationKey fields in its input type.
     */
    private boolean processHasCorrelationKeys(ExpressionNode processExpr, SemanticModel semanticModel) {
        Optional<TypeSymbol> typeOpt = semanticModel.typeOf(processExpr);
        if (typeOpt.isEmpty() || typeOpt.get().typeKind() != TypeDescKind.FUNCTION) {
            return false;
        }
        
        if (!(typeOpt.get() instanceof FunctionTypeSymbol funcType)) {
            return false;
        }

        Optional<List<ParameterSymbol>> paramsOpt = funcType.params();
        if (paramsOpt.isEmpty()) {
            return false;
        }
        
        // Find the input parameter (skip Context and events)
        for (ParameterSymbol param : paramsOpt.get()) {
            TypeSymbol paramType = param.typeDescriptor();
            TypeSymbol actualType = WorkflowPluginUtils.resolveTypeReference(paramType);
            
            if (actualType.typeKind() == TypeDescKind.RECORD && actualType instanceof RecordTypeSymbol recordType) {
                // Skip events records (those with future fields)
                if (containsFutureFields(recordType)) {
                    continue;
                }
                // Check if this record has @CorrelationKey annotated fields
                for (RecordFieldSymbol field : recordType.fieldDescriptors().values()) {
                    if (hasCorrelationKeyAnnotation(field)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a record field has the @workflow:CorrelationKey annotation.
     */
    private boolean hasCorrelationKeyAnnotation(RecordFieldSymbol field) {
        for (AnnotationSymbol annotation : field.annotations()) {
            Optional<String> nameOpt = annotation.getName();
            if (nameOpt.isPresent()
                    && WorkflowConstants.CORRELATION_KEY_ANNOTATION.equals(nameOpt.get())) {
                Optional<ModuleSymbol> moduleOpt = annotation.getModule();
                if (moduleOpt.isPresent() && WorkflowPluginUtils.isWorkflowModule(moduleOpt.get())) {
                    return true;
                }
            }
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
                if (actualType instanceof RecordTypeSymbol recordType) {
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
        
        if (!(actualType instanceof RecordTypeSymbol recordType)) {
            return EMPTY_STRING_ARRAY;
        }

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
        
        if (actualType instanceof FutureTypeSymbol futureTypeSymbol) {
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
        
        if (actualType.typeKind() == TypeDescKind.RECORD && actualType instanceof RecordTypeSymbol recordType) {
            Map<String, RecordFieldSymbol> fields = recordType.fieldDescriptors();
            
            StringBuilder signature = new StringBuilder("record{");
            fields.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> signature.append(entry.getKey())
                                           .append(":")
                                           .append(getStructuralSignature(entry.getValue().typeDescriptor()))
                                           .append(";"));
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
