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

# Marks a function as a workflow process.
#
# A process function defines the main workflow logic that orchestrates activities
# and handles workflow state. Process functions must be deterministic - they should
# produce the same results given the same inputs and should not perform I/O directly.
# Use activities for non-deterministic operations.
#
# # Example
# ```ballerina
# @workflow:Process
# function orderProcess(Order input) returns OrderResult|error {
#     // Workflow logic here
# }
# ```
public annotation Process on function;

# Marks a function as a workflow activity.
#
# An activity function performs non-deterministic operations such as I/O,
# database calls, external API calls, or any operation with side effects.
# Activities are executed exactly once by the workflow runtime, even during
# workflow replay after failures.
#
# # Example
# ```ballerina
# @workflow:Activity
# function sendEmail(EmailRequest request) returns EmailResponse|error {
#     // Send email using external service
# }
# ```
public annotation Activity on function;