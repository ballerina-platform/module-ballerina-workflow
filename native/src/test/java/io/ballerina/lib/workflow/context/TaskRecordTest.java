/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.context;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Both task kinds project to the same memo and inputs vocabulary.
 */
public class TaskRecordTest {

    private static TaskRecord.Builder base(String kind) {
        return TaskRecord.builder(kind)
                .taskId("child-1").taskName("order.approve").parentWorkflowId("wf-1").parentWorkflowType("order")
                .title("Approve").description("Look").userRoles(List.of("manager"))
                .taskInput(Map.of("amount", 12)).createdAt("2026-09-16T00:00:00Z");
    }

    @Test
    public void humanTaskMemoCarriesTheSharedVocabulary() {
        Map<String, Object> memo = base(TaskRecord.HUMAN_TASK).stepId("s1").timeoutMillis(5000L).build().toMemo();
        Assert.assertEquals(memo.get("workflowKind"), "HUMAN_TASK");
        Assert.assertEquals(memo.get("taskInput"), Map.of("amount", 12));
        Assert.assertEquals(memo.get("userRoles"), List.of("manager"));
        Assert.assertEquals(memo.get("parentWorkflowType"), "order");
        Assert.assertEquals(memo.get(WorkflowContextNative.STEP_ID_KEY), "s1");
        Assert.assertEquals(memo.get("timeoutMillis"), 5000L);
        Assert.assertFalse(memo.containsKey(WorkflowContextNative.REVIEW_STEP_ID_KEY));
        Assert.assertFalse(memo.containsKey("trigger"));
        Assert.assertFalse(memo.containsKey("activityArgs"));
    }

    @Test
    public void emptyAssignmentListsStayOffTheMemo() {
        Map<String, Object> memo = base(TaskRecord.HUMAN_TASK).build().toMemo();
        Assert.assertFalse(memo.containsKey("users"));
        Assert.assertFalse(memo.containsKey("excludedUsers"));
        Assert.assertFalse(memo.containsKey("excludedRoles"));
        Assert.assertFalse(memo.containsKey("timeoutMillis"));

        Map<String, Object> withUsers = base(TaskRecord.HUMAN_TASK)
                .users(List.of("alice")).excludedRoles(List.of("intern")).build().toMemo();
        Assert.assertEquals(withUsers.get("users"), List.of("alice"));
        Assert.assertEquals(withUsers.get("excludedRoles"), List.of("intern"));
    }

    @Test
    public void reviewMemoAddsItsOwnFieldsUnderTheSameInputKey() {
        Map<String, Object> memo = base(TaskRecord.REVIEW_ACTIVITY).stepId("s1")
                .trigger("PRE_RUN").activityName("order.charge").build().toMemo();
        Assert.assertEquals(memo.get("workflowKind"), "REVIEW_ACTIVITY");
        Assert.assertEquals(memo.get("trigger"), "PRE_RUN");
        Assert.assertEquals(memo.get("activityName"), "order.charge");
        Assert.assertEquals(memo.get("errorMessage"), "");
        Assert.assertEquals(memo.get("taskInput"), Map.of("amount", 12));
        Assert.assertEquals(memo.get(WorkflowContextNative.REVIEW_STEP_ID_KEY),
                "s1" + WorkflowContextNative.REVIEW_STEP_ID_SUFFIX);
        Assert.assertFalse(memo.containsKey("activityArgs"));
    }

    @Test
    public void inputsCarryWhatTheChildReadsInReadingOrder() {
        Map<String, Object> inputs = base(TaskRecord.REVIEW_ACTIVITY)
                .trigger("ON_FAILURE").activityName("order.charge").errorMessage("boom").build().toInputs();
        Assert.assertEquals(inputs.keySet().iterator().next(), "taskId");
        Assert.assertEquals(inputs.get("taskName"), "order.approve");
        Assert.assertTrue(inputs.containsKey("timeoutMillis"));
        Assert.assertNull(inputs.get("timeoutMillis"));
        Assert.assertEquals(inputs.get("userRoles"), List.of("manager"));
        Assert.assertEquals(inputs.get("title"), "Approve");
        Assert.assertEquals(inputs.get("errorMessage"), "boom");
        Assert.assertEquals(inputs.get("parentWorkflowType"), "order");
    }
}
