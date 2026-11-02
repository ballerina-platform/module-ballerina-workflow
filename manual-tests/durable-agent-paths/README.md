# Durable agent paths

Runs durable agents and agentic workflows through their main combinations against a real model. The
automated tests use a mock model that accepts any request, so problems that only a real model shows,
such as how a result type is described to it, are caught here.

Each scenario starts one agent or workflow. `run.sh` starts them in turn, answers the approvals and human
tasks they raise, and writes a report to `results/`.

## Run

1. Build the module: `./gradlew build -x test` from the repository root. This also compiles this project.
2. Start a Temporal dev server: `temporal server start-dev` (UI on http://localhost:8233).
3. Create `Config.toml` in this folder. It is git-ignored; never commit it.

   ```toml
   [ballerina.workflow]
   mode = "LOCAL"

   [ballerina.ai.wso2ProviderConfig]
   serviceUrl = "<generated>"
   accessToken = "<generated>"
   ```

   Generate the model entries with **Ballerina: Configure Default Model Provider** in VS Code. The token
   expires after a while; a run full of `Invalid Credentials` failures needs a new one.
4. Start the service with the locally built distribution: `../../target/ballerina-runtime/bin/bal run`
   (port 8095, `port` in `Config.toml` to change it).
5. In another terminal: `./run.sh` for every scenario, or `./run.sh R06-record W03-human-task-in-agent`.
   `TIMEOUT=<seconds>` changes how long it waits for each scenario (default 240). Every request is bounded by
   it, and a scenario still running at the deadline is reported `TIMED_OUT`.

Every run is also visible in the Temporal UI, which is the place to look when a scenario fails.

## Scenarios and expected results

All scenarios are expected to end `DONE`. Model answers vary, so read the result, not only the status.

| Scenario | What it checks | Expected result |
|---|---|---|
| R01-text | No `resultType` | A sentence |
| R02-string | `resultType: string` | A sentence |
| R03-int | `resultType: int` | `366` |
| R04-boolean | `resultType: boolean` | `true` |
| R05-enum | `resultType` is an enum | One of `LOW`, `MEDIUM`, `HIGH` |
| R06-record | Closed record | `{approved, risk, reason}` |
| R07-optional-fields | Optional, nilable and defaulted fields | `email` is `null`, `phone` is set |
| R08-nested-record | Record with a nested record and an array of records | An invoice with two items and a total |
| R09-record-array | Array of records | Three cities |
| R10-map | `map<int>` | Letter counts for three words |
| R11-annotated-record | Record with `@ai:JsonSchema` | `{summary, score}` |
| R12-union | Union of a record and `string` | A verdict record or a sentence |
| R13-json | `resultType: json` | A JSON object |
| I01-record-input | Record `inputType` with a record result | `accepted: true` for 2 laptops |
| I02-json-payload | Default `json` input with an array payload | `5` |
| M01-management-start | Start through `management:startWorkflowByType` | `accepted: false` for 30 mice (quantity above 10) |
| C01-activity-tools | Activity tools, one with `bindings` | Stock reported and `RES-WH-1-...` reserved |
| C02-approval-approved | Activity with an approval gate, approved | Refund issued |
| C03-approval-rejected | Same gate, rejected | Refund refused, with the reviewer's reason |
| C04-ai-tool | `@ai:AgentTool` function | `30000` |
| C05-human-task | Human task declared on the agent | The manager's decision as a record |
| C06-chat | `MULTI_EVENT` chat from a service, two turns | Two replies, the last a farewell |
| C07-peer-agent | Agent peer | A summary of the researcher's answer |
| C08-peer-workflow | Workflow function peer | Confirmation that the message was logged |
| C09-activity-retry | Activity that fails every other call, with `retryPolicy` | The exchange rate |
| W01-agent-step | Agent as a step of a workflow | The order decision |
| W02-parallel-agents | Two agents started together, then both awaited | A verdict and an order decision |
| W03-human-task-in-agent | Human task raised by an agent inside a workflow | The manager's decision |
| W04-chat-from-workflow | Chat with an agent from a workflow, two turns | Two replies |
| W05-agent-failure | Agent over its `maxIter`, handled by the workflow | `fallback: ...` with the iteration error |
| W06-non-blocking-read | `getResult` inside a workflow before the agent ends | `in progress, then: <planet>` |

## Known issues

- Agents here write `model: model` in full. The shorthand `model,` compiles but leaves the agent
  unregistered ([#9378](https://github.com/ballerina-platform/ballerina-library/issues/9378)).
