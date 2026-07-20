# Durable AI Agent — Human Task Approval

Demonstrates a durable AI agent with a **human task in its toolbox**
(`ctx.registerHumanTask`). When the user asks to expedite shipping, the agent decides to
involve a person: it creates the `approveExpedite` human task — a durable Temporal
sub-workflow — and **suspends** (for hours or days, without holding a thread) until a manager
completes it, then reports the decision back to the user in the same chat turn.

The example is an HTTP service: chat turns are synchronous request-responses via
`workflow:updateAgent`, the manager's inbox is served by the `workflow.management` module, and
`management:completeHumanTask` resumes the suspended agent.

## Prerequisites — configure the model provider

This example uses the WSO2 default model provider (`ai:getDefaultModelProvider()`), which reads
its credentials from `Config.toml`. The file contains an access token, so it is
**git-ignored — never commit it**.

1. Open this folder in VS Code with the
   [Ballerina extension](https://marketplace.visualstudio.com/items?itemName=WSO2.ballerina),
   run **“Ballerina: Configure Default Model Provider”** from the Command Palette, and sign in —
   it writes the `[ballerina.ai.wso2ProviderConfig]` entries into `Config.toml`.
2. Add the workflow engine mode, so the final `Config.toml` looks like:

```toml
[ballerina.workflow]
mode = "IN_MEMORY"

[ballerina.ai.wso2ProviderConfig]
serviceUrl = "<generated>"
accessToken = "<generated>"
```

## Run the walkthrough

Start the service:

```bash
bal run
```

**1. Start an agent** (it suspends until the first chat message):

```bash
curl -X POST localhost:8085/orders/start
# {"agentId":"<agentId>"}
```

**2. Chat with it** — the reply comes back synchronously:

```bash
curl -X POST localhost:8085/orders/<agentId>/chat \
     -H "Content-Type: text/plain" -d "Is the laptop available?"
# "Yes, the laptop is in stock. ..."
```

**3. Ask to expedite shipping** — the agent creates the approval human task and this call
**blocks** until a manager decides (run it in a separate terminal):

```bash
curl -X POST localhost:8085/orders/<agentId>/chat \
     -H "Content-Type: text/plain" -d "Please expedite my shipping, it is urgent"
```

**4. As the manager, list the pending task and complete it:**

```bash
curl localhost:8085/orders/<agentId>/tasks
# [{"taskName":"orderAgent.approveExpedite", "taskIds":["humantask-..."], ...}]

curl -X POST localhost:8085/orders/tasks/<taskId>/complete \
     -H "Content-Type: application/json" \
     -d '{"approved": true, "comment": "Approved - urgent customer"}'
```

The suspended agent resumes, and the blocked chat call from step 3 returns the agent's answer
with the manager's decision.

**5. Say goodbye** to end the conversation (the model calls the built-in `endConversation`
tool):

```bash
curl -X POST localhost:8085/orders/<agentId>/chat \
     -H "Content-Type: text/plain" -d "Great, thanks. Goodbye!"
```

Every step is durable: LLM calls and the `checkInventory` tool run as Temporal activities, the
approval is a child workflow, and the agent survives worker restarts at any point — on replay
it reloads its reasoning from the event history instead of re-querying the model.
