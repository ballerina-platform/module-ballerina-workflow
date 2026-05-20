# `mssql-inventory-replenishment` — inventory CDC synchronization

> **Domain:** Supply-chain automation · **Trigger:** SQL Server CDC
> changes from an inventory table · **Connectors:** `ballerinax/mssql`,
> `ballerinax/mssql.cdc.driver`, `ballerinax/slack`,
> `ballerinax/salesforce`, `ballerinax/googleapis.gmail`
> **Category:** Transactional workflow (no human in the loop).

## Scenario

A warehouse system stores inventory levels in SQL Server. Change Data
Capture is enabled on the inventory table. Every insert, update, and
delete event triggers a workflow that synchronizes downstream systems.
This use case is pure automation: it does not pause for approvals,
manual task completion, or human callbacks.

1. **Listen to SQL Server CDC** for inventory table changes.
2. **Create an inventory snapshot record** in Salesforce for audit and
   downstream reporting.
3. **Notify warehouse operations** in Slack.
4. If stock is below the reorder point, **email procurement and the
   preferred supplier** with reorder details.
5. If the row is deleted, **notify operations** that inventory tracking
   was removed.

```text
SQL Server CDC ── create / update / delete ──▶ workflow start
                                                   │
                                                   ├─▶ salesforce: create inventory snapshot
                                                   ├─▶ slack: notify warehouse ops
                                                   └─▶ gmail: email reorder details when low stock
```

The CDC listener is the integration trigger. Activities use real
connector calls; there is no mocked inventory, Salesforce, Slack, or
email backend logic.

## Source table

The example expects CDC events from a table similar to:

```sql
CREATE TABLE dbo.inventory (
    sku VARCHAR(64) PRIMARY KEY,
    product_name VARCHAR(255),
    quantity_on_hand INT,
    reorder_point INT,
    preferred_supplier_email VARCHAR(255)
);
```

CDC setup follows the SQL Server steps documented by the Ballerina MSSQL
connector:

```sql
USE warehouse;
EXEC sys.sp_cdc_enable_db;

EXEC sys.sp_cdc_enable_table
    @source_schema = 'dbo',
    @source_name = 'inventory',
    @role_name = NULL;
```

## CDC behavior

| CDC event | Workflow behavior |
| --------- | ----------------- |
| `onCreate` | Creates a Salesforce inventory snapshot and sends notifications. |
| `onUpdate` | Creates a new snapshot and sends low-stock email when required. |
| `onDelete` | Starts a deletion workflow that notifies warehouse operations. |

## Run

Configure SQL Server CDC, Slack, Salesforce, and Gmail settings in
`Config.toml`, then:

```bash
bal run
```

Insert, update, or delete rows in `dbo.inventory` to trigger the
automation.

Use the result endpoint exposed by the example to inspect a workflow once
you have its id:

```bash
curl http://localhost:8106/inventory/syncs/<workflow-id>
```

## Where this pattern shows up

- Database-change-driven synchronization where a row mutation must fan
   out to CRM, notification, and reporting systems.
- Replenishment, catalog, or warehouse automations triggered by CDC
   rather than a human-operated UI.
- Operational pipelines that need durable retries and state recovery
   when a worker restarts during downstream synchronization.
