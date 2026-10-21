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

final ai:Wso2ModelProvider routerModel = check new ("http://localhost:9099", "test-token");

type OptionalField record {|
    string claimId;
    string[] excludedUsers?;
|};

type RequiredField record {|
    string claimId;
    string excludedUsers;
|};

type Base record {|
    string[] excludedUsers?;
|};

type IncludedField record {|
    *Base;
    string claimId;
|};

type AliasOfOptional OptionalField;

type ReadonlyOptional readonly & OptionalField;

type AudienceLookalikes record {|
    string claimId;
    string[] users;
    string[] userRoles;
    string[] excludedRoles;
|};

type OpenRecord record {|
    string claimId;
    json...;
|};

final workflow:DurableAgent claimAgent = check new ({
    systemPrompt: {role: "Claim router", instructions: "Route claims."},
    model: routerModel,
    humanTasks: {
        optionalField: {userRoles: "manager", taskInputType: OptionalField},
        requiredField: {userRoles: "manager", taskInputType: RequiredField},
        includedField: {userRoles: "manager", taskInputType: IncludedField},
        aliasField: {userRoles: "manager", taskInputType: AliasOfOptional},
        readonlyField: {userRoles: "manager", taskInputType: ReadonlyOptional},
        audienceLookalikes: {userRoles: "manager", taskInputType: AudienceLookalikes},
        openRecord: {userRoles: "manager", taskInputType: OpenRecord}
    }
});
