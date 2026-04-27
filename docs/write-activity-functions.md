# Write Activity Functions

Activity functions are the units of work that workflows orchestrate. They can perform any operation — deterministic or not — including I/O, API calls, database access, computation, and other side effects. The workflow engine records each activity's result, so that on recovery it can skip completed activities and continue from where the workflow left off. If you are new to workflows, start with [Key Concepts](key-concepts.md) first.

## What Activities Should Do

Activities are the right place for:

- HTTP/API calls to external services
- Database manipulations (queries, inserts, updates, deletes)
- File system operations
- Sending emails or notifications
- Any operation with side effects

## What Activities Should Not Do

- **Call other activities** — Activities are flat, not nested. The engine tracks activity calls made from a workflow function via `ctx->callActivity()`. An activity calling another activity directly would bypass this tracking, so the inner call would not be recorded and could re-execute on recovery.
- **Run workflow orchestration logic** — Decisions about what to do next (branching, looping over steps, waiting for data) belong in the workflow function, where the engine can track them. Putting orchestration logic inside an activity hides it from the engine.
- **Access workflow context methods** — Methods like `ctx.sleep()`, `ctx.currentTime()`, and `ctx->callActivity()` are only available in workflow functions. Activities run outside the workflow scheduler and do not have access to the workflow context.

## Define an Activity

Annotate a function with `@workflow:Activity`. Activity functions can be defined in the same file as the workflow, in a separate file within the same module, or even in a different Ballerina module. This makes it easy to share common activities across multiple workflows.

```ballerina
import ballerina/workflow;

@workflow:Activity
function sendEmail(string to, string subject) returns boolean|error {
    // Call external email service
    return true;
}
```

## Function Signature

```ballerina
@workflow:Activity
function <name>(<params...>) returns <ReturnType>|error { }
```

### Parameter and Return Type Rules

- All parameters must be subtypes of `anydata` (e.g., `string`, `int`, `decimal`, `record`, `map`, `array`). The compiler also permits the special parameter pattern `typedesc<anydata> t = <>` (see WORKFLOW_114), which is exempt from the strict `anydata`-subtype rule.
- The return type must be a subtype of `anydata` or `error`
- Activities can return `error?` for operations that don't produce a value

## Call Activities from Workflows

Activities must be called using `ctx->callActivity()` within a workflow. Pass arguments as a `map<anydata>` where keys match the parameter names:

```ballerina
@workflow:Workflow
function orderProcess(workflow:Context ctx, OrderInput input) returns OrderResult|error {
    // Call activity with named arguments
    InventoryStatus status = check ctx->callActivity(checkInventory, {
        "item": input.item,
        "quantity": input.quantity
    });

    if status.inStock {
        check ctx->callActivity(reserveStock, {
            "orderId": input.orderId,
            "item": input.item,
            "quantity": input.quantity
        });
    }

    return {orderId: input.orderId, status: status.inStock ? "RESERVED" : "OUT_OF_STOCK"};
}

@workflow:Activity
function checkInventory(string item, int quantity) returns InventoryStatus|error {
    // Query inventory system
}

@workflow:Activity
function reserveStock(string orderId, string item, int quantity) returns error? {
    // Reserve in inventory system
}
```

## Compile-Time Validation

The compiler enforces proper usage of workflows and activities:

### Workflow function rules

| Error Code | Rule |
|------------|------|
| **WORKFLOW_100** | Context parameter must be typed as `workflow:Context` |
| **WORKFLOW_101** | Input parameter must be a subtype of `anydata` |
| **WORKFLOW_102** | Events parameter must be a record with only `future<T>` fields |
| **WORKFLOW_105** | Return type must be a subtype of `anydata` or `error` |
| **WORKFLOW_106** | Maximum 3 parameters allowed (Context, input, events) |
| **WORKFLOW_113** | Using `time:utcNow()` inside a `@Workflow` function is non-deterministic; use `ctx.currentTime()` instead |

### Activity function rules

| Error Code | Rule |
|------------|------|
| **WORKFLOW_103** | All parameters must be subtypes of `anydata` |
| **WORKFLOW_104** | Return type must be a subtype of `anydata` or `error` |
| **WORKFLOW_114** | `typedesc` parameter must use inferred default `<>` with constraint `anydata` |

### callActivity usage rules

| Error Code | Rule |
|------------|------|
| **WORKFLOW_107** | `ctx->callActivity()` target must be an `@Activity`-annotated function |
| **WORKFLOW_108** | Direct calls to `@Activity` functions inside `@Workflow` functions are not allowed |
| **WORKFLOW_109** | Missing required parameter in `callActivity` args map |
| **WORKFLOW_110** | Extra parameter in `callActivity` that the activity function does not have |
| **WORKFLOW_111** | Activity functions with rest parameters are not supported with `callActivity` |

```ballerina
@workflow:Workflow
function myWorkflow(workflow:Context ctx, Input input) returns Output|error {
    // WORKFLOW_107 — target is not annotated with @Activity
    check ctx->callActivity(someRegularFunction, {});

    // WORKFLOW_108 — direct call not allowed
    check sendEmail(input.email, "Hello");
}
```

These restrictions keep activity boundaries explicit and statically analyzable. In particular, requiring `@workflow:Activity` on `ctx->callActivity()` targets makes the runtime model and tooling behavior predictable.

## Idempotency

The engine records each activity result exactly once per `callActivity()` invocation. In most cases that means an activity executes exactly once. However, it can execute **more than once** for the same call in certain edge cases — for example, if a worker crashes after the activity finishes but before the engine persists the result.

The practical rule is:

- If the engine has already recorded the activity result, recovery returns that recorded result and the activity is not run again.
- If the activity finished but the result was not durably recorded before the failure, the engine may schedule the activity again according to recovery behavior and any configured retry policy.

Because of this, activity implementations should aim to be **idempotent**: producing the same observable outcome if called again with the same inputs. In practice, full idempotency can be difficult to achieve. Common strategies include:

- **Idempotency keys** — Pass a unique identifier (e.g., `orderId`, `requestId`) to external APIs when available. Most payment gateways and messaging systems use this to detect and discard duplicate requests.
- **Check-before-write** — Wrap database operations in a transaction that checks whether the operation already completed before applying changes (e.g., `INSERT ... WHERE NOT EXISTS`).
- **Naturally idempotent operations** — Design activities so repeating them is harmless: prefer upserts over inserts, and reads are always safe.

> **Hard cases:** Some situations are inherently difficult to make idempotent. A common example is a database transaction that commits on the remote side but the acknowledgment never arrives due to a network error. The activity sees a failure, but the effect already happened. These cases require careful system-level design — for example, reconciliation jobs, compensating activities, or relying on the remote system's own idempotency support. See [Error Compensation](patterns/error-compensation.md) for patterns that address this.

## Activity Options

`callActivity` accepts named options after the args map. All fields from `ActivityOptions` can be passed directly as named arguments.

### Default behaviour — errors as values

By default (`retryOnError = false`), any error returned by the activity is handed back to the workflow as a normal return value. No automatic retries occur.

```ballerina
// Default: error returned as a value — workflow handles it
string|error result = ctx->callActivity(riskyActivity, {"data": input.data});
if result is error {
    return "Handled: " + result.message();
}
```

### Opt-in retries

Pass `retryOnError = true` to enable automatic retries. When all retries are exhausted the error propagates and the workflow fails unless caught.

```ballerina
// 3 retries, 2-second initial delay, 1.5x backoff
string result = check ctx->callActivity(sendEmail,
    {"to": email, "subject": subject},
    retryOnError = true, maxRetries = 3, retryDelay = 2.0, retryBackoff = 1.5);
```

### `ActivityOptions` reference

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `retryOnError` | `boolean` | `false` | Enable automatic retries on failure |
| `maxRetries` | `int` | `0` | Number of retry attempts (`0` = no retries; only used when `retryOnError = true`) |
| `retryDelay` | `decimal` | `1.0` | Initial delay in seconds before the first retry |
| `retryBackoff` | `decimal` | `2.0` | Multiplier applied to `retryDelay` after each attempt (`1.0` = fixed interval) |
| `maxRetryDelay` | `decimal?` | — | Cap on the delay between retries in seconds (optional) |


## What's Next

- [Handle Data](handle-data.md) — Receive external data in running workflows
- [Handle Errors](handle-errors.md) — Error handling patterns
