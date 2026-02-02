# Agent Documentation - Index

> **This is an index to detailed instruction files.**
> 
> For essential context that's always loaded, see [copilot-instructions.md](../copilot-instructions.md).
> For deep implementation details, navigate to the specific instruction files below.

## Instruction Files Index

| # | Topic | File | Coverage |
|---|-------|------|----------|
| 01 | Workflow Design | [01-workflow-design.instructions.md](01-workflow-design.instructions.md) | Annotations, Context, Activities, Process signatures |
| 02 | Singleton Worker Pattern | [02-singleton-worker-pattern.instructions.md](02-singleton-worker-pattern.instructions.md) | Worker lifecycle, configuration, initialization |
| 03 | Future-Based Signals | [03-future-based-signals.instructions.md](03-future-based-signals.instructions.md) | TemporalFutureValue, signal handling, deadlock avoidance |
| 04 | Dynamic Workflow Implementation | [04-dynamic-workflow-implementation.instructions.md](04-dynamic-workflow-implementation.instructions.md) | BallerinaWorkflowAdapter, dynamic routing, execution flow |
| 05 | Optional signalName | [05-optional-signal-name.instructions.md](05-optional-signal-name.instructions.md) | Signal name inference, ambiguity detection, SendEventValidatorTask |
| 06 | Integration Testing | [06-integration-testing.instructions.md](06-integration-testing.instructions.md) | Embedded Temporal server, Gradle test lifecycle |
| 07 | Dependencies & Build | [07-dependencies-build.instructions.md](07-dependencies-build.instructions.md) | Version requirements, build commands, module structure |
| 08 | Correlation Keys | [08-correlation.md](08-correlation.md) | Readonly fields, Search Attributes, duplicate detection |

## File Locations Reference

### Ballerina Module
- [ballerina/annotations.bal](../../ballerina/annotations.bal) - @Process, @Activity annotations
- [ballerina/context.bal](../../ballerina/context.bal) - Context client class
- [ballerina/functions.bal](../../ballerina/functions.bal) - createInstance(), sendEvent()
- [ballerina/types.bal](../../ballerina/types.bal) - ProcessInfo, ActivityInfo
- [ballerina/config.bal](../../ballerina/config.bal) - WorkflowConfig
- [ballerina/module.bal](../../ballerina/module.bal) - Module initialization

### Compiler Plugin
- [WorkflowCompilerPlugin.java](../../compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCompilerPlugin.java) - Plugin entry point
- [WorkflowValidatorTask.java](../../compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowValidatorTask.java) - Validation logic
- [WorkflowCodeModifier.java](../../compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/WorkflowCodeModifier.java) - Code generation
- [SendEventValidatorTask.java](../../compiler-plugin/src/main/java/io/ballerina/stdlib/workflow/compiler/SendEventValidatorTask.java) - Signal validation

### Native Implementation
- [WorkflowWorkerNative.java](../../native/src/main/java/io/ballerina/stdlib/workflow/worker/WorkflowWorkerNative.java) - Temporal worker
- [WorkflowRuntime.java](../../native/src/main/java/io/ballerina/stdlib/workflow/runtime/WorkflowRuntime.java) - Runtime management
- [TemporalFutureValue.java](../../native/src/main/java/io/ballerina/stdlib/workflow/context/TemporalFutureValue.java) - Signal futures
- [CorrelationExtractor.java](../../native/src/main/java/io/ballerina/stdlib/workflow/utils/CorrelationExtractor.java) - Correlation keys

### Tests
- [integration-tests/tests/](../../integration-tests/tests/) - Integration tests
- [ballerina/tests/](../../ballerina/tests/) - Unit tests
- [compiler-plugin-tests/](../../compiler-plugin-tests/) - Compiler plugin tests

## How to Use This Documentation

### For AI Agents
1. Start with [copilot-instructions.md](../copilot-instructions.md) for essential context
2. Use the index above to find detailed instruction files for specific topics
3. Each instruction file contains implementation across all 3 layers with code examples

### For Humans
1. Use this index to **find relevant topics**
2. Follow links to **detailed instruction files**
3. Each instruction file is **self-contained** with:
   - Current implementation across all 3 layers
   - Code examples from actual implementation
   - Execution flows
   - Success criteria

## Additional Resources

- **GitHub Copilot Instructions**: [../copilot-instructions.md](../copilot-instructions.md)
- **Project README**: [../../README.md](../../README.md)
- **Changelog**: [../../changelog.md](../../changelog.md)

## Contributing to Documentation

When updating implementation:
1. Update the relevant instruction file(s)
2. Ensure **all three layers** (Ballerina, compiler plugin, native) are documented
3. Add **code examples** from actual implementation
4. Update **success criteria** to reflect new behavior
5. Keep this index in sync with new instruction files

## Documentation Maintenance

Each instruction file should:
- ✅ Cover **current implementation** (not proposals)
- ✅ Include **code from actual files** (not hypothetical)
- ✅ Document **all three layers** consistently
- ✅ Provide **execution flows** showing interaction
- ✅ Define **success criteria** for verification
- ✅ Reference **actual file locations** with links
