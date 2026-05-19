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
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.syntax.tree.BindingPatternNode;
import io.ballerina.compiler.syntax.tree.CaptureBindingPatternNode;
import io.ballerina.compiler.syntax.tree.ModuleVariableDeclarationNode;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.compiler.syntax.tree.TypedBindingPatternNode;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Analysis task that detects module-level {@code final} (or {@code configurable})
 * variables whose declared type is a {@code client object}. Their identifiers are
 * collected into the package-level user data under {@link
 * WorkflowConstants#CONNECTION_VAR_NAMES}, keyed by module id plus source-set,
 * so that {@link WorkflowSourceModifier} can emit corresponding
 * {@code wfInternal:registerConnection(...)} calls only where the variables are
 * actually visible.
 *
 * @since 0.2.0
 */
public class ConnectionVariableAnalysisTask implements AnalysisTask<SyntaxNodeAnalysisContext> {

    private final Map<String, Object> userData;

    public ConnectionVariableAnalysisTask(Map<String, Object> userData) {
        this.userData = userData;
    }

    @Override
    public void perform(SyntaxNodeAnalysisContext context) {
        if (!(context.node() instanceof ModuleVariableDeclarationNode varDecl)) {
            return;
        }
        SemanticModel semanticModel = context.semanticModel();
        Optional<Symbol> symbolOpt = semanticModel.symbol(varDecl);
        if (symbolOpt.isEmpty()) {
            return;
        }
        if (!WorkflowPluginUtils.isModuleLevelFinalClient(symbolOpt.get())) {
            return;
        }
        String name = extractVariableName(varDecl);
        if (name == null || name.isEmpty() || "_".equals(name)) {
            return;
        }
        boolean isTestDocument = !context.currentPackage()
            .module(context.documentId().moduleId())
            .documentIds()
            .contains(context.documentId());
        addConnectionName(scopeKey(context.documentId().moduleId().toString(), isTestDocument), name);
    }

    private String extractVariableName(ModuleVariableDeclarationNode varDecl) {
        TypedBindingPatternNode tbp = varDecl.typedBindingPattern();
        BindingPatternNode bp = tbp.bindingPattern();
        if (bp instanceof CaptureBindingPatternNode capture) {
            Token name = capture.variableName();
            return name.text();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addConnectionName(String scopeKey, String name) {
        Map<String, Set<String>> namesByModule =
                (Map<String, Set<String>>) userData.computeIfAbsent(
                        WorkflowConstants.CONNECTION_VAR_NAMES,
                        k -> new LinkedHashMap<String, Set<String>>());
        Set<String> names = namesByModule.computeIfAbsent(scopeKey, k -> new LinkedHashSet<>());
        names.add(name);
    }

    private String scopeKey(String moduleKey, boolean isTestDocument) {
        return moduleKey + (isTestDocument ? "#test" : "#source");
    }
}
