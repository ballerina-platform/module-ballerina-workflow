# `finance-invoice-processing` — AP invoice processing from SFTP

> **Domain:** Finance automation · **Trigger:** SFTP file watch (`ftp:Listener`) ·
> **Connectors:** `ballerina/ftp`, `ballerina/email` (SMTP), `ballerinax/salesforce`

## Scenario

Vendors drop CSV invoices into `/incoming/invoices` on the corporate
SFTP server. An FTP listener picks them up and starts one workflow per
row. Small invoices are auto-approved; larger ones require human review
via Salesforce.

1. If `amount_usd <= autoApproveThresholdUsd` → email the finance team
   that the invoice was auto-approved and the workflow ends.
2. Otherwise:
   * Create a **Salesforce `Case`** for the AP queue.
   * **Email the finance team** (SMTP) that a Case was filed.
   * **Pause** until the Salesforce webhook calls back with the Case's
     resolution.
   * **Close the Case** in Salesforce.
   * **Email the finance team** with the final outcome.

```
SFTP /incoming/invoices/*.csv  ──▶  ftp listener  ──▶  workflow:run per row
                                                              │
                                                              ├─▶ salesforce: create Case
                                                              ├─▶ smtp: notify finance
                                                              │
                                                              ░ wait events.approval ░
                                                              │
 Salesforce Flow ── POST /finance/invoices/{id}/approval ── workflow:sendData
                                                              │
                                                              ├─▶ salesforce: close Case
                                                              └─▶ smtp: outcome email
```

## CSV format

The watcher expects files matching `*.csv` with this header:

```csv
invoice_number,vendor,amount_usd,due_date
INV-2026-0001,Acme Cloud Inc,1850.00,2026-02-15
```

## Endpoints (HTTP listener)

| Method | Path                                          | Purpose                                  |
| ------ | --------------------------------------------- | ---------------------------------------- |
| POST   | `/finance/invoices/{workflowId}/approval`     | Salesforce webhook callback.             |
| GET    | `/finance/invoices/{workflowId}`              | Fetch the workflow's final result.       |

## Run

Configure the SFTP credentials and the SMTP/Salesforce settings in
`Config.toml`, then:

```bash
bal run
```

The FTP listener will start polling on the configured interval.
