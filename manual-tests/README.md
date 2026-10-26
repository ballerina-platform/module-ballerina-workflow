# Manual tests

Projects here exercise the module against real services, such as a model provider, that the automated
tests replace with mocks. The build compiles each project against the locally built module, so they keep
up with API changes, but running them needs credentials and a person to read the results.

| Project | What it covers |
|---|---|
| [durable-agent-paths](durable-agent-paths) | Durable agents and agentic workflows across result types, inputs, capabilities, and agents inside workflows, against a real model |

Run them before a release and after changes to the agent runtime, and record anything new as an issue.
