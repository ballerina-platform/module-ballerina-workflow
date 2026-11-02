// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
//
// WSO2 LLC. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import ballerina/workflow;

@workflow:Workflow
function agentStepWorkflow(workflow:Context ctx, Order input) returns OrderDecision|error {
    string id = check orderAgent.run("Review this order.", input);
    OrderDecision decision = check orderAgent.waitForResult(id);
    return decision;
}

@workflow:Workflow
function parallelAgentsWorkflow(workflow:Context ctx, Order input) returns ParallelOutcome|error {
    string verdictId = check recordAgent.run(
            string `Should order ${input.orderId} for ${input.quantity} x ${input.item} be approved?`);
    string decisionId = check orderAgent.run("Review this order.", input);
    Verdict verdict = check recordAgent.waitForResult(verdictId);
    OrderDecision decision = check orderAgent.waitForResult(decisionId);
    return {verdict, decision};
}

@workflow:Workflow
function humanTaskInAgentWorkflow(workflow:Context ctx) returns ManagerApproval|error {
    string id = check expediteAgent.run("Please expedite order ORD-8; the customer travels on Friday.");
    ManagerApproval approval = check expediteAgent.waitForResult(id);
    return approval;
}

@workflow:Workflow
function chatFromWorkflow(workflow:Context ctx) returns string[]|error {
    string id = check chatAgent.run("");
    string firstTurn = check chatAgent.sendData(id, "chat", "Is the laptop in stock?");
    string firstReply = check chatAgent.waitForDataResult(id, firstTurn);
    string lastTurn = check chatAgent.sendData(id, "chat", "Thanks, goodbye.");
    string lastReply = check chatAgent.waitForDataResult(id, lastTurn);
    return [firstReply, lastReply];
}

@workflow:Workflow
function agentFailureWorkflow(workflow:Context ctx) returns string|error {
    string id = check limitedAgent.run("Check the stock of laptop, mouse and monitor.");
    string|error answer = limitedAgent.waitForResult(id);
    if answer is error {
        return "fallback: " + answer.message();
    } else {
        return answer;
    }
}

@workflow:Workflow
function nonBlockingReadWorkflow(workflow:Context ctx) returns string|error {
    string id = check textAgent.run("Name one planet.");
    string|error early = textAgent.getResult(id);
    string answer = check textAgent.waitForResult(id);
    if early is workflow:WorkflowInProgressError {
        return "in progress, then: " + answer;
    } else if early is error {
        return "early read failed: " + early.message();
    } else {
        return "already done: " + answer;
    }
}
