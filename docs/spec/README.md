# Workflow Definition Descriptor (WDD) specification

The Workflow Definition Descriptor is a **versioned, structured definition file** — "OpenAPI
for durable workflows" — that fully describes every workflow component a Ballerina integration
contains: workflow definitions, activities, human tasks, review activities, events, and durable
agents, with their JSON Schemas.

- **Generated at build time** by the `ballerina/workflow` compiler plugin and **packed into the
  integration's executable JAR** as the fixed-name resource **`workflow.def.json`**.
- **Describes, never executes**: the workflow logic stays Ballerina code; the descriptor records
  what the compiled code registers — the way an OpenAPI document describes a service without
  being the service.
- **Structural facts only**: what must be registered with the workflow runtime (names, function
  bindings, event channels) plus the schemas attached to those structures. Expression-valued
  instance parameters — retry counts, roles, titles, descriptions, timeouts, payloads — are
  deliberately out: they parameterize an instance, they don't change its structure. Two builds
  with the same descriptor checksum are structurally interchangeable.
- **Typed schema slots**: every schema-bearing position records the resolved **Ballerina type**
  (always) and a derived **JSON Schema** (when the type can honestly be one): exact for closed
  anydata shapes; permissive and flagged `lossy` for open anydata (`json`, `anydata`); omitted
  for the unrepresentable (`xml`, `error`, behavioral types, mixed composites). Form generators
  degrade gracefully across the tiers: structured form → schema-guided editor → raw payload
  editor labeled with the type.
- **Versioned twice**: `descriptorVersion` (major.minor) versions this spec's shape — minor is
  additive-only, an unknown major must not be parsed. Instance identity is
  `(package.org, package.name, package.version, checksum)`, where the checksum is the SHA-256
  of the document's canonical bytes (sorted keys, no insignificant whitespace) serialized
  without the checksum field.

- **The graph, per workflow and per agent** (part of `descriptorVersion` 1.0 — the descriptor has not shipped, so the graph is in the first version rather than a bump): what a workflow *does*, in
  source order, nested under the control flow that guards it — activities, human tasks, child
  workflows, event waits and sleeps as steps, `BRANCH`/`LOOP`/`TRY` as the containers around them,
  with edges that follow control flow. It answers what the activity list cannot: when the same
  activity is called from both arms of an `if`, which arm ran. An agent's graph is a star instead
  of a flow — the model, not the code, decides what runs — with data events and human tasks
  inbound and tools and the model outbound.
- **Step ids join a run to its graph**: a step's identity is either chosen at the call site
  (`stepId = "charge-card"`) or generated as `<target>#<ordinal>`, counting occurrences of that
  target within the workflow in source order. A generated id survives reformatting and edits
  elsewhere in the file but shifts if a call to the same target is added earlier; a chosen id does
  not move at all, which is why naming the steps that matter is worth doing. A chosen id must be a
  constant (an expression evaluated per execution cannot be described at build time); an id another
  step already has is suffixed with a warning rather than rejected, and the suffixed id is what both
  the graph and the call carry, so the two never disagree.
  `line`/`column` travel alongside for display only. The runtime stamps the step id onto the
  invocation it records and reports it back as `ActivityTreeNode.stepId` /
  `GraphNode.metadata.stepId`, so a viewer can highlight the path a run actually took. Treat the
  join as optional: an execution started before the runtime carried step ids reports none, and a
  step renamed since a run started reports the old id.

The meta-schema — the JSON Schema this descriptor validates against — is
[`workflow-descriptor.schema.json`](workflow-descriptor.schema.json). The embedded schema
dialect is pinned: the exact JSON Schema 2020-12 subset enumerated in the meta-schema's
`schemaDocument` definition (what the runtime's `TypesUtil` emits). Dialect extensions arrive
only with descriptor minor versions.
