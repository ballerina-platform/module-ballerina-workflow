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

import ballerina/workflow;
import ballerina/io;

// Activity Implementations
// Activities perform I/O operations and interact with external systems

# Check inventory availability
# This activity queries the mock inventory system
#
# + item - Item name/SKU
# + quantity - Requested quantity
# + return - Inventory status or error
@workflow:Activity
function checkInventory(string item, int quantity) returns InventoryStatus|error {
    io:println(string `[Activity] Checking inventory for item: ${item}, quantity: ${quantity}`);
    
    // Call mock inventory system
    InventoryStatus status = check getInventoryStatus(item, quantity);
    
    io:println(string `[Activity] Inventory check result - Available: ${status.available}, In Stock: ${status.inStock}`);
    
    return status;
}

# Reserve stock for an order
# This activity reserves items in the inventory system
#
# + orderId - Order identifier
# + item - Item name/SKU
# + quantity - Quantity to reserve
# + return - Reservation details or error
@workflow:Activity
function reserveStock(string orderId, string item, int quantity) returns StockReservation|error {
    io:println(string `[Activity] Reserving stock - Order: ${orderId}, Item: ${item}, Quantity: ${quantity}`);
    
    // Call mock inventory system
    StockReservation reservation = check reserveStockInInventory(orderId, item, quantity);
    
    io:println(string `[Activity] Stock reserved - Reservation ID: ${reservation.reservationId}`);
    
    return reservation;
}
