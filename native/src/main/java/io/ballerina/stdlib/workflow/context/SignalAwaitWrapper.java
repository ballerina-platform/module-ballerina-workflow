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

package io.ballerina.stdlib.workflow.context;

import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper for managing signal futures in Temporal workflows.
 * 
 * <p>Purpose:
 * <ul>
 *   <li>Listen for signals in workflows coming from Temporal</li>
 *   <li>Create future values that can be passed to workflow functions as arguments</li>
 *   <li>During replay, create temporary futures for completed signals and mark them as completed
 *       with the signal data</li>
 * </ul>
 * 
 * <p>All signal data is expected to be a map with a mandatory "id" field (String) for correlation.
 * The "id" field is used internally by the workflow engine to identify signals and workflow instances.
 * 
 * <p>Other semantics (like awaiting signals) are handled from the Ballerina side using
 * Ballerina's native {@code wait} action on futures.
 *
 * @since 0.1.0
 */
public final class SignalAwaitWrapper {

    private static final Logger LOGGER = Workflow.getLogger(SignalAwaitWrapper.class);

    /** The key for the mandatory id field in signal/workflow data. */
    public static final String ID_FIELD = "id";

    // Map of signal name to its promise (future)
    // Each signal gets a CompletablePromise that will be completed when the signal arrives
    private final Map<String, CompletablePromise<SignalData>> signalPromises = new ConcurrentHashMap<>();

    // Map of signal name to its data (for completed signals, used during replay)
    private final Map<String, SignalData> completedSignals = new ConcurrentHashMap<>();

    /**
     * Default constructor for per-workflow instance.
     */
    public SignalAwaitWrapper() {
        // Default constructor
    }

    /**
     * Creates or gets a future for a signal.
     * If the signal has already been received (during replay), returns a completed future.
     * Otherwise, creates a new promise that will be completed when the signal arrives.
     *
     * @param signalName the name of the signal
     * @return a CompletablePromise that will contain the signal data
     */
    public CompletablePromise<SignalData> getSignalFuture(String signalName) {
        // Check if signal was already completed (during replay)
        if (completedSignals.containsKey(signalName)) {
            LOGGER.info("[SignalAwaitWrapper] Signal '{}' already completed (replay), returning completed future",
                    signalName);
            CompletablePromise<SignalData> completedPromise = Workflow.newPromise();
            completedPromise.complete(completedSignals.get(signalName));
            return completedPromise;
        }

        // Get or create a promise for this signal
        return signalPromises.computeIfAbsent(signalName, k -> {
            LOGGER.info("[SignalAwaitWrapper] Creating new promise for signal '{}'", signalName);
            return Workflow.newPromise();
        });
    }

    /**
     * Records a signal that has been received.
     * This completes the promise for the signal if one exists, or stores the data for replay.
     * The signal data is expected to contain an "id" field for correlation.
     *
     * @param signalName the name of the signal
     * @param data the signal data (expected to be a Map with "id" field)
     */
    public void recordSignal(String signalName, Object data) {
        // Extract the id from the data if it's a Map
        String id = extractId(data);
        SignalData signalData = new SignalData(signalName, id, data);
        
        // Store in completed signals (for replay scenarios)
        completedSignals.put(signalName, signalData);
        LOGGER.info("[SignalAwaitWrapper] Signal '{}' (id={}) recorded in completed signals", signalName, id);

        // Complete the promise if one exists
        CompletablePromise<SignalData> promise = signalPromises.get(signalName);
        if (promise != null && !promise.isCompleted()) {
            promise.complete(signalData);
            LOGGER.info("[SignalAwaitWrapper] Promise for signal '{}' completed", signalName);
        }
    }

    /**
     * Extracts the "id" field from the data object.
     *
     * @param data the data object (expected to be a Map)
     * @return the id value, or null if not found
     */
    @SuppressWarnings("unchecked")
    private String extractId(Object data) {
        if (data instanceof Map) {
            Object idValue = ((Map<String, Object>) data).get(ID_FIELD);
            if (idValue != null) {
                return idValue.toString();
            }
        }
        return null;
    }

    /**
     * Checks if a signal has been received.
     *
     * @param signalName the name of the signal
     * @return true if the signal has been received
     */
    public boolean isSignalReceived(String signalName) {
        return completedSignals.containsKey(signalName);
    }

    /**
     * Gets the data for a completed signal.
     *
     * @param signalName the name of the signal
     * @return the signal data, or null if not received
     */
    public SignalData getCompletedSignalData(String signalName) {
        return completedSignals.get(signalName);
    }

    /**
     * Clears all signal state. Called when workflow completes.
     */
    public void clear() {
        signalPromises.clear();
        completedSignals.clear();
        LOGGER.info("[SignalAwaitWrapper] All signal state cleared");
    }

    /**
     * Container for signal data that wraps the signal name, id, and data together.
     * The "id" field is extracted from the data for easy correlation access.
     */
    public static final class SignalData {
        private final String signalName;
        private final String id;
        private final Object data;

        /**
         * Creates a new SignalData.
         *
         * @param signalName the signal name
         * @param id the correlation id (from "id" field in data)
         * @param data the full signal data
         */
        public SignalData(String signalName, String id, Object data) {
            this.signalName = signalName;
            this.id = id;
            this.data = data;
        }

        /**
         * Gets the signal name.
         *
         * @return the signal name
         */
        public String getSignalName() {
            return signalName;
        }

        /**
         * Gets the correlation id.
         *
         * @return the id from the "id" field in data, or null if not present
         */
        public String getId() {
            return id;
        }

        /**
         * Gets the signal data.
         *
         * @return the full signal data
         */
        public Object getData() {
            return data;
        }

        @Override
        public String toString() {
            return "SignalData{signalName='" + signalName + "', id='" + id + "', data=" + data + "}";
        }
    }
}
