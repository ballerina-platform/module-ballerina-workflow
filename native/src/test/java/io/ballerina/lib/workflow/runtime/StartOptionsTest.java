/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.workflow.runtime;

import io.ballerina.lib.workflow.compiler.WorkflowConstants;
import org.testng.Assert;
import org.testng.annotations.Test;

public class StartOptionsTest {

    // A four-byte code point: one character, four UTF-8 bytes
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    private static StartOptions withId(String id) {
        return new StartOptions(id, null, null, null, null);
    }

    @Test
    public void theCompilerPluginAndTheRuntimeRefuseTheSameIds() {
        Assert.assertEquals(WorkflowConstants.RESERVED_INSTANCE_ID_PREFIXES, StartOptions.RESERVED_PREFIXES,
                "WORKFLOW_166 and StartOptions.validate must name the same reserved prefixes");
        Assert.assertEquals(WorkflowConstants.MAX_INSTANCE_ID_BYTES, StartOptions.MAX_INSTANCE_ID_BYTES,
                "WORKFLOW_166 and StartOptions.validate must share one length limit");
    }

    @Test
    public void validateRefusesWhatTheCompilerPluginRefuses() {
        // The same cases as the plugin's invalid_run_with_id fixture, judged by the runtime
        for (String id : new String[]{"", " ", " x", "x ", "humantask-1", "childagent-2", "x".repeat(256),
                EMOJI.repeat(64)}) {
            Assert.assertThrows(InvalidStartOptionsException.class, () -> withId(id).validate());
        }
        for (String id : new String[]{"order-1", "x".repeat(255), EMOJI.repeat(63) + "abc"}) {
            withId(id).validate();
        }
    }

    @Test
    public void theLengthLimitIsMeasuredInBytes() {
        // 64 four-byte code points are 64 characters but 256 bytes, one over the engine's limit
        String emoji = EMOJI.repeat(64);
        Assert.assertEquals(emoji.codePointCount(0, emoji.length()), 64);
        InvalidStartOptionsException refused =
                Assert.expectThrows(InvalidStartOptionsException.class, () -> withId(emoji).validate());
        Assert.assertTrue(refused.getMessage().contains("256"), refused.getMessage());
    }

    @Test
    public void generatedIdsCarryTheDefaultPolicies() {
        StartOptions generated = StartOptions.generated();
        Assert.assertFalse(generated.hasCallerId());
        Assert.assertEquals(generated.conflictPolicy(),
                io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL);
        Assert.assertEquals(generated.reusePolicy(),
                io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
    }
}
