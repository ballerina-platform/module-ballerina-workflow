# Ballerina Workflow Samples

This directory contains vendor-neutral workflow samples demonstrating the Ballerina Workflow module with the new syntax.

## Available Samples

### 1. Order Processing (`order-processing/`)

Simple order processing workflow demonstrating:
- Basic workflow structure with @Process and @Activity
- Inventory checking and stock reservation
- Error handling and validation
- Mock inventory backend

**Port**: 9090  
**Queue**: ORDER_PROCESSING_QUEUE

### 2. CRM Sync (`crm-sync/`)

Bidirectional contact synchronization between CRM systems:
- Create/update contact sync
- Duplicate detection by email
- Field validation and mapping
- Mock CRM backends

**Port**: 9091  
**Queue**: CRM_SYNC_QUEUE

### 3. Order with Payment (`order-with-payment/`)

Order processing with payment signal workflow:
- Inventory checking
- Signal-based payment waiting
- Future-based event handling
- Correlation with order ID

**Port**: 9094  
**Queue**: ORDER_PAYMENT_QUEUE

## Prerequisites

1. **Temporal Server**: Running on `localhost:7233`
2. **Ballerina**: 2201.13.x or later
3. **Java**: 21

## Quick Start

### Run a Sample

```bash
cd samples/order-processing
bal build
bal run
```

### Test the Sample

```bash
# Order Processing Example
curl -X POST http://localhost:9090/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId": "ORD-001", "item": "laptop", "quantity": 2}'
```

## Sample Structure

Each sample follows this structure:

```
sample-name/
├── Ballerina.toml        # Package configuration
├── Dependencies.toml     # Dependencies
├── Config.toml          # Workflow configuration
├── README.md            # Sample documentation
├── types.bal            # Type definitions
├── workflow.bal         # Workflow process definition
├── activities.bal       # Activity implementations
├── mock_*.bal          # Mock backend systems
└── main.bal            # HTTP service entry point
```

## Key Concepts

### @Process Annotation

Marks the workflow entry point:

```ballerina
@workflow:Process
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    // Workflow orchestration logic
}
```

### @Activity Annotation

Marks activity functions for external interactions:

```ballerina
@workflow:Activity
function checkInventory(string item, int quantity) returns InventoryStatus|error {
    // External system call
}
```

### Context Client Class

The `workflow:Context` parameter provides:
- `callActivity()` - Execute activities with retry
- `sleep()` - Durable delays
- `isReplaying()` - Replay detection

### Starting Workflow Executor

Each sample uses following workflow executor initialization configuration in `Config.toml`:

```toml
# Config.toml
[ballerina.workflow.workflowConfig]
provider = "TEMPORAL"
url = "localhost:7233"
namespace = "default"

[ballerina.workflow.workflowConfig.params]
taskQueue = "MY_TASK_QUEUE"
```

All the workflows in current project are automatically registered to the workflow executor. No manual registration is needed.

## Common Patterns

### Error Handling

```ballerina
MyData|error result = ctx->callActivity(activityFunc, args...);
if result is error {
    // Handle activity failure
}
```

### Correlation

Correlation keys use `readonly` fields to match workflows with signals/events:

```ballerina
// Define input type with readonly correlation keys
type OrderInput record {|
    readonly string orderId;    // Correlation key
    readonly string customerId; // Correlation key
    string item;                // Regular field
    int quantity;
|};

// Event type MUST have same readonly fields
type PaymentEvent record {|
    readonly string orderId;    // Must match OrderInput
    readonly string customerId; // Must match OrderInput
    decimal amount;             // Event-specific data
|};
```


### Event Handling

```ballerina
// In workflow - use events record with future<T> fields
@workflow:Process
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentEvent> paymentReceived; |} events
) returns OrderResult|error {
    // Check inventory first
    var result = check ctx->callActivity(checkInventory, {item: input.item, quantity: input.quantity});
    
    // Wait for payment event (correlated by orderId + customerId)
    PaymentEvent payment = check wait events.paymentReceived;
    
    return {status: "paid", amount: payment.amount};
}

// From client - send event (correlation keys in data are used for routing)
PaymentEvent paymentData = {orderId: "ORD-001", customerId: "C123", amount: 99.99};
_ = check workflow:sendEvent(orderProcess, paymentData, "paymentReceived");
```

## Troubleshooting

### Temporal Connection

Ensure Temporal server is running:
```bash
temporal server start-dev
```

### Port Conflicts

Each sample uses a different port. Change in `main.bal` if needed:
```ballerina
service /... on new http:Listener(9090) {  // Change port here
```

## Learn More

- [Workflow Module Documentation](../../ballerina/README.md)
- [Architecture Design](../../README.md)
- [Instruction Files](../../.github/instructions/)

## Contributing

When creating new samples:
1. Use vendor-neutral terminology
2. Provide mock backends
3. Include comprehensive README
4. Follow the standard structure
5. Add example curl commands
6. Document key concepts demonstrated
