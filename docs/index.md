# Ballerina Workflow Module

The Ballerina Workflow module provides durable workflow orchestration for Ballerina applications. It enables you to define long-running, fault-tolerant business processes using familiar Ballerina constructs.

## Key Features

- **Durable, Long-Running Execution** — Workflows run for as long as needed — minutes, hours, or days — and survive process restarts and infrastructure failures. The runtime automatically checkpoints workflow state and recovers from failures, guaranteeing that workflows always run to completion.
- **Protocol-Independent Entry Points** — Workflow logic is independent of the protocol used to trigger it. You can start the same workflow from an HTTP endpoint, a message queue consumer, a scheduled job, or any other entry point. Multiple entry points can trigger the same workflow.
- **Asynchronous Execution** — Workflows can pause and wait for external data events or timer events during execution. This enables patterns like human-in-the-loop approvals, payment confirmations, or scheduled follow-ups without blocking resources.
- **Activities with Automatic Retry** — Encapsulate non-deterministic operations (I/O, API calls, database access) in activity functions. Activities are automatically retried on failure with configurable retry policies, and their results are recorded so they execute exactly once even during workflow replays.


## Documentation

| Guide | Description |
|-------|-------------|
| [Get Started](get-started.md) | Write and run your first workflow |
| [Key Concepts](key-concepts.md) | Workflows, activities, data events, timer events, and triggers |
| [Set Up Temporal Server](set-up-temporal-server.md) | Install and run the Temporal server for local development |

