# Order Processing Workflow Sample

This sample demonstrates a simple order processing workflow using Ballerina Workflow module.

## Overview

The workflow processes customer orders by:
1. Validating the order request
2. Checking inventory availability
3. Reserving stock
4. Confirming order completion

## Features Demonstrated

- **@Process annotation**: Defines the workflow entry point
- **@Activity annotation**: Marks activity functions for external interactions
- **Context usage**: Uses `workflow:Context` for calling activities
- **Error handling**: Proper error propagation and handling
- **Mock backend**: Simulates inventory management system

## Architecture

```
HTTP Request → Workflow Client → OrderProcessingWorkflow
                                          ↓
                                   Activities:
                                   - checkInventory
                                   - reserveStock
                                   ↓
                              Mock Inventory DB
```

## Running the Sample

### Prerequisites

1. Temporal server running on `localhost:7233`
2. Ballerina 2201.13.x installed
3. Java 21 installed

### Start the Service

```bash
# Build and run
bal build
bal run

# The service starts on http://localhost:9090
```

### Test the Workflow

Start an order:

```bash
curl -X POST http://localhost:9090/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId": "ORD-001", "item": "laptop", "quantity": 2}'
```

Response:
```json
{
  "status": "success",
  "workflowId": "ORD-001",
  "message": "Order processing started"
}
```

Query order status:

```bash
curl http://localhost:9090/orders/ORD-001/status
```

## Code Structure

```
order-processing/
├── main.bal              # HTTP service and workflow client
├── workflow.bal          # Workflow process definition
├── activities.bal        # Activity implementations
├── mock_inventory.bal    # Mock inventory backend
├── types.bal            # Type definitions
├── Config.toml          # Workflow configuration
├── Ballerina.toml       # Package configuration
└── README.md            # This file
```

## Cleanup

```bash
# Stop the application
pkill -f order_processing_sample

# Clean build artifacts
bal clean
```

## Key Concepts

### Process Function

The `@Process` annotation marks the workflow entry point:

```ballerina
@workflow:Process
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    // Workflow logic with activity calls via ctx->callActivity()
}
```

### Activity Functions

Activities are marked with `@Activity` and must be called via `ctx->callActivity()`:

```ballerina
@workflow:Activity
function checkInventory(string item, int quantity) returns InventoryStatus|error {
    // External system interaction
}
```

### Context Client Class

The `workflow:Context` parameter is a client class that provides:
- `callActivity()` - Call activity functions with retry/timeout
- `sleep()` - Durable delays
- `isReplaying()` - Check if in replay mode

## Extension Ideas

- Add payment processing activity
- Implement order cancellation with compensation
- Add inventory restock workflow
- Integrate with real inventory system
- Add order tracking queries
