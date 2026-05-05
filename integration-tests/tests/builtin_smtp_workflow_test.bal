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

import ballerina/test;
import ballerina/workflow;

// In-process GreenMail SMTP server, started before the test and stopped after.
GreenMail? smtpFixture = ();

@test:BeforeGroups {value: ["integration-smtp"]}
function startSmtpServer() returns error? {
    smtpFixture = check startSmtpFixture();
}

@test:AfterGroups {value: ["integration-smtp"]}
function stopSmtpServer() {
    GreenMail? gm = smtpFixture;
    if gm is GreenMail {
        gmStop(gm);
    }
}

@test:Config {
    groups: ["integration", "integration-smtp"]
}
function testSendEmail() returns error? {
    GreenMail? gm = smtpFixture;
    if gm is () {
        return error("SMTP fixture not started");
    }
    int beforeCount = gmReceivedCount(gm);

    string testId = uniqueId("smtp");
    EmailInput input = {
        id: testId,
        recipient: "recipient@example.com",
        subject: "hello from workflow",
        body: "Test body."
    };
    string workflowId = check workflow:run(sendEmailWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED",
            "sendEmailWorkflow should complete. Error: "
                    + (execInfo.errorMessage ?: "none"));

        int afterCount = gmReceivedCount(gm);
        int delivered = afterCount - beforeCount;
        test:assertEquals(delivered, 1,
            "GreenMail should receive exactly one new message from the workflow; got delta: "
                + delivered.toString());
}
