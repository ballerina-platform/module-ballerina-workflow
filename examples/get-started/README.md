# Get Started Example

This example walks through the introductory [Get Started](../../docs/get-started.md) guide.

It shows:

- Defining a workflow with `@workflow:Workflow`
- Defining activities with `@workflow:Activity`
- Calling activities from a workflow via `ctx->callActivity()`
- Starting a workflow with `workflow:run()` and retrieving its result

## Running the Example

### Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

### Using IN_MEMORY mode (no server required)

The included `Config.toml` uses `IN_MEMORY` mode. Simply run:

```bash
bal run
```

Expected output:

```text
Workflow started with ID: <uuid>
Checking inventory for laptop, quantity: 2
Reserving 2 unit(s) of laptop for order ORD-001
Result: Order ORD-001 confirmed. Reservation ID: RES-ORD-001
```

### Using a local Temporal server

To run against a local Temporal server, update `Config.toml`:

```toml
[ballerina.workflow]
mode = "LOCAL"
```

Then start your Temporal dev server (see [Temporal CLI docs](https://docs.temporal.io/cli)):

```bash
temporal server start-dev
```

Then run the example:

```bash
bal run
```

## Running with the local module

To run this example against local changes to the `ballerina/workflow` module, use the
build script from the `examples/` directory:

```bash
# From the examples/ root
./build.sh run
```
