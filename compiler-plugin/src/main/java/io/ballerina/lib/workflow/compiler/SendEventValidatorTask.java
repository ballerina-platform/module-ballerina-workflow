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
import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.SymbolKind;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;

import java.util.Optional;

/**
 * Validation task for sendData function calls.
 * <p>
 * With the simplified sendData API where all parameters are required
 * (workflow function, workflowId, dataName, data), most compile-time
 * validations are handled by the Ballerina type system.
 * This task is retained for potential future validations.
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

        // All sendData parameters are now required. The Ballerina type system
        // enforces that all arguments are provided with correct types.
        // No additional compile-time validation is needed.
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
                    WorkflowConstants.SEND_DATA_FUNCTION.equals(functionName)) {
                return true;
            }
        }
        
        // Check for simple name (sendData) - need to verify it's from workflow module
        if (funcName instanceof SimpleNameReferenceNode simpleName) {
            if (!WorkflowConstants.SEND_DATA_FUNCTION.equals(simpleName.name().text())) {
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
}
