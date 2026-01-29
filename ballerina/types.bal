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

# Workflow execution context.
#
# The Context provides access to workflow-specific operations and metadata
# within a process function. It is an optional first parameter for @Process functions.
#
# + workflowId - The unique identifier for this workflow execution
# + runId - The unique identifier for this specific run of the workflow
public type Context record {|
    string workflowId;
    string runId;
|};

# Information about a registered workflow process.
#
# + name - The name of the registered process
# + activities - Array of activity names associated with this process
public type ProcessRegistration record {
    string name;
    string[] activities;
};

# Information about all registered workflows.
# This is a map where keys are process names and values are their registration info.
public type WorkflowRegistry map<ProcessRegistration>;
