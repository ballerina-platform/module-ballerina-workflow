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

// Type Definitions for Order Processing Workflow

# Represents an order request
#
# + orderId - Unique order identifier
# + item - Item name/SKU
# + quantity - Number of items requested
public type OrderRequest record {|
    readonly string orderId;  // readonly for correlation
    string item;
    int quantity;
|};

# Represents the result of order processing
#
# + orderId - Order identifier
# + status - Order status (COMPLETED, FAILED, OUT_OF_STOCK)
# + message - Descriptive message
# + reservationId - Stock reservation ID (if successful)
public type OrderResult record {|
    string orderId;
    string status;
    string message;
    string? reservationId;
|};

# Represents inventory status check result
#
# + item - Item name/SKU
# + available - Available quantity
# + reserved - Reserved quantity
# + inStock - Whether sufficient stock is available
public type InventoryStatus record {|
    string item;
    int available;
    int reserved;
    boolean inStock;
|};

# Represents a stock reservation
#
# + reservationId - Unique reservation identifier
# + orderId - Associated order ID
# + item - Item name/SKU
# + quantity - Reserved quantity
# + timestamp - Reservation timestamp
public type StockReservation record {|
    string reservationId;
    string orderId;
    string item;
    int quantity;
    string timestamp;
|};
