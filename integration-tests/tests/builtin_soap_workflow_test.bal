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

@test:Config {
    groups: ["integration"]
}
function testCallSoapAPI() returns error? {
    string testId = uniqueId("soap-add");
    SoapAddInput input = {id: testId, intA: 2, intB: 3};
    string workflowId = check workflow:run(soapAddWorkflow, input);

    workflow:WorkflowExecutionInfo execInfo =
            check workflow:getWorkflowResult(workflowId, 30);
    test:assertEquals(execInfo.status, "COMPLETED",
            "soapAddWorkflow should complete. Error: "
                    + (execInfo.errorMessage ?: "none"));

    // Mock SOAP service always replies with <quer:AddResult>5</quer:AddResult>
    // — assert that the returned envelope contains it.
    string resultStr = execInfo.result.toString();
    test:assertTrue(resultStr.includes("<quer:AddResult>5</quer:AddResult>"),
            "Expected SOAP response to contain <quer:AddResult>5</quer:AddResult> "
                    + "but was: " + resultStr);
}
