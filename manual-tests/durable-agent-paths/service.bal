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

import ballerina/http;
import ballerina/time;
import ballerina/workflow;
import ballerina/workflow.management;

configurable int port = 8095;

final readonly & Order sampleOrder = {orderId: "ORD-1", item: "laptop", quantity: 2, amount: 2400.00};

// Every scenario the runner knows, in run order.
final readonly & string[] scenarios = [
    "R01-text", "R02-string", "R03-int", "R04-boolean", "R05-enum", "R06-record", "R07-optional-fields",
    "R08-nested-record", "R09-record-array", "R10-map", "R11-annotated-record", "R12-union", "R13-json",
    "I01-record-input", "I02-json-payload", "M01-management-start",
    "C01-activity-tools", "C02-approval-approved", "C03-approval-rejected", "C04-ai-tool",
    "C05-human-task", "C06-chat", "C07-peer-agent", "C08-peer-workflow", "C09-activity-retry",
    "W01-agent-step", "W02-parallel-agents", "W03-human-task-in-agent", "W04-chat-from-workflow",
    "W05-agent-failure", "W06-non-blocking-read"
];

// Loopback only: this service completes tasks and reviews with fixed roles.
service /tests on new http:Listener(port, host = "127.0.0.1", timeout = 0) {

    resource function get scenarios() returns readonly & string[] {
        return scenarios;
    }

    resource function post run/[string scenario]() returns json|error {
        string started = time:utcToString(time:utcNow());
        string id = check startScenario(scenario);
        return {scenario, id, started};
    }

    resource function get result/[string scenario]/[string id]() returns json {
        json|error result = readScenario(scenario, id);
        if result is workflow:WorkflowInProgressError {
            return {status: "IN_PROGRESS"};
        }
        if result is error {
            return {status: "FAILED", 'error: result.message()};
        }
        return {status: "DONE", result};
    }

    // Pending items raised since the scenario started. Not filtered by parent workflow: a task raised by
    // an agent inside a workflow belongs to the agent, not to the scenario's workflow.
    resource function get pending(string since) returns json|error {
        management:HumanTaskSummary[] tasks = check management:listAllHumanTasks("PENDING", startTimeFrom = since);
        management:ReviewActivitySummary[] reviews =
                check management:listAllReviewActivities("PENDING", startTimeFrom = since);
        return {
            tasks: from var task in tasks select {taskId: task.taskId, taskName: task.taskName},
            reviews: from var review in reviews select {taskId: review.taskId, activityName: review.activityName}
        };
    }

    resource function post tasks/[string taskId](@http:Payload json result) returns json|error {
        check management:completeHumanTask(taskId, result, ["MANAGER"]);
        return {completed: taskId};
    }

    resource function post reviews/[string taskId](management:ReviewDecision decision) returns json|error {
        check management:completeReviewActivity(taskId, decision, ["FINANCE"]);
        return {decided: taskId};
    }

    resource function post chat/[string agentId](@http:Payload string message) returns json|error {
        string token = check chatAgent.sendData(agentId, "chat", message);
        string reply = check chatAgent.waitForDataResult(agentId, token);
        return {reply};
    }
}

isolated function startScenario(string scenario) returns string|error {
    match scenario {
        "R01-text" => {
            return textAgent.run("In one sentence, what is a durable workflow?");
        }
        "R02-string" => {
            return stringAgent.run("What is the capital of France?");
        }
        "R03-int" => {
            return intAgent.run("How many days are there in a leap year?");
        }
        "R04-boolean" => {
            return booleanAgent.run("Is 17 a prime number?");
        }
        "R05-enum" => {
            return enumAgent.run("Rate the fraud risk of a 5000 USD cash withdrawal at 3 a.m. from a new device.");
        }
        "R06-record" => {
            return recordAgent.run("Should a 120 USD team lunch expense claim with receipts be approved?");
        }
        "R07-optional-fields" => {
            return optionalFieldsAgent.run(
                    "Create a contact for Jane Perera, phone +94 77 123 4567. Her email address is unknown.");
        }
        "R08-nested-record" => {
            return nestedAgent.run(string `Build invoice INV-9 for customer Sam (sam@acme.test) with 2 x SKU-1
                    at 10.50 and 1 x SKU-2 at 99.00, and compute the total.`);
        }
        "R09-record-array" => {
            return recordArrayAgent.run("List three cities in Sri Lanka with their approximate population.");
        }
        "R10-map" => {
            return mapAgent.run("Give the number of letters in each of these words: apple, kiwi, banana.");
        }
        "R11-annotated-record" => {
            return annotatedAgent.run(
                    "Summarize 'durable workflows survive restarts' and score its clarity from 1 to 10.");
        }
        "R12-union" => {
            return unionAgent.run("Should a 40 USD taxi expense claim be approved?");
        }
        "R13-json" => {
            return jsonAgent.run("Describe one well-known book as a JSON object with title and year.");
        }
        "I01-record-input" => {
            return orderAgent.run("Review this order.", sampleOrder);
        }
        "I02-json-payload" => {
            return payloadAgent.run("How many numbers are in the input?", [3, 1, 4, 1, 5]);
        }
        "M01-management-start" => {
            management:WorkflowHandle started = check management:startInstance("orderAgent",
                    {query: "Review this order.", input: {orderId: "ORD-2", item: "mouse", quantity: 30, amount: 600}});
            return started.workflowId;
        }
        "C01-activity-tools" => {
            return toolsAgent.run("Is the laptop in stock? If so, reserve 2 units.");
        }
        "C02-approval-approved" => {
            return refundAgent.run("Refund 25.00 on order ORD-3.");
        }
        "C03-approval-rejected" => {
            return refundAgent.run("Refund 900.00 on order ORD-4.");
        }
        "C04-ai-tool" => {
            return currencyAgent.run("Convert 100 USD to LKR.");
        }
        "C05-human-task" => {
            return expediteAgent.run("Please expedite order ORD-7; the customer travels on Friday.");
        }
        "C06-chat" => {
            return chatAgent.run("");
        }
        "C07-peer-agent" => {
            return coordinatorAgent.run("What is Kandy known for?");
        }
        "C08-peer-workflow" => {
            return auditingAgent.run("Log that the nightly sync finished.");
        }
        "C09-activity-retry" => {
            return rateAgent.run("What is the USD to LKR rate?");
        }
        "W01-agent-step" => {
            return workflow:run(agentStepWorkflow, sampleOrder);
        }
        "W02-parallel-agents" => {
            return workflow:run(parallelAgentsWorkflow, sampleOrder);
        }
        "W03-human-task-in-agent" => {
            return workflow:run(humanTaskInAgentWorkflow);
        }
        "W04-chat-from-workflow" => {
            return workflow:run(chatFromWorkflow);
        }
        "W05-agent-failure" => {
            return workflow:run(agentFailureWorkflow);
        }
        "W06-non-blocking-read" => {
            return workflow:run(nonBlockingReadWorkflow);
        }
    }
    return error(string `Unknown scenario: ${scenario}`);
}

// Reads each result through its agent or workflow with the declared type, so the typed conversion is exercised.
isolated function readScenario(string scenario, string id) returns json|error {
    match scenario {
        "R01-text" => {
            string r = check textAgent.getResult(id);
            return r;
        }
        "R02-string" => {
            string r = check stringAgent.getResult(id);
            return r;
        }
        "R03-int" => {
            int r = check intAgent.getResult(id);
            return r;
        }
        "R04-boolean" => {
            boolean r = check booleanAgent.getResult(id);
            return r;
        }
        "R05-enum" => {
            Risk r = check enumAgent.getResult(id);
            return r;
        }
        "R06-record" => {
            Verdict r = check recordAgent.getResult(id);
            return r;
        }
        "R07-optional-fields" => {
            Contact r = check optionalFieldsAgent.getResult(id);
            return r;
        }
        "R08-nested-record" => {
            Invoice r = check nestedAgent.getResult(id);
            return r.toJson();
        }
        "R09-record-array" => {
            Cities r = check recordArrayAgent.getResult(id);
            return r;
        }
        "R10-map" => {
            WordLengths r = check mapAgent.getResult(id);
            return r;
        }
        "R11-annotated-record" => {
            AnnotatedSummary r = check annotatedAgent.getResult(id);
            return r;
        }
        "R12-union" => {
            VerdictOrNote r = check unionAgent.getResult(id);
            return r;
        }
        "R13-json" => {
            json r = check jsonAgent.getResult(id);
            return r;
        }
        "I01-record-input"|"M01-management-start" => {
            OrderDecision r = check orderAgent.getResult(id);
            return r;
        }
        "I02-json-payload" => {
            int r = check payloadAgent.getResult(id);
            return r;
        }
        "C01-activity-tools" => {
            string r = check toolsAgent.getResult(id);
            return r;
        }
        "C02-approval-approved"|"C03-approval-rejected" => {
            string r = check refundAgent.getResult(id);
            return r;
        }
        "C04-ai-tool" => {
            decimal r = check currencyAgent.getResult(id);
            return r;
        }
        "C05-human-task" => {
            ManagerApproval r = check expediteAgent.getResult(id);
            return r;
        }
        "C06-chat" => {
            string r = check chatAgent.getResult(id);
            return r;
        }
        "C07-peer-agent" => {
            string r = check coordinatorAgent.getResult(id);
            return r;
        }
        "C08-peer-workflow" => {
            string r = check auditingAgent.getResult(id);
            return r;
        }
        "C09-activity-retry" => {
            string r = check rateAgent.getResult(id);
            return r;
        }
        "W01-agent-step" => {
            OrderDecision r = check workflow:getResult(id);
            return r;
        }
        "W02-parallel-agents" => {
            ParallelOutcome r = check workflow:getResult(id);
            return r.toJson();
        }
        "W03-human-task-in-agent" => {
            ManagerApproval r = check workflow:getResult(id);
            return r;
        }
        "W04-chat-from-workflow" => {
            string[] r = check workflow:getResult(id);
            return r;
        }
        "W05-agent-failure"|"W06-non-blocking-read" => {
            string r = check workflow:getResult(id);
            return r;
        }
    }
    return error(string `Unknown scenario: ${scenario}`);
}
