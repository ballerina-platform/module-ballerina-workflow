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

// Order Processing Workflow
// Demonstrates the new workflow syntax with @Process annotation

# Process Order Workflow
# This workflow orchestrates the order processing flow:
# 1. Checks inventory availability
# 2. Reserves stock if available
# 3. Returns order result
#
# + ctx - Workflow context for calling activities
# + request - Order request with orderId, item, quantity
# + return - Order result or error
@workflow:Process
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    io:println(string `[Workflow] Starting order processing for Order ID: ${request.orderId}`);

    // Step 1: Check inventory availability
    // Activities MUST be called via ctx->callActivity() passing the function reference
    InventoryStatus inventoryStatus = check ctx->callActivity(checkInventory, {"item": request.item, "quantity": request.quantity});

    if !inventoryStatus.inStock {
        io:println(string `[Workflow] Order ${request.orderId} failed: Out of stock`);
        
        return {
            orderId: request.orderId,
            status: "OUT_OF_STOCK",
            message: string `Insufficient stock for ${request.item}. Available: ${inventoryStatus.available}, Requested: ${request.quantity}`,
            reservationId: ()
        };
    }

    // Step 2: Reserve stock
    StockReservation reservation = check ctx->callActivity(reserveStock, {"orderId": request.orderId, "item": request.item, "quantity": request.quantity});

    io:println(string `[Workflow] Order ${request.orderId} completed successfully`);

    return {
        orderId: request.orderId,
        status: "COMPLETED",
        message: string `Order completed successfully. ${request.quantity} ${request.item}(s) reserved.`,
        reservationId: reservation.reservationId
    };
}
