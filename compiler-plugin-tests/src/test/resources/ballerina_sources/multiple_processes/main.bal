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

import ballerina/workflow;

type UserSignupInput record {|
    string email;
    string name;
|};

type UserResult record {|
    string userId;
    string status;
|};

type OrderInput record {|
    string userId;
    string productId;
|};

type OrderResult record {|
    string orderId;
    string status;
|};

// Shared activity function - validates email
@workflow:Activity
function validateEmail(string email) returns boolean|error {
    // Simulated email validation
    return email.includes("@");
}

// Shared activity function - logs an event
@workflow:Activity
function logEvent(string eventType, string message) returns boolean|error {
    // Simulated logging
    return true;
}

// First process function - user signup
@workflow:Process
function userSignupProcess(UserSignupInput input) returns UserResult|error {
    // Validate email using activity
    boolean isValid = check validateEmail(input.email);
    if !isValid {
        return error("Invalid email");
    }
    
    // Log the signup event
    _ = check logEvent("SIGNUP", "User " + input.name + " signed up");
    
    return {
        userId: "USR-001",
        status: "ACTIVE"
    };
}

// Second process function - order creation
@workflow:Process
function orderCreationProcess(OrderInput input) returns OrderResult|error {
    // Log order initiation
    _ = check logEvent("ORDER", "Order started for user " + input.userId);
    
    return {
        orderId: "ORD-" + input.productId + "-001",
        status: "PENDING"
    };
}

// Third process function - no activity calls
@workflow:Process
function simpleProcess(string input) returns string {
    return "Processed: " + input;
}
