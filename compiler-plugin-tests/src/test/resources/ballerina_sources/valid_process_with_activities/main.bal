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

type OrderInput record {|
    string orderId;
    string productId;
    int quantity;
|};

type OrderResult record {|
    string status;
    string trackingNumber;
|};

type EmailRequest record {|
    string to;
    string subject;
    string body;
|};

// Activity function to validate an order
@workflow:Activity
function validateOrder(OrderInput input) returns boolean|error {
    // Simulated order validation
    return input.quantity > 0;
}

// Activity function to process payment
@workflow:Activity
function processPayment(string orderId, decimal amount) returns string|error {
    // Simulated payment processing
    return "PAY-" + orderId + "-001";
}

// Activity function to send email notification
@workflow:Activity
function sendNotification(EmailRequest request) returns boolean|error {
    // Simulated email sending
    return true;
}

// Process function that calls activity functions
@workflow:Process
function orderProcess(OrderInput input) returns OrderResult|error {
    // Call activity to validate order
    boolean isValid = check validateOrder(input);
    if !isValid {
        return error("Invalid order");
    }
    
    // Call activity to process payment
    string paymentId = check processPayment(input.orderId, 100.0d);
    
    // Call activity to send notification
    EmailRequest emailReq = {
        to: "customer@example.com",
        subject: "Order Confirmed",
        body: "Your order " + input.orderId + " has been confirmed."
    };
    _ = check sendNotification(emailReq);
    
    return {
        status: "COMPLETED",
        trackingNumber: paymentId
    };
}
