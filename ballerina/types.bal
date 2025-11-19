// Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
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

# Represents a duration of time interval.
public type Duration record {
    # Number of months
    int months?;
    # Number of days
    int days?;
    # Number of hours
    int hours?;
    # Number of minutes
    int minutes?;
    # Number of seconds
    int seconds?;
    # Number of milliseconds
    int milliseconds?;
};

# Represents a workflow execution.
#
# + id - The unique identifier of the workflow execution
public type Execution record {|
    string id;
|};

# Represents workflow model data containing workflow definition and associated methods.
#
# + workflowName - The name of the workflow
# + execute - The execute method definition
# + signals - Map of signal method definitions
# + queries - Map of query method definitions
# + activities - Map of activity functions
public type WorkflowModelData record {|
    string workflowName;
    WorkflowAction execute;
    map<WorkflowAction> signals;
    map<WorkflowAction> queries;
    Activities activities;
|};

# Represents a workflow action (execute, signal, or query method).
#
# + name - The name of the action
# + parameters - Map of parameter names to their types
# + returnType - The return type descriptor of the action
public type WorkflowAction record {|
    string name;
    Parameters parameters;
    typedesc returnType;
|};

# Represents the parameters of a workflow action as a map of parameter names to type descriptors.
public type Parameters map<typedesc>;

# Represents workflow methods including execute, signal, and query methods.
#
# + execute - The execute method definition
# + signals - Map of signal method definitions
# + queries - Map of query method definitions
public type WorkflowMethods record {|
    WorkflowAction execute;
    map<WorkflowAction> signals;
    map<WorkflowAction> queries;
|};

# Represents a collection of activity functions that can be executed within a workflow.
public type Activities map<function>;
