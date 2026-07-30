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

package io.ballerina.lib.workflow.test;

import io.ballerina.lib.workflow.utils.TypesUtil;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import io.ballerina.runtime.api.values.BTypedesc;

import java.util.HashMap;
import java.util.Map;

/**
 * Native implementations for test-only external functions.
 * <p>
 * These functions back dependently-typed {@code @Activity} external functions used in unit tests. Dependently-typed
 * functions require an {@code external} body in Ballerina, so a Java implementation is necessary.
 *
 * @since 0.2.1
 */
public final class TestNatives {

    /**
     * Test-only: starts a plain {@code workflow-*} typed workflow on an ARBITRARY task queue
     * (no worker serves it, so it stays RUNNING), so instance-listing scoping can be asserted
     * against a foreign integration's workflow.
     *
     * @param workflowId   the workflow ID
     * @param taskQueue    the foreign task queue name
     * @param workflowType the workflow type (use a {@code workflow-} prefix for listings)
     * @return null on success, or a BError
     */
    public static Object startForeignQueueWorkflow(io.ballerina.runtime.api.values.BString workflowId,
            io.ballerina.runtime.api.values.BString taskQueue,
            io.ballerina.runtime.api.values.BString workflowType) {
        try {
            io.temporal.client.WorkflowClient client =
                    io.ballerina.lib.workflow.worker.WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return io.ballerina.runtime.api.creators.ErrorCreator.createError(
                        io.ballerina.runtime.api.utils.StringUtils.fromString("Workflow client not initialized"));
            }
            io.temporal.client.WorkflowOptions options = io.temporal.client.WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId.getValue())
                    .setTaskQueue(taskQueue.getValue())
                    .build();
            io.temporal.client.WorkflowStub stub =
                    client.newUntypedWorkflowStub(workflowType.getValue(), options);
            stub.start(java.util.Map.of());
            return null;
        } catch (Exception e) {
            // Reruns against a persistent dev server find the fixture already started —
            // which is exactly the state the tests need. (Checked by name: exception-table
            // types must resolve during Ballerina interop validation, where the temporal
            // classes are not on the classpath.)
            if (e.getClass().getName().contains("AlreadyStarted")) {
                return null;
            }
            return io.ballerina.runtime.api.creators.ErrorCreator.createError(
                    io.ballerina.runtime.api.utils.StringUtils.fromString(
                            "Failed to start foreign-queue workflow: " + e.getMessage()));
        }
    }

    /**
     * Test-only: starts an untyped workflow shaped like a human task on an ARBITRARY task
     * queue (no worker serves it, so it stays RUNNING). Lets one test process simulate a
     * second integration sharing the namespace, to validate task-queue scoping.
     *
     * @param workflowId the workflow ID (use a {@code humantask-} prefix for task listings)
     * @param taskQueue  the foreign task queue name
     * @param taskName   the task name recorded in the memo
     * @param userRoles  the roles recorded in the memo
     * @return null on success, or a BError
     */
    public static Object startForeignQueueHumanTask(io.ballerina.runtime.api.values.BString workflowId,
            io.ballerina.runtime.api.values.BString taskQueue,
            io.ballerina.runtime.api.values.BString taskName,
            io.ballerina.runtime.api.values.BArray userRoles) {
        try {
            io.temporal.client.WorkflowClient client =
                    io.ballerina.lib.workflow.worker.WorkflowWorkerNative.getWorkflowClient();
            if (client == null) {
                return io.ballerina.runtime.api.creators.ErrorCreator.createError(
                        io.ballerina.runtime.api.utils.StringUtils.fromString("Workflow client not initialized"));
            }
            java.util.Map<String, Object> memo = new java.util.HashMap<>();
            memo.put("workflowKind", "HUMAN_TASK");
            memo.put("taskName", taskName.getValue());
            memo.put("parentWorkflowId", "test-foreign-parent");
            memo.put("userRoles", userRoles.getStringArray());
            io.temporal.client.WorkflowOptions options = io.temporal.client.WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId.getValue())
                    .setTaskQueue(taskQueue.getValue())
                    .setMemo(memo)
                    .build();
            io.temporal.client.WorkflowStub stub =
                    client.newUntypedWorkflowStub("humantask-" + taskName.getValue(), options);
            stub.start(java.util.Map.of());
            return null;
        } catch (Exception e) {
            // A previous test run already created the fixture; it is still pending
            // (no worker serves the foreign queue), which is exactly what tests need.
            // (Checked by name — see startForeignQueueWorkflow.)
            if (e.getClass().getName().contains("AlreadyStarted")) {
                return null;
            }
            return io.ballerina.runtime.api.creators.ErrorCreator.createError(
                    io.ballerina.runtime.api.utils.StringUtils.fromString(
                            "Failed to start foreign-queue task: " + e.getMessage()));
        }
    }


    private TestNatives() {
        // Utility class, prevent instantiation
    }

    /**
     * Dependently-typed activity implementation for tests. Converts the input string to the target type specified by
     * the typedesc.
     *
     * @param data     the input string data
     * @param typedesc the target type descriptor (from dependent typing)
     * @return the data converted to the target type, or an error
     */
    public static Object convertData(BString data, BTypedesc typedesc) {
        Type targetType = typedesc.getDescribingType();
        return TypesUtil.cloneWithType(data, targetType);
    }

    /**
     * Simulates the {@code sendData} round-trip for the given value without a live workflow server.
     * <p>
     * It mirrors the runtime path that broke for non-record payloads: the value is converted to its Java
     * representation on the send side ({@link TypesUtil#convertBallerinaToJavaType}), converted back on the
     * receive side ({@link TypesUtil#convertJavaToBallerinaType}), and finally validated/coerced to the event
     * future's constraint type ({@link TypesUtil#validateAndConvert}, matching {@code WaitUtils}). This lets unit
     * tests assert that primitives, json and xml survive the round-trip (not only records), that a nil is accepted
     * only when the target type is nilable, and that mismatched payloads surface an error.
     *
     * @param data     the value being sent (any anydata, including nil)
     * @param typedesc the target type the receiving {@code future<T>} expects
     * @return the value after the full send/receive/convert round-trip, or an error
     */
    public static Object roundTripSendData(Object data, BTypedesc typedesc) {
        Object javaData = TypesUtil.convertBallerinaToJavaType(data);
        Object ballerinaData = TypesUtil.convertJavaToBallerinaType(javaData);
        return TypesUtil.validateAndConvert(ballerinaData, typedesc.getDescribingType());
    }

    /**
     * Builds the JSON Schema string for the type described by {@code typedesc}. Backs unit tests that exercise
     * {@link TypesUtil#toJsonSchema(Type)} - the schema builder used to generate workflow input schemas.
     *
     * @param typedesc the type to describe
     * @return the JSON Schema as a string
     */
    public static BString buildJsonSchema(BTypedesc typedesc) {
        return StringUtils.fromString(TypesUtil.toJsonSchema(typedesc.getDescribingType()));
    }

    /**
     * Simulates the human task completion payload path against the task's expected result type, without a live
     * workflow server.
     * <p>
     * It mirrors the runtime: the completion value is serialised on the send side
     * ({@link TypesUtil#convertBallerinaToJavaType}), deserialised on the receive side
     * ({@link TypesUtil#convertJavaToBallerinaType}), and validated/coerced against the expected type
     * ({@link TypesUtil#validateAndConvert}). This lets unit tests assert that empty (nil), basic, and complex
     * payloads succeed for compatible types and that mismatched payloads return an error instead of completing the
     * task (ballerina-library#8866).
     *
     * @param result   the completion value (any anydata, including nil)
     * @param typedesc the task's expected result type {@code T}
     * @return the value after validation/coercion, or an error when it does not match {@code T}
     */
    public static Object simulateHumanTaskCompletion(Object result, BTypedesc typedesc) {
        Object javaResult = TypesUtil.convertBallerinaToJavaType(result);
        Object ballerinaResult = TypesUtil.convertJavaToBallerinaType(javaResult);
        return TypesUtil.validateAndConvert(ballerinaResult, typedesc.getDescribingType());
    }

    /**
     * Backs the {@code generate} remote method of the mock {@code ai:ModelProvider} used in agent tests. Because
     * {@code ai:ModelProvider.generate} is dependently typed, implementations must have an external body — real
     * providers (Wso2, Anthropic, OpenAI) all bind it to Java. This mock returns a fixed structured value coerced
     * to the requested type.
     *
     * @param self     the mock model provider object (unused)
     * @param prompt   the prompt object (unused)
     * @param typedesc the expected return type
     * @return the fixed value coerced to {@code typedesc}, or an error
     */
    public static Object mockGenerate(BObject self, BObject prompt, BTypedesc typedesc) {
        Map<String, Object> fixed = new HashMap<>();
        fixed.put("summary", "generated summary");
        fixed.put("score", 7L);
        Object ballerinaValue = TypesUtil.convertJavaToBallerinaType(fixed);
        return TypesUtil.cloneWithType(ballerinaValue, typedesc.getDescribingType());
    }
}
