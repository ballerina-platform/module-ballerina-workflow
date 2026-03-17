# Examples

The `ballerina/workflow` library provides practical examples illustrating usage in various scenarios.

| Example | Description |
|---------|-------------|
| [get-started](get-started/) | Introductory example: define a workflow, call activities, run with `workflow:run()` |

## Prerequisites

- [Ballerina](https://ballerina.io/downloads/) 2201.13.0 or later

## Running an example

Execute the following commands to build an example from the source:

* To build an example:

    ```bash
    bal build
    ```

* To run an example:

    ```bash
    bal run
    ```

## Building the examples with the local module

**Warning**: Because of the absence of support for reading the local repository for Java package dependencies, the Gradle build process cannot be used for the examples. Consequently, the examples directory is not a Gradle subproject.

Execute the following commands to build all the examples against the changes you have made to the module locally:

* To build all the examples:

    ```bash
    ./build.sh build
    ```

* To run all the examples:

    ```bash
    ./build.sh run
    ```
