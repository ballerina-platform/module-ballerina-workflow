# `sheets-campaign-sync` — campaign sync from Google Sheets

> **Domain:** Marketing operations · **Trigger:** Google Sheets row append
> and row update · **Connectors:** `ballerinax/trigger.google.sheets`,
> `ballerinax/slack`, `ballerinax/salesforce`,
> `ballerinax/googleapis.gmail`
> **Category:** Transactional workflow (no human in the loop).

## Scenario

Marketing operations maintains a spreadsheet of campaign launch records.
This use case is pure automation: a sheet row starts a workflow that
synchronizes campaign data into downstream systems. No workflow step waits
for a human approval or task completion.

1. **Listen for a new campaign row** using the Google Sheets trigger.
2. **Create a Salesforce Campaign** from the spreadsheet values.
3. **Notify marketing operations** in Slack with the created campaign id.
4. **Email the campaign owner** with the synchronization outcome.
5. **Listen for row updates** and synchronize Salesforce Campaign status
   when the sheet status changes.

```text
Google Sheets ── row append / update ──▶ workflow start
                                              │
                                              ├─▶ salesforce: create/update Campaign
                                              ├─▶ slack: notify marketing ops
                                              └─▶ gmail: email campaign owner
```

No activity mocks the backend. Slack, Salesforce, and Gmail work is done
through real connector calls. Google Sheets is the operational data entry
surface and event trigger.

## Sheet format

The example expects rows with these columns:

| Column | Meaning |
| ------ | ------- |
| A | Request id |
| B | Campaign name |
| C | Owner email |
| D | Region |
| E | Budget USD |
| F | Launch date |
| G | Campaign status (`Planned`, `In Progress`, `Completed`) |
| H | Salesforce Campaign Id, used by update events |

The `onAppendRow` handler starts a workflow that creates a Salesforce
Campaign. The `onUpdateRow` handler starts a workflow that updates an
existing Salesforce Campaign when column H contains the Salesforce id.

## Google Sheets trigger setup

Configure the Apps Script trigger as described in the
`ballerinax/trigger.google.sheets` connector documentation, replacing
`<BASE_URL>` with the public URL where this Ballerina service is running.
The trigger supports row append and row update events through
`onAppendRow` and `onUpdateRow`.

## Run

Configure Google Sheets, Slack, Salesforce, and Gmail settings in
`Config.toml`, then:

```bash
bal run
```

Append a row to the configured sheet to create a Salesforce Campaign.
Update the row status later to synchronize the campaign status.

## Where this pattern shows up

- Spreadsheet-driven operational workflows where a business team edits
   rows and downstream systems need to stay synchronized.
- Campaign, pricing, catalog, or partner-data fan-out where one row
   change must update CRM, messaging, and email systems together.
- Low-code operational surfaces where Google Sheets is the source of
   truth, but durable execution and retries are still required.
