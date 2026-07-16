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
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.FunctionArgumentNode;
import io.ballerina.compiler.syntax.tree.FunctionCallExpressionNode;
import io.ballerina.compiler.syntax.tree.NameReferenceNode;
import io.ballerina.compiler.syntax.tree.NamedArgumentNode;
import io.ballerina.compiler.syntax.tree.PositionalArgumentNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SeparatedNodeList;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;

import java.util.Optional;

/**
 * Shared helpers for validating calls to {@code ballerina/workflow} module functions
 * ({@code run}, {@code sendData}, ...).
 *
 * @since 0.6.0
 */
public final class WorkflowFunctionCallUtils {

    private WorkflowFunctionCallUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks whether the call invokes the given {@code ballerina/workflow} module function,
     * matching both qualified ({@code workflow:run}) and simple ({@code run}) references.
     *
     * @param callNode      the function call node
     * @param semanticModel the semantic model
     * @param functionName  the workflow module function name to match
     * @return true when the call resolves to the workflow module function
     */
    public static boolean isWorkflowModuleFunctionCall(FunctionCallExpressionNode callNode,
                                                       SemanticModel semanticModel,
                                                       String functionName) {
        NameReferenceNode funcName = callNode.functionName();

        String simpleName = null;
        if (funcName instanceof QualifiedNameReferenceNode qualifiedName) {
            simpleName = qualifiedName.identifier().text();
        } else if (funcName instanceof SimpleNameReferenceNode simpleRef) {
            simpleName = simpleRef.name().text();
        }
        if (!functionName.equals(simpleName)) {
            return false;
        }

        // Verify via the semantic model that the call resolves to the
        // ballerina/workflow module (aliased imports included).
        Optional<Symbol> symbolOpt = semanticModel.symbol(callNode);
        if (symbolOpt.isEmpty() || symbolOpt.get().kind() != SymbolKind.FUNCTION) {
            return false;
        }
        FunctionSymbol funcSymbol = (FunctionSymbol) symbolOpt.get();
        Optional<ModuleSymbol> moduleOpt = funcSymbol.getModule();
        return moduleOpt.isPresent() && WorkflowPluginUtils.isWorkflowModule(moduleOpt.get());
    }

    /**
     * Resolves the expression bound to a parameter, honoring both positional and
     * named-argument call styles.
     *
     * @param arguments       the call's arguments
     * @param position        the parameter's positional index
     * @param parameterName   the parameter's name (for named arguments)
     * @return the bound expression, or {@code null} when the argument was not provided
     */
    public static ExpressionNode getArgumentExpression(SeparatedNodeList<FunctionArgumentNode> arguments,
                                                       int position, String parameterName) {
        boolean namedSeen = false;
        for (int i = 0; i < arguments.size(); i++) {
            FunctionArgumentNode arg = arguments.get(i);
            if (arg instanceof NamedArgumentNode namedArg) {
                namedSeen = true;
                if (parameterName.equals(namedArg.argumentName().name().text())) {
                    return namedArg.expression();
                }
            } else if (arg instanceof PositionalArgumentNode posArg && !namedSeen && i == position) {
                return posArg.expression();
            }
        }
        return null;
    }
}
