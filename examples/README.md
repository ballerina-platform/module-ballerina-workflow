# Examples

The `ballerina/workflow` library provides practical examples illustrating usage in various scenarios.

| Example | Description |
|---------|-------------|
| [get-started](get-started/) | Introductory example: define a workflow, call activities, run with `workflow:run()` |
| [error-propagation](error-propagation/) | Propagate an activity error to the caller with `check` — workflow transitions to Failed |
| [error-fallback](error-fallback/) | Fall back to a secondary activity when the primary exhausts its retries |
| [error-compensation](error-compensation/) | Saga pattern: run compensating activities to undo committed steps when a later step fails |
| [graceful-completion](graceful-completion/) | Tolerate failures in non-critical activities and complete the workflow successfully |
| [order-processing](order-processing/) | HTTP service that starts an order workflow and polls for results |
| [order-with-payment](order-with-payment/) | Order workflow that pauses and waits for a payment confirmation |
| [human-in-the-loop](human-in-the-loop/) | Pause the workflow for a human decision (approve or reject) |
| [forward-recovery](forward-recovery/) | Pause for corrected data and retry a failed activity |

## Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

## Running an example

First, change into the example directory:

```bash
cd examples/<example-name>
```

Then execute the following commands:

* To build an example:

    ```bash
    bal build
    ```

* To run an example:

    ```bash
    bal run
    ```

## Building the examples with the local module

### Via Gradle (recommended for CI)

The examples directory is a Gradle subproject (`:workflow-examples`). To build all examples against the locally built workflow module:

```bash
./gradlew :workflow-examples:build
```

### Via shell script

Alternatively, use the shell scripts to build or run all examples:

**On Unix/macOS:**

* To build all the examples:

    ```bash
    ./build.sh build
    ```

* To run all the examples:

    ```bash
    ./build.sh run
    ```

**On Windows:**

* To build all the examples:

    ```bat
    build.bat build
    ```

* To run all the examples:

    ```bat
    build.bat run
    ```
