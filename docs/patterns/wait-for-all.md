# Pattern: Wait for All (Collect Multiple Data)

When a workflow step requires input from **every** data source before it can proceed, wait for each future sequentially. The workflow resumes only after all expected data has arrived.

> **Runnable example:** [`examples/wait-for-all/`](../../examples/wait-for-all/) — a fund transfer requires authorization from both the Operations team and the Compliance team.

## When to Use

- Multiple independent parties must all weigh in before the workflow can continue (dual authorization, multi-party sign-off).
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

### Wait for All Data — Sequential Waits

```ballerina
// Notify both teams
check ctx->callActivity(notifyApprovalTeams, {
    "transferId": input.transferId,
    "amount": input.amount
});

// Wait for operations team
ApprovalDecision opsDecision = check wait events.operationsApproval;

// Wait for compliance team
ApprovalDecision compDecision = check wait events.complianceApproval;

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

**Order does not matter.** If the compliance team sends their decision before the operations team, the data is stored by the runtime. When the operations wait completes, the compliance wait resolves immediately because the data is already available.

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

- Between the two waits, the workflow is durable. If the worker restarts after receiving the first approval but before the second, the workflow replays and skips the already-completed first wait.
- Data sent while the workflow is paused is stored and delivered when the workflow reaches the corresponding `wait`.

## How It Differs from Alternative Wait

| | Wait for All | Alternative Wait |
|---|---|---|
| **Completes when** | **All** futures resolve | First `sendData` call arrives |
| **Data channels** | Separate channel per source | Single shared channel |
| **Use case** | Dual authorization, collect all inputs | Approval ladder, first-responder |
| **Subsequent sends** | Each consumed by its own `wait` | Silently ignored |

## What's Next

- [Alternative Wait](alternative-wait.md) — Proceed when the first of several data sources responds
- [Human in the Loop](human-in-the-loop.md) — Single-approver decision gate
- [Handle Data](../handle-data.md) — Full reference for receiving external data
