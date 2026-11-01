// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

// Every field below that can be read from the declaration is written in shorthand: '{model}' is
// '{model: model}', so the agent registers exactly as it would with the field written in full.
final ai:Wso2ModelProvider model = check new ("http://localhost:9099", "test-token");
final readonly & ai:SystemPrompt systemPrompt = {role: "Order assistant", instructions: "Help the user."};
const int maxIter = 4;

type Verdict record {|
    boolean approved;
|};

type Question record {|
    string text;
|};

final typedesc<Verdict> resultType = Verdict;
final typedesc<Question> request = Question;
final string description = "Checks whether an item is in stock.";

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    return item.length() > 0;
}

final function activity = checkInventory;

final workflow:DurableAgent shorthandAgent = check new ({
    systemPrompt,
    model,
    maxIter,
    resultType,
    activities: [{activity, description}],
    events: {chat: {request}},
    humanTasks: {signoff: {resultType, userRoles: ["MANAGER"]}}
});

public function main() returns error? {
    string id = check shorthandAgent.run("Is a laptop in stock?");
    Verdict verdict = check shorthandAgent.waitForResult(id);
    _ = verdict;
}
