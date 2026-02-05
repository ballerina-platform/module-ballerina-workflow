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

### 3. File Notification (`file-notification/`)

File storage to messaging notification workflow:
- File upload event handling
- Message formatting and posting
- Mock file storage and messaging systems

**Port**: 9092  
**Queue**: FILE_NOTIFICATION_QUEUE

### 4. Invoice Automation (`invoice-automation/`)

Automated invoice processing workflow:
- OCR data extraction (mocked)
- Invoice validation
- Duplicate detection
- Purchase order matching
- Approval routing
- Mock document processing and accounting systems

**Port**: 9093  
**Queue**: INVOICE_PROCESSING_QUEUE

### 5. Order with Payment (`order-with-payment/`)

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
4. **Workflow Module**: Built and available in local repository

## Quick Start

### Build the Workflow Module

From repository root:

```bash
cd native
mvn clean package

cd ../ballerina
bal pack && bal push --repository=local
```

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
- `awaitSignal()` - Wait for external signals
- `sleep()` - Durable delays
- `isReplaying()` - Replay detection

### Singleton Worker Pattern

Each sample uses the singleton worker pattern:

```ballerina
final workflow:PersistenceProvider provider = check new ({
    serviceUrl: "localhost:7233",
    namespace: "default"
});

listener workflow:Listener worker = check new (provider, {taskQueue: "..."});

service "WorkflowName" on worker {
    isolated remote function execute(workflow:Context ctx, ...) returns ... {
        // Workflow logic
    }
}
```

## Vendor-Neutral Design

All samples use mock backends instead of specific vendor APIs:
- **No vendor names**: Generic terms like "CRM", "Storage", "Messaging"
- **Mock implementations**: Simulate external systems in-memory
- **Extensible**: Easy to replace mocks with real integrations

## Common Patterns

### Error Handling

```ballerina
anydata result = check ctx->callActivity("activityName", args...);
if result is error {
    // Handle activity failure
}
```

### Correlation

```ballerina
map<string> correlationData = {
    "orderId": request.orderId  // readonly field in input type
};
```

### Query Methods

```ballerina
isolated remote function getStatus() returns map<anydata> {
    return {"status": self.workflowStatus};
}
```

### Signal Handling

```ballerina
// In workflow
anydata signalData = check ctx->awaitSignal("paymentReceived", 3600);

// From client
check client->signal(correlationData, "paymentReceived", {...});
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

### Activity Registration

Activities must be registered in `init()`:
```ballerina
function init() returns error? {
    check workflow:registerActivity("activityName", activityFunction);
}
```

## Extension Ideas

- Integrate with real APIs (Stripe, Twilio, AWS, etc.)
- Add compensation logic (SAGA pattern)
- Implement long-running workflows with multiple signals
- Add workflow versioning and migration
- Build dashboards using query methods
- Add metrics and observability

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
