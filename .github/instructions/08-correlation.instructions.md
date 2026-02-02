# Correlation Keys via Readonly Fields

applyTo: "**/*.bal,**/WorkflowValidatorTask.java,**/WorkflowWorkerNative.java,**/WorkflowRuntime.java,**/CorrelationExtractor.java"

---

## Overview

Correlation keys enable workflows to be identified by business identifiers (not just workflow IDs). This feature uses **readonly fields** in record types to define correlation keys that:

1. Identify the target workflow instance when sending signals
2. Register as Temporal Search Attributes for visibility queries
3. Enable lookup of workflows by business identifiers
4. **Prevent duplicate workflows** - detect and reject attempts to start workflows with duplicate correlation keys

## Core Concept: Readonly Fields as Correlation Keys

In Ballerina, `readonly` fields in a record type are immutable after initialization. We leverage this semantic to define **correlation keys** - fields that remain constant throughout the workflow lifecycle.

### Design Principles

1. **Type Constraint**: Input types must be `record {| anydata...; |}` (record types)
2. **Correlation via Readonly**: Fields marked `readonly` become correlation keys
3. **Signal Matching**: Signal data types must have the **same readonly fields** (name and type) as the process input for correlation
4. **Events Require Readonly Fields**: If a process has events (signals), input MUST have readonly fields for correlation

## Current Implementation

### 1. Ballerina Layer

#### Defining Correlation Keys (User Code)
```ballerina
// Order input with correlation keys
type OrderInput record {|
    readonly string orderId;      // Correlation key #1
    readonly string customerId;   // Correlation key #2
    string productName;           // Regular field (not for correlation)
    int quantity;
|};

// Payment signal with SAME correlation keys
type PaymentSignal record {|
    readonly string orderId;      // Must match OrderInput.orderId
    readonly string customerId;   // Must match OrderInput.customerId
    decimal amount;
    string transactionRef;
|};

@workflow:Process
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    record {| future<PaymentSignal> payment; |} events
) returns OrderResult|error {
    PaymentSignal payment = check wait events.payment;
    return processPayment(payment);
}
```

#### Starting Workflow
```ballerina
// Start workflow - readonly fields become Search Attributes
OrderInput input = {
    orderId: "ORD-12345",
    customerId: "CUST-789",
    productName: "Widget",
    quantity: 10
};

// Workflow ID is auto-generated using UUID v7
// Correlation keys stored as Temporal Search Attributes
string workflowId = check workflow:startProcess(orderProcess, input);
```

#### Sending Signals with Correlation
```ballerina
// Signal is routed using correlation keys (no workflowId needed)
PaymentSignal payment = {
    orderId: "ORD-12345",      // Correlation key
    customerId: "CUST-789",    // Correlation key
    amount: 99.99d,
    transactionRef: "TXN-001"
};

// Lookup by correlation keys, then send signal
_ = check workflow:sendEvent(orderProcess, payment, "payment");
```

#### Duplicate Detection
```ballerina
// First workflow starts successfully
string wfId1 = check workflow:startProcess(orderProcess, {
    orderId: "ORD-123",
    customerId: "CUST-456",
    productName: "Widget",
    quantity: 1
});

// Second attempt with same correlation keys FAILS
string|error result = workflow:startProcess(orderProcess, {
    orderId: "ORD-123",        // Same orderId
    customerId: "CUST-456",    // Same customerId
    productName: "Gadget",     // Different data
    quantity: 2
});

if result is error {
    // Error: "DuplicateWorkflowError: A running workflow already exists..."
    // After first workflow completes, new workflow with same keys can start
}
```

### 2. Compiler Plugin Layer

#### WorkflowValidatorTask.java
Location: [WorkflowValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowValidatorTask.java)

**Correlation Validation Rules:**
- **WORKFLOW_113**: Input type must be a record type (not plain anydata)
- **WORKFLOW_114**: Signal constraint types must have same readonly fields as input type
- **WORKFLOW_115**: Readonly field type mismatch between input and signal types
- **WORKFLOW_116**: Process with events MUST have readonly fields for correlation

```java
/**
 * Validates correlation key consistency between process input and signal types.
 * 
 * Rules:
 * 1. If process has events, input MUST have readonly fields for correlation
 * 2. All signal types must have the SAME readonly fields as the input
 */
private void validateCorrelationKeys(
        FunctionDefinitionNode functionNode,
        SyntaxNodeAnalysisContext context,
        RecordTypeSymbol inputType,
        List<RecordTypeSymbol> signalTypes) {
    
    // Extract readonly fields from input type
    Map<String, TypeSymbol> inputReadonlyFields = extractReadonlyFields(inputType);
    
    // If process has events but no readonly fields, report error
    if (inputReadonlyFields.isEmpty() && !signalTypes.isEmpty()) {
        reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_116,
            "Process with events must have readonly fields in input for correlation. " +
            "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')");
        return;
    }
    
    // Validate each signal type has matching readonly fields
    for (RecordTypeSymbol signalType : signalTypes) {
        Map<String, TypeSymbol> signalReadonlyFields = extractReadonlyFields(signalType);
        
        // Check if signal has all input's readonly fields
        for (Map.Entry<String, TypeSymbol> entry : inputReadonlyFields.entrySet()) {
            String fieldName = entry.getKey();
            TypeSymbol inputFieldType = entry.getValue();
            
            if (!signalReadonlyFields.containsKey(fieldName)) {
                reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_114,
                    String.format("Signal type '%s' is missing readonly field '%s' " +
                        "required for correlation with process input",
                        signalType.getName().orElse("unknown"), fieldName));
            } else {
                TypeSymbol signalFieldType = signalReadonlyFields.get(fieldName);
                if (!inputFieldType.signature().equals(signalFieldType.signature())) {
                    reportDiagnostic(context, functionNode, WorkflowConstants.WORKFLOW_115,
                        String.format("Readonly field '%s' type mismatch: " +
                            "input has '%s', signal '%s' has '%s'",
                            fieldName, inputFieldType.signature(),
                            signalType.getName().orElse("unknown"),
                            signalFieldType.signature()));
                }
            }
        }
    }
}

/**
 * Extracts readonly fields from a record type.
 */
private Map<String, TypeSymbol> extractReadonlyFields(RecordTypeSymbol recordType) {
    Map<String, TypeSymbol> readonlyFields = new LinkedHashMap<>();
    
    for (RecordFieldSymbol field : recordType.fieldDescriptors().values()) {
        if (field.isReadonly()) {
            readonlyFields.put(field.getName().get(), field.typeDescriptor());
        }
    }
    
    return readonlyFields;
}
```

#### WorkflowConstants.java Error Codes
```java
// Correlation validation errors
public static final String WORKFLOW_113 = "WORKFLOW_113";
public static final String WORKFLOW_114 = "WORKFLOW_114";
public static final String WORKFLOW_115 = "WORKFLOW_115";
public static final String WORKFLOW_116 = "WORKFLOW_116";

public static final String PROCESS_INPUT_MUST_BE_RECORD = 
    "@Process function input parameter must be a record type for correlation support";
public static final String SIGNAL_MISSING_CORRELATION_KEY = 
    "Signal type '%s' is missing readonly field '%s' required for correlation with process input";
public static final String CORRELATION_KEY_TYPE_MISMATCH = 
    "Readonly field '%s' type mismatch: input has '%s', signal '%s' has '%s'";
public static final String CORRELATION_KEY_REQUIRED_FOR_EVENTS = 
    "Process with events must have readonly fields in input for correlation. " +
    "Add 'readonly' modifier to fields used for correlation (e.g., 'readonly string customerId')";
```

### 3. Native Layer

#### CorrelationExtractor.java
Location: [CorrelationExtractor.java](native/src/main/java/io/ballerina/stdlib/workflow/utils/CorrelationExtractor.java)

```java
public final class CorrelationExtractor {

    /**
     * Extracts correlation key values from input data based on the record type.
     * Only readonly fields are considered correlation keys.
     *
     * @param inputData  the input data map
     * @param recordType the record type definition (can be null)
     * @return map of correlation key names to values (never null, may be empty)
     */
    public static Map<String, Object> extractCorrelationKeys(
            BMap<BString, Object> inputData, RecordType recordType) {

        Map<String, Object> correlationKeys = new LinkedHashMap<>();

        if (recordType != null) {
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                Field field = entry.getValue();
                String fieldName = entry.getKey();

                // Only readonly fields are correlation keys
                if (isReadonlyField(field)) {
                    Object value = inputData.get(StringUtils.fromString(fieldName));
                    if (value != null) {
                        correlationKeys.put(fieldName, value);
                    }
                }
            }
        }

        return correlationKeys;
    }
    
    /**
     * Extracts correlation keys from a Ballerina BMap based on its record type.
     * Automatically extracts RecordType from the BMap.
     */
    public static Map<String, Object> extractCorrelationKeysFromMap(BMap<BString, Object> inputData) {
        Map<String, Object> correlationKeys = new LinkedHashMap<>();

        if (inputData == null) {
            return correlationKeys;
        }

        // Get record type from BMap
        Type type = inputData.getType();
        RecordType recordType = extractRecordType(type);

        if (recordType != null) {
            for (Map.Entry<String, Field> entry : recordType.getFields().entrySet()) {
                Field field = entry.getValue();
                String fieldName = entry.getKey();

                if (isReadonlyField(field)) {
                    Object value = inputData.get(StringUtils.fromString(fieldName));
                    if (value != null) {
                        // Convert BString to String for consistency
                        if (value instanceof BString) {
                            correlationKeys.put(fieldName, ((BString) value).getValue());
                        } else {
                            correlationKeys.put(fieldName, value);
                        }
                    }
                }
            }
        }

        return correlationKeys;
    }
    
    /**
     * Checks if a field is marked as readonly.
     */
    public static boolean isReadonlyField(Field field) {
        return SymbolFlags.isFlagOn(field.getFlags(), SymbolFlags.READONLY);
    }
    
    /**
     * Generates a workflow ID using UUID v7 (time-ordered UUID).
     * Format: processName-<uuid7>
     * Example: orderProcess-019c19e6-68f6-7e9c-ba1c-62a6e71f7802
     */
    public static String generateWorkflowId(String processName) {
        return processName + "-" + generateUuidV7();
    }
    
    /**
     * Generates a UUID v7 (time-ordered UUID) per RFC 9562.
     * Uses timestamp + random bits for uniqueness and sortability.
     */
    private static String generateUuidV7() {
        // Get current timestamp in milliseconds
        long timestamp = System.currentTimeMillis();
        
        // Generate random bytes for the rest
        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[10];
        random.nextBytes(randomBytes);
        
        // Build UUID v7 format
        // 48 bits: timestamp
        // 4 bits: version (0111 = 7)
        // 12 bits: random
        // 2 bits: variant (10)
        // 62 bits: random
        
        long mostSigBits = (timestamp << 16) | ((randomBytes[0] & 0x0F) << 8) | (randomBytes[1] & 0xFF);
        mostSigBits = (mostSigBits & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000007000L; // Set version to 7
        
        long leastSigBits = 0;
        for (int i = 2; i < 10; i++) {
            leastSigBits = (leastSigBits << 8) | (randomBytes[i] & 0xFF);
        }
        leastSigBits = (leastSigBits & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // Set variant to 10
        
        UUID uuid = new UUID(mostSigBits, leastSigBits);
        return uuid.toString();
    }
    
    /**
     * Converts correlation keys to Temporal Search Attributes format.
     * Capitalizes field names (e.g., orderId -> OrderId)
     */
    public static Map<String, Object> toSearchAttributes(Map<String, Object> correlationKeys) {
        Map<String, Object> searchAttributes = new LinkedHashMap<>();
        
        for (Map.Entry<String, Object> entry : correlationKeys.entrySet()) {
            String key = capitalize(entry.getKey());
            Object value = convertToSearchAttributeValue(entry.getValue());
            searchAttributes.put(key, value);
        }
        
        return searchAttributes;
    }
    
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    
    private static Object convertToSearchAttributeValue(Object value) {
        if (value instanceof BString) {
            return ((BString) value).getValue();
        }
        return value;
    }
}
```

#### WorkflowRuntime.java - startProcess with Duplicate Detection
Location: [WorkflowRuntime.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/WorkflowRuntime.java)

```java
/**
 * Starts a new workflow process with correlation key support and duplicate detection.
 * 
 * Workflow ID: Generated using UUID v7 (time-ordered, sortable)
 * Correlation Keys: Extracted from readonly fields, stored as Temporal Search Attributes
 * Duplicate Detection: Checks for running workflows with same correlation keys before starting
 */
public String startProcess(String processName, Object input) {
    // Get process function and extract input type
    BFunctionPointer processFunction = WorkflowWorkerNative.getProcessRegistry().get(processName);
    RecordType inputRecordType = EventExtractor.getInputRecordType(processFunction);
    
    // Extract correlation keys from input (only readonly fields)
    BMap<BString, Object> inputMap = (BMap<BString, Object>) input;
    Map<String, Object> correlationKeys;
    
    if (inputMap.getType() instanceof RecordType) {
        // Use type from BMap
        correlationKeys = CorrelationExtractor.extractCorrelationKeysFromMap(inputMap);
    } else {
        // Use type from function signature
        correlationKeys = CorrelationExtractor.extractCorrelationKeys(inputMap, inputRecordType);
    }
    
    // Validation: If process expects events but no correlation keys, signals cannot be routed
    RecordType eventsRecordType = EventExtractor.getEventsRecordType(processFunction);
    boolean hasEvents = eventsRecordType != null && !eventsRecordType.getFields().isEmpty();
    
    if (hasEvents && correlationKeys.isEmpty()) {
        throw new RuntimeException(
            "Process '" + processName + "' has events but input has no readonly fields. " +
            "Add 'readonly' modifier to fields used for correlation.");
    }
    
    // Check for duplicate workflow (single query, no retry)
    if (!correlationKeys.isEmpty()) {
        String existingWorkflowId = findWorkflowByCorrelationKeys(
            processName, correlationKeys, true, false);
        
        if (existingWorkflowId != null) {
            throw new DuplicateWorkflowException(
                "A running workflow already exists with the same correlation keys: " + correlationKeys,
                existingWorkflowId);
        }
    }
    
    // Generate workflow ID using UUID v7
    String workflowId = CorrelationExtractor.generateWorkflowId(processName);
    
    // Convert correlation keys to Search Attributes
    Map<String, Object> searchAttributes = CorrelationExtractor.toSearchAttributes(correlationKeys);
    
    // Get workflow client
    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
    String taskQueue = WorkflowWorkerNative.getTaskQueue();
    
    try {
        // Build workflow options with search attributes
        WorkflowOptions.Builder optionsBuilder = WorkflowOptions.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(taskQueue);
        
        // Add search attributes if we have correlation keys
        if (!searchAttributes.isEmpty()) {
            TypedSearchAttributes typedSearchAttributes = buildTypedSearchAttributes(searchAttributes);
            optionsBuilder.setTypedSearchAttributes(typedSearchAttributes);
        }
        
        WorkflowOptions options = optionsBuilder.build();
        
        // Start workflow
        WorkflowStub workflowStub = client.newUntypedWorkflowStub(processName, options);
        workflowStub.start(input);
        
        LOGGER.info("Started workflow: {} with ID: {} and correlation keys: {}",
            processName, workflowId, correlationKeys);
        
        return workflowId;
        
    } catch (Exception e) {
        throw new RuntimeException("Failed to start workflow: " + e.getMessage(), e);
    }
}

/**
 * Builds TypedSearchAttributes from correlation key map.
 */
private TypedSearchAttributes buildTypedSearchAttributes(Map<String, Object> attributes) {
    TypedSearchAttributes.Builder builder = TypedSearchAttributes.newBuilder();
    
    for (Map.Entry<String, Object> entry : attributes.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        
        // Add to search attributes based on type
        if (value instanceof String) {
            SearchAttributeKey<String> attrKey = SearchAttributeKey.forKeyword(key);
            builder.set(attrKey, (String) value);
        } else if (value instanceof Long) {
            SearchAttributeKey<Long> attrKey = SearchAttributeKey.forLong(key);
            builder.set(attrKey, (Long) value);
        } else if (value instanceof Integer) {
            SearchAttributeKey<Long> attrKey = SearchAttributeKey.forLong(key);
            builder.set(attrKey, ((Integer) value).longValue());
        } else if (value instanceof Boolean) {
            SearchAttributeKey<Boolean> attrKey = SearchAttributeKey.forBoolean(key);
            builder.set(attrKey, (Boolean) value);
        } else if (value instanceof Double) {
            SearchAttributeKey<Double> attrKey = SearchAttributeKey.forDouble(key);
            builder.set(attrKey, (Double) value);
        }
    }
    
    return builder.build();
}
```

#### WorkflowRuntime.java - sendEvent with Correlation Lookup
```java
/**
 * Sends a signal to a workflow using correlation keys for lookup.
 * 
 * Signal routing:
 * 1. Extract correlation keys (readonly fields) from signal data
 * 2. Build a visibility query using Search Attributes
 * 3. Find matching workflow ID with retry for eventual consistency
 * 4. Send signal to that workflow
 */
public boolean sendEvent(String processName, Object eventData, String signalName) {
    BFunctionPointer processFunction = WorkflowWorkerNative.getProcessRegistry().get(processName);
    RecordType signalRecordType = EventExtractor.getSignalRecordType(processFunction, signalName);
    
    BMap<BString, Object> eventMap = (BMap<BString, Object>) eventData;
    Map<String, Object> correlationKeys;
    
    if (eventMap.getType() instanceof RecordType) {
        correlationKeys = CorrelationExtractor.extractCorrelationKeysFromMap(eventMap);
    } else {
        correlationKeys = CorrelationExtractor.extractCorrelationKeys(eventMap, signalRecordType);
    }
    
    if (correlationKeys.isEmpty()) {
        throw new RuntimeException(
            "Signal data has no readonly fields for correlation. " +
            "Cannot route signal without correlation keys.");
    }
    
    // Use Visibility API with retry to find workflow by Search Attributes
    // withRetry=true for eventual consistency (up to 10 retries with exponential backoff)
    String workflowId = findWorkflowByCorrelationKeys(processName, correlationKeys, true, true);
    
    if (workflowId == null) {
        throw new RuntimeException("No running workflow found for correlation keys: " + correlationKeys);
    }
    
    // Send signal
    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
    WorkflowStub workflowStub = client.newUntypedWorkflowStub(workflowId);
    workflowStub.signal(signalName, eventData);
    
    LOGGER.info("Sent signal '{}' to workflow: {} (found via correlation: {})",
        signalName, workflowId, correlationKeys);
    
    return true;
}

/**
 * Finds a workflow by correlation keys using Temporal Visibility API.
 * 
 * @param processName the workflow type name
 * @param correlationKeys the correlation keys to match
 * @param runningOnly if true, only search running workflows
 * @param withRetry if true, retry with exponential backoff for eventual consistency
 * @return workflow ID if found, null otherwise
 */
private String findWorkflowByCorrelationKeys(
        String processName,
        Map<String, Object> correlationKeys,
        boolean runningOnly,
        boolean withRetry) {
    
    // Build visibility query
    StringBuilder query = new StringBuilder();
    query.append("WorkflowType = '").append(processName).append("'");
    
    if (runningOnly) {
        query.append(" AND ExecutionStatus = 'Running'");
    }
    
    // Add correlation key conditions
    Map<String, Object> searchAttributes = CorrelationExtractor.toSearchAttributes(correlationKeys);
    for (Map.Entry<String, Object> entry : searchAttributes.entrySet()) {
        query.append(" AND ").append(entry.getKey()).append(" = ");
        
        if (entry.getValue() instanceof String) {
            query.append("'").append(entry.getValue()).append("'");
        } else {
            query.append(entry.getValue());
        }
    }
    
    WorkflowClient client = WorkflowWorkerNative.getWorkflowClient();
    String namespace = client.getOptions().getNamespace();
    
    int maxRetries = withRetry ? VISIBILITY_MAX_RETRIES : 1;
    long retryDelay = VISIBILITY_RETRY_DELAY_MS;
    
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            // Execute visibility query
            ListWorkflowExecutionsRequest request = ListWorkflowExecutionsRequest.newBuilder()
                .setNamespace(namespace)
                .setQuery(query.toString())
                .setPageSize(1)
                .build();
            
            ListWorkflowExecutionsResponse response = 
                client.getWorkflowServiceStubs().blockingStub().listWorkflowExecutions(request);
            
            if (response.getExecutionsCount() > 0) {
                String workflowId = response.getExecutions(0).getExecution().getWorkflowId();
                LOGGER.debug("Found workflow by correlation keys: {}", workflowId);
                return workflowId;
            }
            
            if (withRetry && attempt < maxRetries - 1) {
                // Wait before retry (exponential backoff)
                Thread.sleep(retryDelay);
                retryDelay *= 2;
            }
            
        } catch (Exception e) {
            LOGGER.warn("Error querying workflow by correlation keys (attempt {}): {}",
                attempt + 1, e.getMessage());
            
            if (!withRetry || attempt >= maxRetries - 1) {
                throw new RuntimeException("Failed to query workflow by correlation keys", e);
            }
        }
    }
    
    return null;
}
```

#### BallerinaWorkflowAdapter - Upsert Search Attributes
In [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java):

```java
public static class BallerinaWorkflowAdapter implements DynamicWorkflow {
    @Override
    public Object execute(EncodedValues args) {
        String workflowType = Workflow.getInfo().getWorkflowType();
        
        // Extract workflow arguments
        Object[] workflowArgs = extractWorkflowArguments(args);
        
        // Upsert correlation keys as Search Attributes
        if (workflowArgs.length > 0) {
            Object firstArg = workflowArgs[0];
            if (firstArg instanceof BMap) {
                BMap<BString, Object> inputMap = (BMap<BString, Object>) firstArg;
                Map<String, Object> correlationKeys = 
                    CorrelationExtractor.extractCorrelationKeysFromMap(inputMap);
                
                if (!correlationKeys.isEmpty()) {
                    Map<String, Object> searchAttributes = 
                        CorrelationExtractor.toSearchAttributes(correlationKeys);
                    
                    // Upsert search attributes in workflow context
                    TypedSearchAttributes typedAttrs = buildTypedSearchAttributes(searchAttributes);
                    Workflow.upsertTypedSearchAttributes(typedAttrs);
                }
            }
        }
        
        // Continue with workflow execution...
    }
}
```

## Temporal Search Attributes

### Automatic Registration
Temporal Cloud and self-hosted Temporal (v1.20+) support dynamic Search Attribute registration. The SDK automatically infers types:
- `String` → `Keyword` or `Text`
- `Long/Integer` → `Int`
- `Boolean` → `Bool`
- `Double/Float` → `Double`

### Eventual Consistency
Search Attributes have eventual consistency (typically <1 second delay). The system uses retry with exponential backoff when looking up workflows by correlation keys.

### Query Syntax
Visibility queries use SQL-like syntax:
```sql
WorkflowType = 'orderProcess' 
AND OrderId = 'ORD-123' 
AND CustomerId = 'CUST-456'
AND ExecutionStatus = 'Running'
```

## Error Handling

### DuplicateWorkflowException
Location: [DuplicateWorkflowException.java](native/src/main/java/io/ballerina/stdlib/workflow/runtime/DuplicateWorkflowException.java)

```java
public class DuplicateWorkflowException extends RuntimeException {
    private final String existingWorkflowId;
    
    public DuplicateWorkflowException(String message, String existingWorkflowId) {
        super(message);
        this.existingWorkflowId = existingWorkflowId;
    }
    
    public String getExistingWorkflowId() {
        return existingWorkflowId;
    }
}
```

### Ballerina Error Handling
```ballerina
string|error result = workflow:startProcess(orderProcess, input);

if result is error {
    if result.message().includes("DuplicateWorkflowError") {
        log:printError("Duplicate workflow detected");
        // Handle duplicate case
    } else {
        log:printError("Failed to start workflow", result);
    }
} else {
    log:printInfo("Workflow started: " + result);
}
```

## Success Criteria

✅ **Compile-Time Validation:**
- Process with events requires readonly fields (WORKFLOW_116 error if missing)
- Signal types have same readonly fields as input (WORKFLOW_114 if missing)
- Readonly field types match between input and signals (WORKFLOW_115 if mismatch)
- Input type must be record type (WORKFLOW_113 if not)

✅ **Runtime Behavior:**
- Workflow ID generated using UUID v7 (time-ordered, unique)
- Correlation keys extracted from readonly fields only
- Search Attributes registered with capitalized field names
- Duplicate detection prevents starting workflow with same correlation keys
- Signal routing finds workflows by correlation keys with retry
- After workflow completes, new workflow with same keys can start

✅ **Search Attributes:**
- Readonly fields stored as Temporal Search Attributes
- Automatic type inference (String, Long, Boolean, Double)
- Search Attributes upserted at workflow start
- Visibility queries return correct workflows
- Eventual consistency handled with retry (up to 10 retries, exponential backoff)

✅ **Signal Routing:**
- Signals find target workflow using correlation keys
- No need to know workflow ID when sending signals
- Retry logic handles eventual consistency
- Clear error when no workflow found for correlation keys
- Clear error when signal data has no readonly fields

✅ **Error Messages:**
- Duplicate workflow error includes existing workflow ID
- Missing correlation keys error suggests adding readonly fields
- Correlation key mismatch error shows field name and types
- Signal routing error shows correlation keys that didn't match
