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
 * Registry for workflow process functions.
 * <p>
 * This registry stores references to Ballerina functions annotated with @Process,
 * allowing them to be invoked by the workflow runtime when a process is started
 * or when a workflow needs to be replayed.
 *
 * @since 0.1.0
 */
public final class ProcessRegistry {

    // Singleton instance
    private static final ProcessRegistry INSTANCE = new ProcessRegistry();

    // Map of process name to process function pointer
    private final Map<String, ProcessInfo> processes = new ConcurrentHashMap<>();

    private ProcessRegistry() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance of the ProcessRegistry.
     *
     * @return the ProcessRegistry instance
     */
    public static ProcessRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a process function with the given name.
     *
     * @param processName the name of the process
     * @param processFunction the Ballerina function pointer for the process
     * @return true if registration was successful, false if already registered
     */
    public boolean register(String processName, BFunctionPointer processFunction) {
        ProcessInfo info = new ProcessInfo(processName, processFunction);
        ProcessInfo existing = processes.putIfAbsent(processName, info);
        return existing == null;
    }

    /**
     * Retrieves a process by its name.
     *
     * @param processName the name of the process
     * @return an Optional containing the ProcessInfo if found
     */
    public Optional<ProcessInfo> getProcess(String processName) {
        return Optional.ofNullable(processes.get(processName));
    }

    /**
     * Checks if a process is registered with the given name.
     *
     * @param processName the name of the process
     * @return true if the process is registered
     */
    public boolean isRegistered(String processName) {
        return processes.containsKey(processName);
    }

    /**
     * Unregisters a process by name.
     *
     * @param processName the name of the process to unregister
     * @return true if the process was removed
     */
    public boolean unregister(String processName) {
        return processes.remove(processName) != null;
    }

    /**
     * Clears all registered processes.
     * Primarily used for testing.
     */
    public void clear() {
        processes.clear();
    }

    /**
     * Returns the number of registered processes.
     *
     * @return the count of registered processes
     */
    public int size() {
        return processes.size();
    }

    /**
     * Information about a registered process.
     */
    public static class ProcessInfo {
        private final String name;
        private final BFunctionPointer functionPointer;

        public ProcessInfo(String name, BFunctionPointer functionPointer) {
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
