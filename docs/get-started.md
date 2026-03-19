# Get Started with Ballerina Workflow

This guide walks you through writing and running your first workflow, then introduces activities step by step.

## Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

## Create a New Ballerina Project

```bash
bal new order-processing
cd order-processing
```

## Step 1: Write a Simple Workflow

Replace the contents of `main.bal` with:

```ballerina
import ballerina/workflow;
import ballerina/io;

type OrderRequest record {|
    string orderId;
    string item;
    int quantity;
|};

@workflow:Workflow
function processOrder(OrderRequest request) returns string|error {
    return string `Order ${request.orderId}: ${request.quantity} unit(s) of "${request.item}" received.`;
}

public function main() returns error? {
    // Start a new workflow instance
    string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop", quantity: 2});
    io:println("Workflow started with ID: " + workflowId);

    workflow:WorkflowExecutionInfo result = check workflow:getWorkflowResult(workflowId);
    io:println("Result: " + result.result.toString());
}
```

### Key Concepts

- **`@workflow:Workflow`** marks a function as a durable workflow. The runtime checkpoints its state and recovers from failures automatically.
- **`workflow:run()`** starts a new workflow instance and returns a unique **workflow instance ID**.
- **Workflow instance ID** is a unique string identifying a running workflow. Use it to query status, send data, or retrieve results.

## Configure and Run

Create a `Config.toml` to use in-memory mode — no server setup needed:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"
```

Run the application:

```bash
bal run
```

Output:

```
Workflow started with ID: 8f3a2b1c-...
Result: Order ORD-001: 2 unit(s) of "laptop" received.
```

> To persist workflow state or connect to a cloud deployment, set up a Temporal server. See [Set Up Temporal Server](set-up-temporal-server.md).

## Step 2: Add Activities

Workflows coordinate **activities** — functions that perform external operations like checking inventory or calling APIs. The runtime tracks each activity execution and its result.

### Define the Activities

Add two activity functions:

```ballerina
@workflow:Activity
function checkInventory(string item, int quantity) returns boolean|error {
    io:println(string `Checking inventory for ${item}, quantity: ${quantity}`);
    return true;
}

@workflow:Activity
function reserveStock(string orderId, string item, int quantity) returns string|error {
    io:println(string `Reserving ${quantity} unit(s) of ${item} for order ${orderId}`);
    return "RES-" + orderId;
}
```

#### Key Concepts

- **`@workflow:Activity`** marks a function as an activity. Use activities for any operation that interacts with external systems — API calls, database queries, sending emails.
- Activity results are tracked by the runtime. If an activity fails, you can configure a retry policy. See [Write Activity Functions](write-activity-functions.md) for details.

### Update the Workflow

Update `processOrder` to call the activities:

```ballerina
@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns string|error {
    boolean inStock = check ctx->callActivity(checkInventory, {
        "item": request.item,
        "quantity": request.quantity
    });

    if !inStock {
        return string `Order ${request.orderId} failed: "${request.item}" is out of stock.`;
    }

    string reservationId = check ctx->callActivity(reserveStock, {
        "orderId": request.orderId,
        "item": request.item,
        "quantity": request.quantity
    });

    return string `Order ${request.orderId} confirmed. Reservation ID: ${reservationId}`;
}
```

#### Key Concepts

- **`workflow:Context`** is the first parameter of a workflow that interacts with the workflow runtime. Use it to call activities or retrieve workflow state.
- **`ctx->callActivity()`** is not a regular function call. It invokes the activity and records its result in the workflow state. This ensures the activity result is preserved even if the workflow is interrupted and recovered.

### Final Code

Your complete `main.bal`:

```ballerina
import ballerina/workflow;
import ballerina/io;

type OrderRequest record {|
    string orderId;
    string item;
    int quantity;
|};

@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns string|error {
    boolean inStock = check ctx->callActivity(checkInventory, {
        "item": request.item,
        "quantity": request.quantity
    });

    if !inStock {
        return string `Order ${request.orderId} failed: "${request.item}" is out of stock.`;
    }

    string reservationId = check ctx->callActivity(reserveStock, {
        "orderId": request.orderId,
        "item": request.item,
        "quantity": request.quantity
    });

    return string `Order ${request.orderId} confirmed. Reservation ID: ${reservationId}`;
}

@workflow:Activity
function checkInventory(string item, int quantity) returns boolean|error {
    io:println(string `Checking inventory for ${item}, quantity: ${quantity}`);
    return true;
}

@workflow:Activity
function reserveStock(string orderId, string item, int quantity) returns string|error {
    io:println(string `Reserving ${quantity} unit(s) of ${item} for order ${orderId}`);
    return "RES-" + orderId;
}

public function main() returns error? {
    // Start a new workflow instance
    string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop", quantity: 2});
    io:println("Workflow started with ID: " + workflowId);

    workflow:WorkflowExecutionInfo result = check workflow:getWorkflowResult(workflowId);
    io:println("Result: " + result.result.toString());
}
```

Run:

```bash
bal run
```

Output:

```
Workflow started with ID: 5e7d9a2f-...
Checking inventory for laptop, quantity: 2
Reserving 2 unit(s) of laptop for order ORD-001
Result: Order ORD-001 confirmed. Reservation ID: RES-ORD-001
```

The full source for this sample is in [`examples/get-started/`](../examples/get-started/).

## What's Next

- [Key Concepts](key-concepts.md) — Understand workflows, activities, external data, and timer events
- [Set Up Temporal Server](set-up-temporal-server.md) — Install Temporal for persistent workflow execution
- [Write Workflow Functions](write-workflow-functions.md) — Workflow signatures and determinism rules
- [Write Activity Functions](write-activity-functions.md) — Activity patterns and retry configuration
