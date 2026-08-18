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

The meta-schema — the JSON Schema this descriptor validates against — is
[`workflow-descriptor.schema.json`](workflow-descriptor.schema.json). The embedded schema
dialect is pinned: the exact JSON Schema 2020-12 subset enumerated in the meta-schema's
`schemaDocument` definition (what the runtime's `TypesUtil` emits). Dialect extensions arrive
only with descriptor minor versions.
