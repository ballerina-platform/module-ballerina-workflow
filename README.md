# Ballerina Workflow Library

[![Build](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-timestamped-master.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-timestamped-master.yml)
[![codecov](https://codecov.io/gh/ballerina-platform/module-ballerina-workflow/branch/main/graph/badge.svg)](https://codecov.io/gh/ballerina-platform/module-ballerina-workflow)
[![Trivy](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/trivy-scan.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/trivy-scan.yml)
[![GraalVM Check](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-with-bal-test-graalvm.yml/badge.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/actions/workflows/build-with-bal-test-graalvm.yml)
[![GitHub Last Commit](https://img.shields.io/github/last-commit/ballerina-platform/module-ballerina-workflow.svg)](https://github.com/ballerina-platform/module-ballerina-workflow/commits/main)
[![Github issues](https://img.shields.io/github/issues/ballerina-platform/ballerina-library/Area%2FWorkflow.svg?label=Open%20Issues)](https://github.com/ballerina-platform/ballerina-library/labels/Area%2Fworkflow)

This library provides APIs for building durable workflow orchestrations in Ballerina. It integrates with [Temporal.io](https://www.temporal.io/) to enable reliable, long-running workflows with built-in fault tolerance and state management.

## Overview

The Workflow library facilitates building stateful, durable workflows that can survive process failures and restarts. It provides:

- **Process Functions**: Durable workflow orchestration with automatic state persistence
- **Activity Functions**: Reliable execution of side effects functions (I/O, API calls, etc.)
- **Event/Signal Support**: Future-based event handling with correlation
- **Compiler Plugin**: Compile-time validation and code generation to ensure workflow determinism

### Key Features

#### @Process Annotation

Marks a function as a workflow process. Process functions define the workflow orchestration logic:

```ballerina
@workflow:Process
function processOrder(workflow:Context ctx, OrderRequest request) returns OrderResult|error {
    // Deterministic workflow orchestration logic
    InventoryStatus inventory = check ctx->callActivity(checkInventory, {item: request.item, quantity: request.quantity});
    
    if inventory.available {
        string reservationId = check ctx->callActivity(reserveStock, {orderId: request.orderId, item: request.item});
        return {status: "completed", reservationId};
    }
    return {status: "insufficient_stock"};
}
```

#### @Activity Annotation

Marks a function as a workflow activity. Activities handle non-deterministic operations like I/O, external API calls, and side effects:

```ballerina
@workflow:Activity
function checkInventory(string item, int quantity) returns InventoryStatus|error {
    // External system calls, database operations, API invocations
    return database->query(`SELECT * FROM inventory WHERE item = ${item}`);
}
```

**Important**: Activities must be called via `ctx->callActivity()` within process functions. Direct activity calls are not allowed and will produce a compiler error.

#### Context Client Class

The `workflow:Context` provides workflow execution capabilities:

- `callActivity()` - Execute activities with automatic retry and result caching
- `sleep()` - Durable delays that survive process restarts
- `isReplaying()` - Detect if workflow is replaying from history
- `getWorkflowId()` - Get the unique workflow execution ID
- `getWorkflowType()` - Get the workflow type name

#### Event Handling

Workflows can wait for external signals using future-based events with correlation:

```ballerina
public type OrderInput record {|
    readonly string orderId;  // Correlation key
    string item;
|};

public type PaymentEvent record {|
    readonly string orderId;  // Matches correlation key
    decimal amount;
|};

@workflow:Process
function processOrderWithPayment(
    workflow:Context ctx, 
    OrderInput input,
    record {| future<PaymentEvent> payment; |} events
) returns OrderResult|error {
    // Check inventory
    check ctx->callActivity(checkInventory, {item: input.item, quantity: input.quantity});
    
    // Wait for payment signal
    PaymentEvent payment = check wait events.payment;
    
    // Complete order
    return {status: "paid", amount: payment.amount};
}
```

## Configuration

### Connecting to Temporal Server

The Workflow library requires a running Temporal server. You can configure the connection using a `Config.toml` file in your project root:

```toml
[ballerina.workflow.workflowConfig]
provider = "TEMPORAL"
url = "localhost:7233"
namespace = "default"

[ballerina.workflow.workflowConfig.params]
taskQueue = "MY_TASK_QUEUE"
maxConcurrentWorkflows = 100
```

**Configuration Parameters:**
- `provider` - Workflow provider (currently only "TEMPORAL" is supported)
- `url` - Temporal server address (default: `localhost:7233`)
- `namespace` - Temporal namespace (default: `default`)
- `taskQueue` - Task queue name for workflow and activity execution
- `maxConcurrentWorkflows` - Maximum number of concurrent workflow executions

### Running Temporal Locally

For local development and testing, you can run Temporal server using Docker:

#### Using Docker Compose

Create a `docker-compose.yml` file or use Temporal's official setup:

```bash
# Clone Temporal's docker-compose setup
git clone https://github.com/temporalio/docker-compose.git
cd docker-compose

# Start Temporal server
docker-compose up -d

# Temporal server will be available at localhost:7233
# Temporal Web UI will be available at http://localhost:8080
```

#### Using Temporal CLI

Alternatively, use Temporal's CLI to run a local development server:

```bash
# Install Temporal CLI
brew install temporal

# Start local Temporal server
temporal server start-dev

# Server runs on localhost:7233 with Web UI on http://localhost:8080
```

For more details, visit the [Temporal documentation](https://docs.temporal.io/cli/server/start-dev).

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
