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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds information about a {@code @workflow:DurableAgent} function, collected during the code-modifier analysis phase
 * and consumed by {@link WorkflowSourceModifier} to emit the agent's registrations at module init on every worker: its
 * workflow (with activity tools plus the built-in {@code llmChat}/{@code generate}/{@code executeAgentTool}
 * activities), its AI tool function pointers, and its human task types.
 *
 * @param functionName     the name of the agent function
 * @param workflowPrefix   the import prefix through which {@code ballerina/workflow} is referenced (from the
 *                         {@code @<prefix>:DurableAgent} annotation), used to qualify the built-in activities
 * @param activityToolRefs map of tool simple name to source reference, from {@code ctx.registerActivity(...)}
 *                         call sites
 * @param aiToolRefs       source references of function tools from {@code ctx.runDurableAgent(..., tools = [...])}
 * @param humanTaskNames   task-name literals from {@code ctx.registerHumanTask("name", ...)} call sites
 * @since 0.7.0
 */
public record AgentFunctionInfo(String functionName, String workflowPrefix, Map<String, String> activityToolRefs,
                                List<String> aiToolRefs, Set<String> humanTaskNames) {

    public AgentFunctionInfo {
        activityToolRefs = new LinkedHashMap<>(activityToolRefs);
        aiToolRefs = List.copyOf(aiToolRefs);
        humanTaskNames = new LinkedHashSet<>(humanTaskNames);
    }

    @Override
    public Map<String, String> activityToolRefs() {
        return java.util.Collections.unmodifiableMap(activityToolRefs);
    }

    @Override
    public Set<String> humanTaskNames() {
        return java.util.Collections.unmodifiableSet(humanTaskNames);
    }
}
