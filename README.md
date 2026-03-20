# Ballerina Workflow Library

[![Build](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-timestamped-master.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-timestamped-master.yml)
[![codecov](https://codecov.io/gh/ballerina-platform/module-ballerina-workflow/branch/main/graph/badge.svg)](https://codecov.io/gh/ballerina-platform/module-ballerina-workflow)
[![Trivy](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/trivy-scan.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/trivy-scan.yml)
[![GraalVM Check](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-with-bal-test-graalvm.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-with-bal-test-graalvm.yml)
[![GitHub Last Commit](https://img.shields.io/github/last-commit/ballerina-platform/module-ballerina-workflow.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/commits/main)
[![Github issues](https://img.shields.io/github/issues/ballerina-platform/ballerina-library/Area%2FWorkflow.svg?label=Open%20Issues)](https://github.com/ballerina-platform/ballerina-library/labels/Area%2Fworkflow)

This library provides durable, fault-tolerant workflow orchestration for Ballerina applications. It lets you define long-running business processes — spanning minutes, hours, or days — that automatically recover from crashes and process restarts without losing progress.

## Overview

Workflows and activities are ordinary Ballerina functions:

- **`@workflow:Workflow`** — A durable function that orchestrates a business process. The runtime checkpoints every step and replays recorded history to recover from failures.
- **`@workflow:Activity`** — A function that performs a single non-deterministic operation (API call, database query, email send). Once an activity completes, its result is recorded and never re-executed during replay.

```ballerina
import ballerina/workflow;

@workflow:Activity
function checkInventory(string item) returns boolean|error {
    // Call external inventory API
    return true;
}

@workflow:Workflow
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    boolean inStock = check ctx->callActivity(checkInventory, {"item": request.item});
    if !inStock {
        return {orderId: request.orderId, status: "OUT_OF_STOCK"};
    }
    return {orderId: request.orderId, status: "COMPLETED"};
}
```

## Starting a Workflow

Use `workflow:run()` to start a workflow instance from any entry point — HTTP service, scheduled job, message consumer, or `main`:

```ballerina
string workflowId = check workflow:run(processOrder, {orderId: "ORD-001", item: "laptop"});
```

## Receiving External Data

A workflow can pause and wait for external input — approvals, payment confirmations, user decisions — using future-based event records. Send data to a running workflow with `workflow:sendData()`:

```ballerina
// In the workflow — wait for a human decision
ApprovalDecision decision = check wait events.approval;

// From outside — deliver the decision
check workflow:sendData(processOrder, workflowId, "approval", {approverId: "mgr-1", approved: true});
```

### Multi-Future Waits with `ctx->await`

Use `ctx->await` to wait for multiple futures at once, with optional quorum and timeout:

| Pattern | Example |
|---------|---------|
| Wait for all | `ctx->await([f1, f2])` |
| Wait for any (first wins) | `ctx->await([f1, f2], 1)` |
| Quorum (N of M) | `ctx->await([f1, f2, f3], 2)` |
| With deadline | `ctx->await([f1, f2], timeout = {hours: 48})` |

## Error Handling

Activity errors are returned as plain Ballerina values. The workflow decides what happens next:

```ballerina
string|error result = ctx->callActivity(chargeCard, {"amount": input.amount});
if result is error {
    // retry with a different card, fall back, or compensate
}
```

Enable automatic retries for transient failures:

```ballerina
string result = check ctx->callActivity(chargeCard, {"amount": input.amount},
    retryOnError = true, maxRetries = 3);
```

## Configuration

Add a `Config.toml` to your project. For local development with no server:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"
```

For production, connect to a Temporal server:

```toml
[ballerina.workflow]
mode = "TEMPORAL"
temporalHost = "localhost"
temporalPort = 7233
namespace = "default"
taskQueue = "my-task-queue"
```

## Documentation

| Guide | Description |
|-------|-------------|
| [Get Started](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/get-started.md) | Write and run your first workflow |
| [Key Concepts](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/key-concepts.md) | Workflows, activities, external data, and timers |
| [Write Workflow Functions](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/write-workflow-functions.md) | Signatures, determinism rules, and durable sleep |
| [Write Activity Functions](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/write-activity-functions.md) | Activity patterns and retry options |
| [Handle Data](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/handle-data.md) | Waiting for external input and sending data |
| [Handle Errors](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/handle-errors.md) | Propagation, retry, fallback, and compensation |
| [Configure the Module](https://github.com/ballerina-platform/module-ballerina-workflow/blob/main/docs/configure-the-module.md) | Connection settings, TLS, and namespaces |

## Examples

| Example | Description |
|---------|-------------|
| [Get Started](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/get-started) | First workflow |
| [Order Processing](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/order-processing) | HTTP-triggered workflow with result polling |
| [Human in the Loop](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/human-in-the-loop) | Pause for a human approval |
| [Wait for All](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/wait-for-all) | Dual authorization — both teams must approve |
| [Alternative Wait](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/alternative-wait) | First responder wins (approval ladder) |
| [Forward Recovery](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/forward-recovery) | Pause for corrected data and retry |
| [Error Propagation](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-propagation) | Fail the workflow on a critical error |
| [Error Fallback](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-fallback) | Fall back to a secondary activity |
| [Error Compensation](https://github.com/ballerina-platform/module-ballerina-workflow/tree/main/examples/error-compensation) | Saga: undo committed steps on failure |

## Issues and projects

Issues and Projects tabs are disabled for this repository as this is part of the Ballerina Standard Library. To report bugs, request new features, start new discussions, view project boards, etc., please visit Ballerina Standard Library [parent repository](https://github.com/ballerina-platform/ballerina-standard-library).

This repository only contains the source code for the package.

## Build from the source

### Set up the prerequisites

1. Download and install Java SE Development Kit (JDK) version 21 (from one of the following locations).

   * [Oracle](https://www.oracle.com/java/technologies/downloads/)
   * [OpenJDK](https://adoptium.net/)

   > **Note:** Set the `JAVA_HOME` environment variable to the path name of the directory into which you installed JDK.

2. Export GitHub Personal Access Token with read package permissions as follows:

   ```bash
   export packageUser=<Username>
   export packagePAT=<Personal access token>
   ```

3. Download and install [Docker](https://www.docker.com/) and Docker Compose (required for integration tests).

### Build the source

Execute the commands below to build from source.

1. To build the library:

   ```bash
   ./gradlew clean build
   ```

2. To run the integration tests:

   ```bash
   ./gradlew clean test
   ```

3. To run a group of tests:

   ```bash
   ./gradlew clean test -Pgroups=<test_group_names>
   ```

4. To build the package without the tests:

   ```bash
   ./gradlew clean build -x test
   ```

5. To debug the tests:

   ```bash
   ./gradlew clean test -Pdebug=<port>
   ```

6. To debug with Ballerina language:

   ```bash
   ./gradlew clean build -PbalJavaDebug=<port>
   ```

7. Publish the generated artifacts to the local Ballerina central repository:

   ```bash
   ./gradlew clean build -PpublishToLocalCentral=true
   ```

8. Publish the generated artifacts to the Ballerina central repository:

   ```bash
   ./gradlew clean build -PpublishToCentral=true
   ```

## Contribute to Ballerina

As an open source project, Ballerina welcomes contributions from the community.

For more information, go to the [contribution guidelines](https://github.com/ballerina-platform/ballerina-lang/blob/master/CONTRIBUTING.md).

## Code of conduct

All contributors are encouraged to read the [Ballerina Code of Conduct](https://ballerina.io/code-of-conduct).

## Useful links

* For more information go to the [Workflow library](https://lib.ballerina.io/ballerina/workflow/latest).
* For example demonstrations of the usage, go to [Ballerina By Examples](https://ballerina.io/learn/by-example/).
* Chat live with us via our [Discord server](https://discord.gg/ballerinalang).
* Post all technical questions on Stack Overflow with the [#ballerina](https://stackoverflow.com/questions/tagged/ballerina) tag.
