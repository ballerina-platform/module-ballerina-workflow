# Pattern: Wait for All (Collect Multiple Data)

When a workflow step requires input from **every** data source before it can proceed, use `ctx->await` to collect all futures in a single call. The workflow resumes only after all expected data has arrived.

> **Runnable example:** [`examples/wait-for-all/`](../../examples/wait-for-all/) — a fund transfer requires authorization from both the Operations team and the Compliance team.

## When to Use

- Multiple independent parties must all weigh in before the workflow can continue (dual authorization / Four-Eyes Principle, multi-party sign-off).
- You need to collect data from several sources and aggregate the results.
- Regulatory or business rules require approval from every designated authority.

## Code Pattern

### Declare the Data Types and Workflow Signature

```ballerina
type ApprovalDecision record {|
    string approverId;
    boolean approved;
    string? reason;
|};

@workflow:Workflow
function transferApproval(
    workflow:Context ctx,
    TransferInput input,
    record {|
        future<ApprovalDecision> operationsApproval;
        future<ApprovalDecision> complianceApproval;
    |} events
) returns TransferResult|error {
```

Each field represents a separate data channel. Both must deliver data before the workflow proceeds.

Current model note: the events record is statically typed, so channels are declared up front. Dynamic, variable-length approver sets are not first-class in this API today; they require an application-level aggregation strategy.

### Wait for All Data — `ctx->await`

```ballerina
// Notify both teams
check ctx->callActivity(notifyApprovalTeams, {
    "transferId": input.transferId,
    "amount": input.amount
});

// Wait for both teams — ctx->await blocks until all futures complete (default)
[ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] = check ctx->await(
    [events.operationsApproval, events.complianceApproval]
);

// Both must approve
if !opsDecision.approved {
    return {transferId: input.transferId, status: "REJECTED",
            message: "Rejected by Operations: " + (opsDecision.reason ?: "")};
}
if !compDecision.approved {
    return {transferId: input.transferId, status: "REJECTED",
            message: "Rejected by Compliance: " + (compDecision.reason ?: "")};
}

// Both approved — execute transfer
string txnRef = check ctx->callActivity(executeTransfer, {...});
return {transferId: input.transferId, status: "COMPLETED", message: txnRef};
```

`ctx->await` passes the full array of futures and waits for every one of them by default (equivalent to `minCount = array length`). The return type is inferred as a typed tuple — each element matches the `future<T>` type at the same index — so the destructured variables are already typed correctly without any casting.

**Order does not matter.** If the compliance team sends their decision before the operations team, the data is stored by the runtime and `ctx->await` resolves as soon as the last outstanding future completes.

**With a deadline**, pass a `timeout` to get an error if not all data arrives in time:

```ballerina
[ApprovalDecision, ApprovalDecision] [opsDecision, compDecision] =
    check ctx->await(
        [events.operationsApproval, events.complianceApproval],
        timeout = {hours: 48}
    );
```

### Send Data from HTTP Endpoints

Each team has its own endpoint:

```ballerina
service /api on new http:Listener(8090) {
    resource function post transfers/[string workflowId]/operationsApproval(
            ApprovalDecision decision) returns json|error {
        check workflow:sendData(transferApproval, workflowId, "operationsApproval", decision);
        return {status: "received"};
    }

    resource function post transfers/[string workflowId]/complianceApproval(
            ApprovalDecision decision) returns json|error {
        check workflow:sendData(transferApproval, workflowId, "complianceApproval", decision);
        return {status: "received"};
    }
}
```

## Durability While Paused

- The workflow is durable while `ctx->await` is blocking. If the worker restarts after receiving the first approval but before the second, the workflow replays and `ctx->await` resumes from where it left off without re-requesting already-received data.
- Data sent while the workflow is paused is stored and delivered when `ctx->await` evaluates the condition.

## How It Differs from Alternative Wait

| | Wait for All | Alternative Wait |
|---|---|---|
| **Syntax** | `ctx->await([f1, f2])` | `wait events.approval` |
| **Completes when** | **All** futures resolve | First `sendData` call arrives |
| **Result shape** | `[T1, T2]` — all populated | `T` — the decision value |
| **Data channels** | Separate channel per source | Single shared channel |
| **Use case** | Dual authorization (Four-Eyes Principle), collect all inputs | Approval ladder, first-responder |
| **Subsequent sends** | Each consumed by its own future | Silently ignored |

Both mechanisms are fully durable. Use `wait` for simple single-channel first-wins scenarios; use `ctx->await` when you need richer controls such as `timeout`.

## What's Next

- [Alternative Wait](alternative-wait.md) — Proceed when the first of several data sources responds
- [Human in the Loop](human-in-the-loop.md) — Single-approver decision gate
- [Handle Data](../handle-data.md) — Full reference for receiving external data
