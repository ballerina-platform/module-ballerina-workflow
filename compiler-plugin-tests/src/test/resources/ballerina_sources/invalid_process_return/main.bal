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

import ballerina/workflow;

type OrderInput record {|
    string orderId;
|};

// This class is NOT a subtype of anydata - used for invalid return type
class InvalidResult {
    string status;

    function init(string status) {
        self.status = status;
    }

    function getStatus() returns string {
        return self.status;
    }
}

// Invalid: Workflow function with non-anydata return type
// Should trigger WORKFLOW_105 error
@workflow:Workflow
function workflowWithInvalidReturn(workflow:Context ctx, OrderInput input) returns InvalidResult|error {
    return new InvalidResult("DONE");
}
