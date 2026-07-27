# Durable AI Agent — Conversational Order Processing

Demonstrates a conversational durable AI agent declared with the object model,
powered by the **WSO2 default model provider** (`ai:getDefaultModelProvider()`).

The agent is a module-level `final workflow:DurableAgent` whose constructor config
carries every capability: the system prompt, the model, the `checkInventory`
activity tool (a `@workflow:Activity` function), and a `chat` event channel with
`MULTI_EVENT` cardinality that keeps the conversation open after each answer.
Every LLM call and every tool call runs as a durable Temporal activity, so the agent survives
worker crashes and, on replay, re-loads its previous reasoning from the workflow event history
instead of re-querying the model.

The client starts a run with `orderAgent.run(...)` and drives the conversation with
**`sendEvent` / `waitForEventResult`** — each `sendEvent` delivers the user's message and
returns a correlation token, and `waitForEventResult` returns the agent's answer for that
turn (a Temporal Update under the hood). Between turns the agent suspends durably —
it can wait hours or days for the next message without holding a thread.

```ballerina
string turn = check orderAgent.sendEvent(agentId, "chat", "Is the laptop available?");
string reply = check orderAgent.waitForEventResult(agentId, turn);
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

```text
Agent started with ID: <uuid>
[activity] checkInventory(laptop)
Turn 1: The laptop is in stock. ...
Turn 2: ... (acknowledges expedited shipping) ...
Final: Goodbye! ...
```

Under `MULTI_EVENT` cardinality the framework owns conversation continuity: after
each answer the agent automatically suspends until the next chat message — the model does not
need to do anything to keep the conversation open. Ending is explicit: the model calls the
built-in `endConversation` tool when the user says goodbye, and a bounded per-turn event
timeout ends an abandoned conversation gracefully, with the max-event-waits cap as a hard
backstop.
