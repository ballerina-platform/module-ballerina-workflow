# CRM to CRM Contact Sync Workflow

This sample demonstrates bidirectional contact synchronization between two CRM systems.

## Overview

The workflow syncs contacts between CRM systems (vendor-neutral):
1. Receives contact update event
2. Validates contact data
3. Checks if contact exists in target CRM
4. Creates or updates contact in target CRM
5. Records sync result

Inspired by CRM integration patterns used in automation platforms.

## Features Demonstrated

- **@Process annotation**: Workflow entry point
- **@Activity annotation**: External system interactions
- **Error handling**: Graceful handling of sync failures
- **Duplicate detection**: Check existing contacts
- **Mock CRM systems**: Simulates source and target CRM APIs

## Architecture

```
Webhook Event → HTTP Service → CRM Sync Workflow
                                      ↓
                                 Activities:
                                 - validateContact
                                 - findTargetContact
                                 - createContact
                                 - updateContact
                                      ↓
                              Mock CRM Systems
```

## Running the Sample

### Prerequisites

1. Temporal server running on `localhost:7233`
2. Ballerina 2201.13.x installed

### Start the Service

```bash
bal build
bal run

# Service starts on http://localhost:9091
```

### Test Contact Sync

Trigger a contact sync:

```bash
curl -X POST http://localhost:9091/sync/contact \
  -H "Content-Type: application/json" \
  -d '{
    "id": "CONTACT-001",
    "email": "john.doe@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+1-555-0123",
    "company": "Example Corp"
  }'
```

Response:
```json
{
  "status": "success",
  "workflowId": "CONTACT-001",
  "syncResult": "created"
}
```

Query sync status:

```bash
curl http://localhost:9091/sync/contact/CONTACT-001/status
```

## Code Structure

```
crm-sync/
├── main.bal              # HTTP service and workflow client
├── workflow.bal          # Contact sync workflow
├── activities.bal        # Activity implementations
├── mock_crm.bal         # Mock CRM backends
├── types.bal            # Type definitions
├── Config.toml          # Configuration
└── README.md            # This file
```

## Key Concepts

### Bidirectional Sync

The workflow handles:
- **Create**: New contacts in source → create in target
- **Update**: Modified contacts → update target
- **Duplicate Detection**: Check existing contacts by email

### Contact Mapping

Maps contact fields between systems:
```ballerina
{
    id: "CRM1-123",
    email: "user@example.com",
    firstName: "John",
    lastName: "Doe",
    phone: "+1-555-0123"
}
↓
{
    Id: "CRM2-456",
    Email: "user@example.com",
    FirstName: "John",
    LastName: "Doe",
    Phone: "+1-555-0123",
    Source: "CRM1"
}
```

## Extension Ideas

- Add bidirectional sync (both directions)
- Implement conflict resolution
- Add custom field mapping
- Support bulk sync operations
- Add sync history tracking
- Integrate with real CRM APIs
