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

import java.util.List;

/**
 * Information extracted from a module-level {@code final workflow:DurableAgent x = new ({...})}
 * declaration, used by {@link WorkflowSourceModifier} to generate the module-init registration.
 * Expression-valued config entries are carried as their source text so the generated code
 * re-references the same symbols (model variable, activity/tool functions, types).
 *
 * @param agentName          the module-level variable name — the agent's stable identity
 * @param workflowPrefix     the import prefix of the workflow module in the declaring document
 * @param modelSource        source text of the {@code model} config expression
 * @param systemPromptSource source text of the {@code systemPrompt} config expression
 * @param maxIterSource      source text of the {@code maxIter} config expression, or null for default
 * @param activities         declared activity capabilities
 * @param aiToolRefs         source refs of {@code @ai:AgentTool} function tools
 * @param events             declared event channels
 * @param humanTasks         declared human tasks
 *
 * @since 0.9.0
 */
public record DurableAgentDeclInfo(String agentName,
                                   String workflowPrefix,
                                   String modelSource,
                                   String systemPromptSource,
                                   String maxIterSource,
                                   List<ActivityDecl> activities,
                                   List<String> aiToolRefs,
                                   List<EventDecl> events,
                                   List<HumanTaskDecl> humanTasks) {

    public DurableAgentDeclInfo {
        activities = List.copyOf(activities);
        aiToolRefs = List.copyOf(aiToolRefs);
        events = List.copyOf(events);
        humanTasks = List.copyOf(humanTasks);
    }

    /**
     * A declared activity capability.
     *
     * @param toolName          tool name advertised to the model (explicit {@code name} or the
     *                          function's simple name)
     * @param functionRefSource source text of the activity function reference
     * @param metaSource        source text of a json metadata mapping (description, gating,
     *                          retry policy), or null when there is none
     */
    public record ActivityDecl(String toolName, String functionRefSource, String metaSource) { }

    /**
     * A declared event channel.
     *
     * @param name              the channel name
     * @param requestTypeSource source text of the request typedesc expression
     * @param responseTypeSource source text of the response typedesc expression, or null
     * @param cardinality       "SINGLE_EVENT" or "MULTI_EVENT"
     */
    public record EventDecl(String name, String requestTypeSource, String responseTypeSource,
                            String cardinality) { }

    /**
     * A declared human task.
     *
     * @param name       the task name
     * @param metaSource source text of a json metadata mapping (roles, title, description),
     *                   or null when there is none
     */
    public record HumanTaskDecl(String name, String metaSource) { }
}
