# Future-Based Signal Handling

applyTo: "**/*.bal,**/TemporalFutureValue.java,**/EventFutureCreator.java,**/SignalAwaitWrapper.java"

---

## Overview

The workflow module implements a **Future-based approach** where signal listeners are injected into the workflow function as a record of `future<T>`. This allows Ballerina developers to use the native `wait` keyword to suspend execution until a signal arrives.

## Current Implementation

### 1. Ballerina Layer

#### User Experience
Workflows receive signals via a record parameter with `future<T>` fields:

```ballerina
@workflow:Process
function orderProcess(
    workflow:Context ctx,
    OrderInput input,
    // Framework injects futures automatically based on field names
    record {|
        future<ApprovalSignal> approval;  // Field name = signal name
        future<PaymentSignal> payment;
    |} events
) returns OrderResult|error {
    // Wait for approval signal (field name maps to Temporal signal name)
    ApprovalSignal decision = check wait events.approval;

    if decision.status {
        // Wait for payment only if approved
        PaymentSignal payParams = check wait events.payment;
        return processPayment(payParams);
    }

    return { status: "REJECTED" };
}
```

#### Signal Types
```ballerina
type ApprovalSignal record {|
    string approverId;
    boolean status;
|};

type PaymentSignal record {|
    string txnId;
    decimal amount;
|};
```

### 2. Native Layer

#### TemporalFutureValue.java
Location: [TemporalFutureValue.java](native/src/main/java/io/ballerina/stdlib/workflow/context/TemporalFutureValue.java)

**Purpose**: Bridges Temporal's `CompletablePromise` to Ballerina's `FutureValue`, enabling use of Ballerina's `wait` keyword without triggering Temporal's deadlock detection.

**The Deadlock Problem:**
```
Ballerina's wait action:
1. Calls future.getAndSetWaited()
2. Directly calls future.completableFuture.get() → BLOCKS Java thread!

Problem: CompletableFuture.get() blocks, triggering Temporal's PotentialDeadlockException
Temporal expects: Workflow.await() for cooperative yielding during replay
```

**The Solution:**
```java
public class TemporalFutureValue extends FutureValue {
    private final CompletablePromise<SignalData> promise;
    private final String signalName;
    private final Type constraintType;

    // Intercept BEFORE Ballerina calls completableFuture.get()
    @Override
    public boolean getAndSetWaited() {
        // Use Temporal's await (cooperative blocking, replay-safe)
        ensureSignalReady();
        return false;  // Allow multiple waits
    }
    
    private void ensureSignalReady() {
        if (!this.completableFuture.isDone()) {
            LOGGER.debug("Waiting for signal '{}' using Temporal await", signalName);
            // This yields control properly during replay
            Workflow.await(() -> this.completableFuture.isDone());
        }
    }
    
    // Set up callback to complete Java CompletableFuture when signal arrives
    private void setupPromiseCallback() {
        promise.thenApply(signalData -> {
            Object ballerinaData = TypesUtil.convertJavaToBallerinaType(signalData.getData());
            Object result = TypesUtil.cloneWithType(ballerinaData, constraintType);
            this.completableFuture.complete(result);  // Unblock wait
            return result;
        });
    }
}
```

**Key Design Points:**
1. **Intercepts `getAndSetWaited()`**: Called by Ballerina's `AsyncUtils.handleWait()` before accessing `completableFuture`
2. **Uses `Workflow.await()`**: Temporal-safe blocking that yields during replay
3. **Completes CompletableFuture**: When signal arrives, callback completes the Java future
4. **Non-blocking get()**: By the time `completableFuture.get()` is called, future is already complete

#### EventFutureCreator.java
Location: [EventFutureCreator.java](native/src/main/java/io/ballerina/stdlib/workflow/utils/EventFutureCreator.java)

**Purpose**: Creates the events record with `TemporalFutureValue` objects for each signal field.

```java
public class EventFutureCreator {
    public static BMap<BString, Object> createEventsRecord(
            RecordType eventsRecordType,
            SignalAwaitWrapper signalWrapper,
            Scheduler scheduler) {

        Map<String, Field> fields = eventsRecordType.getFields();
        BMap<BString, Object> eventsRecord = ValueCreator.createRecordValue(eventsRecordType);

        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            Type constraintType = extractConstraintType(field.getFieldType());

            // Create TemporalFutureValue for this signal
            TemporalFutureValue futureValue = createTemporalFutureValue(
                fieldName, signalWrapper, constraintType, scheduler);

            // Add to events record
            eventsRecord.put(StringUtils.fromString(fieldName), futureValue);
        }

        return eventsRecord;
    }

    private static TemporalFutureValue createTemporalFutureValue(
            String signalName,
            SignalAwaitWrapper signalWrapper,
            Type constraintType,
            Scheduler scheduler) {
        
        // Create Temporal CompletablePromise
        CompletablePromise<SignalData> promise = signalWrapper.registerSignal(signalName);
        
        // Wrap in TemporalFutureValue
        return new TemporalFutureValue(promise, signalName, constraintType, scheduler);
    }
}
```

#### SignalAwaitWrapper.java
Location: [SignalAwaitWrapper.java](native/src/main/java/io/ballerina/stdlib/workflow/context/SignalAwaitWrapper.java)

**Purpose**: Manages Temporal signal registration and promise lifecycle.

```java
public class SignalAwaitWrapper {
    private final Map<String, CompletablePromise<SignalData>> signalPromises = new HashMap<>();

    public CompletablePromise<SignalData> registerSignal(String signalName) {
        CompletablePromise<SignalData> promise = Workflow.newPromise();
        signalPromises.put(signalName, promise);
        return promise;
    }

    public void handleSignal(String signalName, Object data) {
        CompletablePromise<SignalData> promise = signalPromises.get(signalName);
        if (promise != null && !promise.isCompleted()) {
            promise.complete(new SignalData(signalName, data));
        }
    }

    public record SignalData(String signalName, Object data) {
    }
}
```

#### BallerinaWorkflowAdapter Signal Registration
In [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java):

```java
public static class BallerinaWorkflowAdapter implements DynamicWorkflow {
    private final SignalAwaitWrapper signalWrapper;
    
    public BallerinaWorkflowAdapter() {
        this.signalWrapper = new SignalAwaitWrapper();
        
        // Register dynamic signal handler
        Workflow.registerListener(new DynamicSignalHandler() {
            @Override
            public void signal(String signalName, EncodedValues encodedArgs) {
                Object[] args = extractArgs(encodedArgs);
                Object data = args.length > 0 ? args[0] : null;
                signalWrapper.handleSignal(signalName, data);
            }
        });
    }
    
    @Override
    public Object execute(EncodedValues args) {
        // 1. Get process function from registry
        String workflowType = Workflow.getInfo().getWorkflowType();
        BFunctionPointer processFunc = PROCESS_REGISTRY.get(workflowType);
        
        // 2. Extract input data
        Object input = extractWorkflowInput(args);
        
        // 3. Create Context
        BObject context = createWorkflowContext();
        
        // 4. Check if process has events parameter
        List<String> eventNames = EVENT_REGISTRY.get(workflowType);
        BMap<BString, Object> eventsRecord = null;
        
        if (eventNames != null && !eventNames.isEmpty()) {
            // Get events record type from function signature
            RecordType eventsType = extractEventsRecordType(processFunc);
            // Create events record with TemporalFutureValue objects
            eventsRecord = EventFutureCreator.createEventsRecord(
                eventsType, signalWrapper, ballerinaRuntime.getScheduler());
        }
        
        // 5. Invoke Ballerina function
        Object[] ballerinaArgs = eventsRecord != null
            ? new Object[]{context, input, eventsRecord}
            : new Object[]{context, input};
            
        return processFunc.call(ballerinaRuntime, ballerinaArgs);
    }
}
```

### 3. Compiler Plugin Layer

The compiler plugin has **no direct involvement** in signal handling - it only validates the events parameter signature:

```java
// WorkflowValidatorTask.java
private void validateProcessFunction(FunctionDefinitionNode node, SyntaxNodeAnalysisContext context) {
    // Validate: (Context?, anydata, record{future<T>...}?) signature
    // Third parameter must be record type with future fields
    if (hasEventsParameter) {
        TypeSymbol eventsType = getEventsParameterType();
        if (eventsType.typeKind() == TypeDescKind.RECORD) {
            RecordTypeSymbol recordType = (RecordTypeSymbol) eventsType;
            // Validate all fields are future<anydata> subtypes
        }
    }
}
```

## Execution Flow

```
1. Workflow Start
   └─> BallerinaWorkflowAdapter constructor
       ├─> Creates SignalAwaitWrapper
       └─> Registers DynamicSignalHandler with Temporal

2. Workflow Execution Begins
   └─> BallerinaWorkflowAdapter.execute()
       ├─> Check if process has events (EVENT_REGISTRY)
       └─> If yes:
           ├─> Get events record type from function signature
           ├─> EventFutureCreator.createEventsRecord()
           │   └─> For each field: create TemporalFutureValue
           │       ├─> SignalAwaitWrapper.registerSignal() → CompletablePromise
           │       └─> new TemporalFutureValue(promise, signalName, type)
           └─> Pass eventsRecord to Ballerina function

3. Ballerina Wait Action
   └─> wait events.approval
       ├─> AsyncUtils.handleWait(strand, future)
       ├─> future.getAndSetWaited() → TemporalFutureValue intercepts
       │   └─> Workflow.await(() -> completableFuture.isDone())  ← Yields properly
       └─> completableFuture.get() → Returns immediately (already done)

4. Signal Arrives (External)
   └─> workflow:sendEvent(processFunc, eventData, "approval")
       └─> Temporal delivers signal → DynamicSignalHandler.signal()
           └─> signalWrapper.handleSignal("approval", data)
               ├─> promise.complete(SignalData) → Temporal promise complete
               └─> promise.thenApply() callback
                   ├─> Convert data to Ballerina type
                   └─> completableFuture.complete(result)  ← Unblocks wait
```

## Key Design Points

1. **Field Name = Signal Name**: Record field names directly map to Temporal signal names
2. **Type Safety**: Each future's constraint type ensures proper signal data conversion
3. **Replay Safety**: `Workflow.await()` ensures deterministic behavior during replay
4. **No Deadlocks**: Avoids `CompletableFuture.get()` blocking by using `Workflow.await()` first
5. **Multiple Waits**: `getAndSetWaited()` returns `false` to allow multiple waits on same signal
6. **Lazy Evaluation**: Futures only block when waited on, not when created

## Success Criteria

✅ **Compilation:**
- Process functions with events parameter compile successfully
- Events record type validated (all fields must be `future<anydata>` subtypes)
- Signal field names are valid identifiers

✅ **Runtime Behavior:**
- `wait signals.fieldName` successfully blocks until signal arrives
- Signal data is correctly converted to Ballerina type matching `future<T>` constraint
- Multiple signals can be waited in any order
- Waiting for same signal multiple times returns same value
- No `PotentialDeadlockException` from Temporal
- No `UnableToAcquireLockException` from Ballerina

✅ **Replay Behavior:**
- Signals replay deterministically (same order, same values)
- `Workflow.await()` properly yields during replay
- Completed signals return immediately during replay (no re-waiting)

✅ **Error Handling:**
- Signal data type mismatch produces clear error
- Waiting for non-existent signal field produces compiler error
- Signal promise failures propagate to Ballerina error

✅ **Integration:**
- `sendEvent()` successfully delivers signals to running workflows
- Field name inference works for single-signal workflows
- Explicit `signalName` parameter works for ambiguous cases
- Signals work correctly with correlation (readonly fields)
