# Workflow built-in activities

The `workflow.activity` submodule provides a set of ready-made activity
functions for common integration tasks: REST calls, SOAP calls, and email
sending. Each function is annotated with `@workflow:Activity`, so they can
be invoked from any `@workflow:Workflow` function via the
`ctx->callActivity(...)` form.

The connector clients used by these activities (`http:Client`,
`soap11:Client` / `soap12:Client`, `email:SmtpClient`) must be declared as
module-level `final` variables; the workflow compiler plugin emits the
wiring needed to make them available on the activity worker side.

| Function       | Connector             | Returns      |
|----------------|-----------------------|--------------|
| `callRestAPI`  | `http:Client`         | `t\|error` |
| `callSoapAPI`  | `soap11:Client \| soap12:Client` | `xml\|error` |
| `sendEmail`    | `email:SmtpClient`    | `error?`     |
