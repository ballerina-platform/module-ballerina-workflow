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

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Holds information about a @Workflow annotated function.
 * <p>
 * Contains the function name, a map of activity functions called within it,
 * and the set of human task names found in {@code awaitHumanTask} call sites.
 *
 * @param functionName  the name of the process function
 * @param activityMap   map of activity function names to their references
 * @param humanTaskNames set of task names passed to {@code ctx->awaitHumanTask}
 * @param humanTaskResultTypes map of task name to the source text of the result type declared at
 *                             its {@code awaitHumanTask} call site; a task is absent when its type
 *                             could not be determined statically, and mapped to {@code ""} when
 *                             different call sites of the same task declared different types
 * @since 0.1.0
 */
public record ProcessFunctionInfo(String functionName, Map<String, String> activityMap,
                                  Set<String> humanTaskNames, Map<String, String> humanTaskResultTypes) {

    /**
     * Creates a new ProcessFunctionInfo with defensive copies of the supplied collections
     * to prevent external mutation from affecting the stored state.
     *
     * @param functionName  the name of the process function
     * @param activityMap   map of activity function names to their references
     * @param humanTaskNames set of task names found at awaitHumanTask call sites
     * @param humanTaskResultTypes map of task name to statically determined result type source
     */
    public ProcessFunctionInfo {
        activityMap = Map.copyOf(activityMap);
        humanTaskNames = Set.copyOf(humanTaskNames);
        humanTaskResultTypes = Map.copyOf(humanTaskResultTypes);
    }

    /**
     * Gets the name of the process function.
     *
     * @return the function name
     */
    @Override
    public String functionName() {
        return functionName;
    }

    /**
     * Gets the map of activity functions called within this process.
     * <p>
     * The key is the activity function name, and the value is the function reference.
     *
     * @return unmodifiable map of activity function names to references
     */
    @Override
    public Map<String, String> activityMap() {
        return Collections.unmodifiableMap(activityMap);
    }

    /**
     * Gets the set of human task names found in {@code ctx->awaitHumanTask} call sites
     * within this workflow function.
     *
     * @return unmodifiable set of task name strings
     */
    @Override
    public Set<String> humanTaskNames() {
        return Collections.unmodifiableSet(humanTaskNames);
    }

    /**
     * Gets the map of human task names to the source text of the result type declared at their
     * {@code awaitHumanTask} call sites. Only tasks whose result type could be captured
     * statically appear; a value of {@code ""} marks conflicting declarations.
     *
     * @return unmodifiable map of task name to result type source text
     */
    @Override
    public Map<String, String> humanTaskResultTypes() {
        return Collections.unmodifiableMap(humanTaskResultTypes);
    }
}
