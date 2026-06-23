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

// ================================================================================
// SCALAR / NON-RECORD DATA WORKFLOWS
// ================================================================================
//
// These workflows verify that `workflow:sendData` works for every persistable
// (JSON/XML-compatible) anydata type - not only records. Previously a bare
// boolean / int / string / json / xml payload was coerced into an empty
// `map<anydata>` on delivery, producing a `{ballerina}ConversionError`.
//
// ================================================================================

import ballerina/workflow;

# Input for the scalar data workflows.
#
# + id - The workflow identifier
type ScalarInput record {|
    string id;
|};

# Workflow that waits for a boolean signal and echoes it back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the boolean future
# + return - The received boolean
@workflow:Workflow
function booleanDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<boolean> approved;
    |} events
) returns boolean|error {
    boolean approved = check wait events.approved;
    return approved;
}

# Workflow that waits for an int signal and echoes it back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the int future
# + return - The received int
@workflow:Workflow
function intDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<int> count;
    |} events
) returns int|error {
    int count = check wait events.count;
    return count;
}

# Workflow that waits for a string signal and echoes it back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the string future
# + return - The received string
@workflow:Workflow
function stringDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<string> note;
    |} events
) returns string|error {
    string note = check wait events.note;
    return note;
}

# Workflow that waits for a json signal and echoes it back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the json future
# + return - The received json
@workflow:Workflow
function jsonDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<json> payload;
    |} events
) returns json|error {
    json payload = check wait events.payload;
    return payload;
}

# Workflow that waits for an xml signal and echoes it back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the xml future
# + return - The received xml
@workflow:Workflow
function xmlDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<xml> document;
    |} events
) returns xml|error {
    xml document = check wait events.document;
    return document;
}

# A row in the table signal payload.
#
# + id - The row identifier
# + name - The row name
type TableRow record {|
    readonly int id;
    string name;
|};

# Workflow that waits for a table signal and echoes the table back.
#
# + ctx - The workflow context
# + input - The workflow input
# + events - Record containing the table future
# + return - The received table
@workflow:Workflow
function tableDataWorkflow(
    workflow:Context ctx,
    ScalarInput input,
    record {|
        future<table<TableRow> key(id)> rows;
    |} events
) returns table<TableRow> key(id)|error {
    table<TableRow> key(id) rows = check wait events.rows;
    return rows;
}
