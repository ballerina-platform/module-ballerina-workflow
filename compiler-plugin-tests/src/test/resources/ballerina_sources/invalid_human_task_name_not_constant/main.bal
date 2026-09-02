// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/ai;
import ballerina/workflow;

final ai:Wso2ModelProvider chatModel = check new ("http://localhost:9099", "test-token");

final string taskId = "approval";

// ERROR: the agent human task name is interpolated — not a compile-time constant.
// The event channel beside it is the good case: a plain key is already constant.
final workflow:DurableAgent orderAgent = check new ({
    systemPrompt: {role: "Order assistant", instructions: "Help the user."},
    model: chatModel,
    humanTasks: {
        [string `approve-${taskId}`]: {userRoles: "manager"}
    },
    events: {
        customerReply: {request: string}
    }
});

// ERROR: the workflow human task name is a variable — not a compile-time constant.
@workflow:Workflow
function reviewFlow(workflow:Context ctx, string data) returns error? {
    anydata _ = check ctx->awaitHumanTask(taskId, {}, userRoles = "manager");
}
