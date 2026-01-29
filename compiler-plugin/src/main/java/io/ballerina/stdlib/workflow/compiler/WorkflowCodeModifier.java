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

import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.plugins.CodeModifier;
import io.ballerina.projects.plugins.CodeModifierContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Code modifier that transforms workflow process functions.
 * <p>
 * This modifier:
 * 1. Detects functions annotated with @workflow:Process
 * 2. Analyzes process function bodies to find @workflow:Activity calls
 * 3. Replaces activity calls with callActivity(functionPointer, args...)
 * 4. Adds registerProcess call at module level
 *
 * @since 0.1.0
 */
public class WorkflowCodeModifier extends CodeModifier {

    private final Map<String, Object> userData;
    private final Map<DocumentId, WorkflowModifierContext> modifierContextMap;

    public WorkflowCodeModifier(Map<String, Object> userData) {
        this.userData = userData;
        this.modifierContextMap = new HashMap<>();
        userData.put(WorkflowConstants.MODIFIER_CONTEXT_MAP, this.modifierContextMap);
    }

    @Override
    public void init(CodeModifierContext modifierContext) {
        // Register the analysis task that collects process and activity information
        modifierContext.addSyntaxNodeAnalysisTask(
                new ProcessFunctionAnalysisTask(this.userData),
                SyntaxKind.FUNCTION_DEFINITION
        );

        // Register the source modifier task that performs the actual transformations
        modifierContext.addSourceModifierTask(new WorkflowSourceModifier(this.modifierContextMap));

        // Mark analysis as completed
        this.userData.put(WorkflowConstants.IS_ANALYSIS_COMPLETED, true);
    }
}
