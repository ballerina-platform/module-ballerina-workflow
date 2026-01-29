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

package io.ballerina.stdlib.workflow.registry;

import io.ballerina.runtime.api.values.BFunctionPointer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for workflow activity functions.
 * <p>
 * This registry stores references to Ballerina functions annotated with @Activity,
 * allowing them to be invoked by the workflow runtime when an activity is executed.
 * Activities are non-deterministic operations like I/O, database calls, or external API calls.
 *
 * @since 0.1.0
 */
public final class ActivityRegistry {

    // Singleton instance
    private static final ActivityRegistry INSTANCE = new ActivityRegistry();

    // Map of activity name to activity function pointer
    private final Map<String, ActivityInfo> activities = new ConcurrentHashMap<>();

    private ActivityRegistry() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance of the ActivityRegistry.
     *
     * @return the ActivityRegistry instance
     */
    public static ActivityRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers an activity function with the given name.
     *
     * @param activityName the name of the activity
     * @param activityFunction the Ballerina function pointer for the activity
     * @return true if registration was successful, false if already registered
     */
    public boolean register(String activityName, BFunctionPointer activityFunction) {
        ActivityInfo info = new ActivityInfo(activityName, activityFunction);
        ActivityInfo existing = activities.putIfAbsent(activityName, info);
        return existing == null;
    }

    /**
     * Retrieves an activity by its name.
     *
     * @param activityName the name of the activity
     * @return an Optional containing the ActivityInfo if found
     */
    public Optional<ActivityInfo> getActivity(String activityName) {
        return Optional.ofNullable(activities.get(activityName));
    }

    /**
     * Checks if an activity is registered with the given name.
     *
     * @param activityName the name of the activity
     * @return true if the activity is registered
     */
    public boolean isRegistered(String activityName) {
        return activities.containsKey(activityName);
    }

    /**
     * Unregisters an activity by name.
     *
     * @param activityName the name of the activity to unregister
     * @return true if the activity was removed
     */
    public boolean unregister(String activityName) {
        return activities.remove(activityName) != null;
    }

    /**
     * Clears all registered activities.
     * Primarily used for testing.
     */
    public void clear() {
        activities.clear();
    }

    /**
     * Returns the number of registered activities.
     *
     * @return the count of registered activities
     */
    public int size() {
        return activities.size();
    }

    /**
     * Information about a registered activity.
     */
    public static class ActivityInfo {
        private final String name;
        private final BFunctionPointer functionPointer;

        public ActivityInfo(String name, BFunctionPointer functionPointer) {
            this.name = name;
            this.functionPointer = functionPointer;
        }

        public String getName() {
            return name;
        }

        public BFunctionPointer getFunctionPointer() {
            return functionPointer;
        }
    }
}
