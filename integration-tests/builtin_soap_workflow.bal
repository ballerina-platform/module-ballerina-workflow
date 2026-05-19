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
// BUILT-IN SOAP CONNECTOR ACTIVITY — workflow tests
// ================================================================================
//
// Verifies that the `ballerina/workflow.activity:callSoapAPI` builtin can be
// invoked end-to-end through `ctx->callActivity(...)` against an in-process
// HTTP listener that speaks SOAP. A module-level `final soap11:Client` is
// registered as a workflow connection by the compiler plugin via the
// generated `wfInternal:registerConnection(...)` call.
//
// ================================================================================

import ballerina/http;
import ballerina/soap.soap11;
import ballerina/workflow;
import ballerina/workflow.activity;

// --------------------------------------------------------------------------------
// MOCK SOAP HTTP SERVICE (in-process)
// --------------------------------------------------------------------------------

listener http:Listener mockSoapListener = new (9596);

service /soap on mockSoapListener {

    // POST /soap/calculator → returns a SOAP 1.1 envelope echoing the request
    // path so tests can assert on the round trip. We don't parse the request
    // body; just return a canned XML envelope that contains an <Answer>5</Answer>.
    resource function post calculator(http:Request req) returns xml|http:InternalServerError {
        xml response = xml `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                                <soap:Body>
                                    <quer:AddResponse xmlns:quer="http://tempuri.org/">
                                        <quer:AddResult>5</quer:AddResult>
                                    </quer:AddResponse>
                                </soap:Body>
                            </soap:Envelope>`;
        return response;
    }
}

// Module-level `final` `soap11:Client` — registered as a workflow connection
// by the compiler plugin.
final soap11:Client mockSoap = check new ("http://localhost:9596/soap/calculator");

// --------------------------------------------------------------------------------
// TYPES
// --------------------------------------------------------------------------------

# Input for the SOAP demo workflow.
#
# + id - Workflow identifier
# + intA - First operand
# + intB - Second operand
type SoapAddInput record {|
    string id;
    int intA;
    int intB;
|};

// --------------------------------------------------------------------------------
// WORKFLOW
// --------------------------------------------------------------------------------

# Workflow that uses the builtin `callSoapAPI` activity to call an Add SOAP
# operation and returns the response envelope as `xml`.
#
# + ctx - Workflow context
# + input - Workflow input (operands and id)
# + return - The SOAP response envelope, or an error
@workflow:Workflow
function soapAddWorkflow(workflow:Context ctx, SoapAddInput input)
        returns string|error {
    xml envelope = xml `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                            <soap:Body>
                                <quer:Add xmlns:quer="http://tempuri.org/">
                                    <quer:intA>${input.intA}</quer:intA>
                                    <quer:intB>${input.intB}</quer:intB>
                                </quer:Add>
                            </soap:Body>
                        </soap:Envelope>`;
    xml response = check ctx->callActivity(activity:callSoapAPI, {
        connection: mockSoap,
        body: envelope,
        action: "http://tempuri.org/Add"
    });
    return response.toString();
}
