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

# Represents an order request initiated by a customer.
#
# + orderId - Unique identifier for the order
# + item - Name of the item being ordered
public type OrderRequest record {|
    string orderId;
    string item;
|};

# Represents payment confirmation received for an order.
# Used as a data payload to confirm payment has been processed.
#
# + amount - Payment amount received
public type PaymentConfirmation record {|
    decimal amount;
|};

# Represents the final result of an order processing workflow.
#
# + orderId - The order identifier
# + status - Order status (e.g., "COMPLETED", "FAILED")
# + message - Descriptive message about the order outcome
public type OrderResult record {|
    string orderId;
    string status;
    string message;
|};
