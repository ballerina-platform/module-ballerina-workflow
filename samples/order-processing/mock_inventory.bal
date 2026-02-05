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

import ballerina/uuid;

// Mock Inventory Database
// Simulates an external inventory management system

type InventoryItem record {|
    string item;
    int available;
    int reserved;
|};

type Reservation record {|
    string reservationId;
    string orderId;
    string item;
    int quantity;
    string timestamp;
|};

// In-memory inventory storage
final map<InventoryItem> inventory = {
    "laptop": {item: "laptop", available: 10, reserved: 0},
    "mouse": {item: "mouse", available: 50, reserved: 0},
    "keyboard": {item: "keyboard", available: 30, reserved: 0},
    "monitor": {item: "monitor", available: 15, reserved: 0},
    "headset": {item: "headset", available: 25, reserved: 0}
};

// In-memory reservation storage
final map<Reservation> reservations = {};

# Check inventory availability for an item
#
# + item - Item name/SKU
# + quantity - Requested quantity
# + return - Inventory status or error
public function getInventoryStatus(string item, int quantity) returns InventoryStatus|error {
    lock {
        InventoryItem? inventoryItem = inventory[item];
        if inventoryItem is () {
            return error(string `Item not found: ${item}`);
        }

        boolean inStock = inventoryItem.available >= quantity;
        return {
            item: item,
            available: inventoryItem.available,
            reserved: inventoryItem.reserved,
            inStock: inStock
        };
    }
}

# Reserve stock for an order
#
# + orderId - Order identifier
# + item - Item name/SKU
# + quantity - Quantity to reserve
# + return - Reservation details or error
public function reserveStockInInventory(string orderId, string item, int quantity) returns StockReservation|error {
    lock {
        InventoryItem? inventoryItem = inventory[item];
        if inventoryItem is () {
            return error(string `Item not found: ${item}`);
        }

        if inventoryItem.available < quantity {
            return error(string `Insufficient stock for ${item}. Available: ${inventoryItem.available}, Requested: ${quantity}`);
        }

        // Update inventory
        inventoryItem.available -= quantity;
        inventoryItem.reserved += quantity;

        // Create reservation
        string reservationId = uuid:createType1AsString();
        string timestamp = "2025-02-01T10:00:00Z";  // In real implementation, use time:utcNow()

        Reservation reservation = {
            reservationId: reservationId,
            orderId: orderId,
            item: item,
            quantity: quantity,
            timestamp: timestamp
        };

        reservations[reservationId] = reservation;

        return {
            reservationId: reservationId,
            orderId: orderId,
            item: item,
            quantity: quantity,
            timestamp: timestamp
        };
    }
}

# Get reservation details
#
# + reservationId - Reservation identifier
# + return - Reservation or error
public function getReservation(string reservationId) returns Reservation|error {
    lock {
        Reservation? reservation = reservations[reservationId];
        if reservation is () {
            return error(string `Reservation not found: ${reservationId}`);
        }
        return reservation;
    }
}

# Reset inventory (for testing)
public function resetInventory() {
    lock {
        inventory["laptop"] = {item: "laptop", available: 10, reserved: 0};
        inventory["mouse"] = {item: "mouse", available: 50, reserved: 0};
        inventory["keyboard"] = {item: "keyboard", available: 30, reserved: 0};
        inventory["monitor"] = {item: "monitor", available: 15, reserved: 0};
        inventory["headset"] = {item: "headset", available: 25, reserved: 0};
        reservations.removeAll();
    }
}
