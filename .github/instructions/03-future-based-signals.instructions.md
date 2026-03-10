# Future-Based Signal Handling

applyTo: "**/*.bal,**/TemporalFutureValue.java,**/EventFutureCreator.java,**/SignalAwaitWrapper.java"

---

## Overview

The workflow module implements a **Future-based approach** where signal listeners are injected into the workflow function as a record of `future<T>`. This allows Ballerina developers to use the native `wait` keyword to suspend execution until a signal arrives.

## Current Implementation

### 1. Ballerina Layer

Workflows receive signals via a record parameter with `future<T>` fields. Field names directly map to Temporal signal names. See examples in [integration-tests/](integration-tests/) (e.g., workflow files using events records).

### 2. Native Layer

#### TemporalFutureValue.java
Location: [TemporalFutureValue.java](native/src/main/java/io/ballerina/stdlib/workflow/context/TemporalFutureValue.java)

**Purpose**: Bridges Temporal's `CompletablePromise` to Ballerina's `FutureValue`, enabling use of Ballerina's `wait` keyword without triggering Temporal's deadlock detection.

**The Deadlock Problem**: Ballerina's `wait` action calls `future.getAndSetWaited()` and then `completableFuture.get()` which blocks the Java thread, triggering Temporal's `PotentialDeadlockException`. Temporal expects `Workflow.await()` for cooperative yielding during replay.

**The Solution**: `TemporalFutureValue` extends `FutureValue` and overrides:
- `getAndSetWaited()` — intercepts before Ballerina calls `completableFuture.get()`, calls `Workflow.await(() -> completableFuture.isDone())` for Temporal-safe cooperative blocking, returns `false` to allow multiple waits
- `setupPromiseCallback()` — when signal arrives via `CompletablePromise`, converts data to Ballerina type via `TypesUtil` and completes the Java `CompletableFuture`

#### EventFutureCreator.java
Location: [EventFutureCreator.java](native/src/main/java/io/ballerina/stdlib/workflow/utils/EventFutureCreator.java)

**Purpose**: Creates the events record with `TemporalFutureValue` objects for each signal field.
- `createEventsRecord(RecordType, SignalAwaitWrapper, Scheduler)` — iterates over record fields, creates a `TemporalFutureValue` for each, returns a populated `BMap` record

#### SignalAwaitWrapper.java
Location: [SignalAwaitWrapper.java](native/src/main/java/io/ballerina/stdlib/workflow/context/SignalAwaitWrapper.java)

**Purpose**: Manages Temporal signal registration and promise lifecycle.
- `registerSignal(String signalName)` — creates a `Workflow.newPromise()` and stores it
- `recordSignal(String signalName, Object data)` — completes the promise when signal arrives

#### BallerinaWorkflowAdapter Signal Registration
In [WorkflowWorkerNative.java](native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java):
- Constructor creates `SignalAwaitWrapper` and registers a `DynamicSignalHandler` with Temporal
- `execute()` checks `EVENT_REGISTRY` for event names, uses `EventFutureCreator.createEventsRecord()` to build the events record, passes it to the Ballerina function

### 3. Compiler Plugin Layer

The compiler plugin (in [WorkflowValidatorTask.java](compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowValidatorTask.java)) validates the events parameter signature — the third parameter must be a record type where all fields are `future<anydata>` subtypes.

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
   └─> workflow:sendData(processFunc, workflowId, "approval", eventData)
       └─> Temporal delivers signal → DynamicSignalHandler.signal()
           └─> signalWrapper.recordSignal("approval", data)
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

- Process functions with events parameter compile successfully
- Events record type validated (all fields must be `future<anydata>` subtypes)
- `wait events.fieldName` successfully blocks until signal arrives
- Signal data is correctly converted to Ballerina type matching `future<T>` constraint
- Multiple signals can be waited in any order
- No `PotentialDeadlockException` from Temporal
- Signals replay deterministically (same order, same values)
- `Workflow.await()` properly yields during replay
- Completed signals return immediately during replay (no re-waiting)

✅ **Error Handling:**
- Signal data type mismatch produces clear error
- Waiting for non-existent signal field produces compiler error
- Signal promise failures propagate to Ballerina error

✅ **Integration:**
- `sendData()` successfully delivers signals to running workflows
- Explicit `dataName` parameter specifies which signal to deliver
- Signals work correctly with correlation (readonly fields)
