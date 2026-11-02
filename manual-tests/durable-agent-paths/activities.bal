// Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com).
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

import ballerina/ai;
import ballerina/workflow;

# Returns how many units of an item are in stock.
#
# + item - Item name
# + return - Units in stock
@workflow:Activity
function checkStock(string item) returns int|error {
    return item.toLowerAscii().includes("laptop") ? 3 : 40;
}

# Reserves units of an item in a warehouse.
#
# + warehouse - Warehouse code, bound by the agent declaration
# + item - Item name
# + quantity - Units to reserve
# + return - Reservation id
@workflow:Activity
function reserveStock(string warehouse, string item, int quantity) returns string|error {
    return string `RES-${warehouse}-${item}-${quantity}`;
}

# Issues a refund for an order.
#
# + orderId - Order id
# + amount - Amount to refund
# + return - Refund id
@workflow:Activity
function issueRefund(string orderId, decimal amount) returns string|error {
    return string `REFUND-${orderId}-${amount}`;
}

isolated int rateCalls = 0;

# Returns the exchange rate from USD to a currency. Fails on every other call to exercise retries.
#
# + currency - Target currency code
# + return - Units of the currency per USD
@workflow:Activity
function fetchExchangeRate(string currency) returns decimal|error {
    lock {
        rateCalls += 1;
        if rateCalls % 2 == 1 {
            return error("rate service unavailable");
        }
    }
    return currency == "LKR" ? 300.0d : 1.0d;
}

# Converts an amount between currencies at fixed demo rates.
#
# + amount - Amount to convert
# + fromCurrency - Source currency code
# + toCurrency - Target currency code
# + return - Converted amount
@ai:AgentTool
isolated function convertCurrency(decimal amount, string fromCurrency, string toCurrency) returns decimal {
    map<decimal> perUsd = {USD: 1.0d, LKR: 300.0d, EUR: 0.9d};
    decimal fromRate = perUsd[fromCurrency] ?: 1.0d;
    decimal toRate = perUsd[toCurrency] ?: 1.0d;
    return amount / fromRate * toRate;
}

# Records a message in the audit log.
#
# + ctx - Workflow context
# + entry - The message to record
# + return - The recorded message
@workflow:Workflow
function auditLog(workflow:Context ctx, AuditEntry entry) returns string|error {
    return entry.message;
}
