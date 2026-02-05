// Copyright (c) 2025 WSO2 LLC. (http://www.wso2.com).
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
import ballerina/io;

# Checks the inventory level for a given item.
# This is a workflow activity that can be invoked from workflow functions.
# In a real implementation, this would query an inventory database or external system.
#
# + item - The name of the item to check inventory for
# + return - The current stock level, or an error if the check fails
@workflow:Activity
isolated function checkInventory(string item) returns int|error {
    io:println(string `[Activity] Checking inventory for: ${item}`);
    return 10;  // Mock stock level
}
