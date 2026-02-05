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
// SIGNAL INFERENCE WORKFLOW - Tests for optional signalName in sendEvent
// ================================================================================
// 
// These workflows test the optional signalName feature:
// 1. Single signal workflow - signal name should be inferred
// 2. Distinct types workflow - signal name inferred by type matching
// 3. Workflow with no events - tests sendEvent to workflow without signals
//
// ================================================================================

import ballerina/workflow;

// ================================================================================
// SINGLE SIGNAL WORKFLOW - Signal name inference with one signal
// ================================================================================

# Input for single signal inference workflow.
#
# + id - The workflow identifier (readonly for correlation)
# + data - The input data
type SingleSignalInferInput record {|
    readonly string id;
    string data;
|};

# Signal data for the single signal.
#
# + id - The workflow identifier (readonly for correlation)
# + response - The response data
type SingleInferSignal record {|
    readonly string id;
    string response;
|};

# Result of single signal inference workflow.
#
# + inputData - The original input data
# + signalResponse - The response from the signal
type SingleSignalInferResult record {|
    string inputData;
    string signalResponse;
|};

# Workflow with a single signal - signal name should always be inferable.
#
# + ctx - The workflow context
# + input - The workflow input
# + signals - Record containing the signal future
# + return - The result combining input and signal data
@workflow:Process
function singleSignalInferWorkflow(
    workflow:Context ctx,
    SingleSignalInferInput input,
    record {|
        future<SingleInferSignal> onlySignal;
    |} signals
) returns SingleSignalInferResult|error {
    SingleInferSignal signal = check wait signals.onlySignal;
    return {
        inputData: input.data,
        signalResponse: signal.response
    };
}

// ================================================================================
// DISTINCT TYPES WORKFLOW - Signal name inference by type structure
// ================================================================================

# Input for distinct types workflow.
#
# + id - The workflow identifier (readonly for correlation)
# + requestId - The request identifier
type DistinctTypesInput record {|
    readonly string id;
    string requestId;
|};

# First signal with distinct structure (has 'approved' boolean).
#
# + id - The workflow identifier (readonly for correlation)
# + approved - Whether the approval was granted
# + approverName - The name of the approver
type ApprovalTypeSignal record {|
    readonly string id;
    boolean approved;
    string approverName;
|};

# Second signal with distinct structure (has 'amount' decimal).
#
# + id - The workflow identifier (readonly for correlation)
# + amount - The payment amount
# + transactionRef - The transaction reference
type PaymentTypeSignal record {|
    readonly string id;
    decimal amount;
    string transactionRef;
|};

# Third signal with distinct structure (has 'rating' int).
#
# + id - The workflow identifier (readonly for correlation)
# + rating - The feedback rating
# + comment - The feedback comment
type FeedbackTypeSignal record {|
    readonly string id;
    int rating;
    string comment;
|};

# Result of distinct types workflow.
#
# + wasApproved - Whether the approval was granted
# + paymentAmount - The payment amount received
# + feedbackRating - The feedback rating received
type DistinctTypesResult record {|
    boolean wasApproved;
    decimal paymentAmount;
    int feedbackRating;
|};

# Workflow with three signals of distinct types - each should be inferable.
#
# + ctx - The workflow context
# + input - The workflow input
# + signals - Record containing the signal futures
# + return - The result combining all signal data
@workflow:Process
function distinctTypesWorkflow(
    workflow:Context ctx,
    DistinctTypesInput input,
    record {|
        future<ApprovalTypeSignal> approval;
        future<PaymentTypeSignal> payment;
        future<FeedbackTypeSignal> feedback;
    |} signals
) returns DistinctTypesResult|error {
    // Wait for signals in order
    ApprovalTypeSignal approvalSignal = check wait signals.approval;
    PaymentTypeSignal paymentSignal = check wait signals.payment;
    FeedbackTypeSignal feedbackSignal = check wait signals.feedback;
    
    return {
        wasApproved: approvalSignal.approved,
        paymentAmount: paymentSignal.amount,
        feedbackRating: feedbackSignal.rating
    };
}

// ================================================================================
// EXPLICIT SIGNAL NAME WORKFLOW - For testing explicit signalName with ambiguous types
// ================================================================================

# Input for explicit signal name test.
#
# + id - The workflow identifier (readonly for correlation)
# + message - The input message
type ExplicitSignalInput record {|
    readonly string id;
    string message;
|};

# Ambiguous signal type 1 - same structure as type 2.
#
# + id - The workflow identifier (readonly for correlation)
# + value - The signal value
type AmbiguousSignal1 record {|
    readonly string id;
    string value;
|};

# Ambiguous signal type 2 - same structure as type 1.
#
# + id - The workflow identifier (readonly for correlation)
# + value - The signal value
type AmbiguousSignal2 record {|
    readonly string id;
    string value;
|};

# Result for explicit signal name workflow.
#
# + fromSignal1 - Value from the first signal
# + fromSignal2 - Value from the second signal
type ExplicitSignalResult record {|
    string fromSignal1;
    string fromSignal2;
|};

# Workflow with ambiguous signal types - requires explicit signalName.
#
# + ctx - The workflow context
# + input - The workflow input
# + signals - Record containing the signal futures
# + return - The result with values from both signals
@workflow:Process
function explicitSignalNameWorkflow(
    workflow:Context ctx,
    ExplicitSignalInput input,
    record {|
        future<AmbiguousSignal1> ambig1;
        future<AmbiguousSignal2> ambig2;
    |} signals
) returns ExplicitSignalResult|error {
    AmbiguousSignal1 sig1 = check wait signals.ambig1;
    AmbiguousSignal2 sig2 = check wait signals.ambig2;
    return {
        fromSignal1: sig1.value,
        fromSignal2: sig2.value
    };
}

// ================================================================================
// MIXED SIGNALS WORKFLOW - Some distinct, some need explicit name
// ================================================================================

# Input for mixed signals workflow.
#
# + id - The workflow identifier (readonly for correlation)
# + orderId - The order identifier
type MixedSignalsInput record {|
    readonly string id;
    string orderId;
|};

# Distinct signal type with unique field (statusCode: int).
#
# + id - The workflow identifier (readonly for correlation)
# + statusCode - The status code
# + description - The status description
type StatusUpdateSignal record {|
    readonly string id;
    int statusCode;
    string description;
|};

# Result for mixed signals workflow.
#
# + finalStatus - The final status code
type MixedSignalsResult record {|
    int finalStatus;
|};

# Workflow with a single distinct signal type.
#
# + ctx - The workflow context
# + input - The workflow input
# + signals - Record containing the signal future
# + return - The result with the status code from the signal
@workflow:Process
function mixedSignalsWorkflow(
    workflow:Context ctx,
    MixedSignalsInput input,
    record {|
        future<StatusUpdateSignal> statusUpdate;
    |} signals
) returns MixedSignalsResult|error {
    StatusUpdateSignal status = check wait signals.statusUpdate;
    return {
        finalStatus: status.statusCode
    };
}
