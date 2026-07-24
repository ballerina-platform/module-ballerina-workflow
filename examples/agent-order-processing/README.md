# Durable AI Agent — Conversational Order Processing

Demonstrates a durable AI agent written with the imperative `workflow:AgentContext` API,
powered by the **WSO2 default model provider** (`ai:getDefaultModelProvider()`).

The `@workflow:DurableAgent` function receives an `AgentContext`, registers its tools
(`@workflow:Activity` functions) imperatively, configures the `MULTI_EVENT` interaction pattern,
and hands control to the durable ReAct loop via `ctx.runDurableAgent(query, systemPrompt = ..., model = ...)` —
the same configuration shape as a regular `ai:Agent`.
Every LLM call and every tool call runs as a durable Temporal activity, so the agent survives
worker crashes and, on replay, re-loads its previous reasoning from the workflow event history
instead of re-querying the model.

The client drives the conversation with **`workflow:updateAgent`** — a synchronous
request-response interaction (a Temporal Update under the hood): each call delivers the user's
message and returns the agent's answer for that turn. Between turns the agent suspends durably —
it can wait hours or days for the next message without holding a thread.

```ballerina
string reply = check workflow:updateAgent(orderAgent, agentId, "chat", "Is the laptop available?");
```

## Prerequisites — configure the model provider

This example uses the WSO2 default model provider, which reads its credentials from
`Config.toml`. The file contains an access token, so it is **git-ignored — never commit it**.

### Generate it with VS Code (recommended)

1. Open this example folder in VS Code with the
   [Ballerina extension](https://marketplace.visualstudio.com/items?itemName=WSO2.ballerina)
   installed, and sign in when prompted.
2. Open the Command Palette (`Cmd/Ctrl + Shift + P`) and run
   **“Ballerina: Configure Default Model Provider”**.
3. The extension signs in to your WSO2 account and writes the
   `[ballerina.ai.wso2ProviderConfig]` entries (`serviceUrl`, `accessToken`) into `Config.toml`.
4. Add the workflow engine mode to the same file, so the final `Config.toml` looks like:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"

[ballerina.ai.wso2ProviderConfig]
serviceUrl = "<generated>"
accessToken = "<generated>"
```

### Manual alternative

Create `Config.toml` with the content above, supplying your own WSO2 AI service URL and
access token.

## Run

```bash
bal run
```

Expected flow (actual wording varies — a real LLM writes the replies):

```
Agent started with ID: <uuid>
[activity] checkInventory(laptop)
Turn 1: The laptop is in stock. ...
Turn 2: ... (acknowledges expedited shipping) ...
Final: Goodbye! ...
```

Under the `MULTI_EVENT` interaction pattern the framework owns conversation continuity: after
each answer the agent automatically suspends until the next chat message — the model does not
need to do anything to keep the conversation open. Ending is explicit: the model calls the
built-in `endConversation` tool when the user says goodbye, or the event timeout from
`ctx.setInteraction` (5 minutes here) ends the conversation gracefully; the max-event-waits cap
bounds it as a hard backstop.
