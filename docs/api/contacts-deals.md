# Contacts & Companies API

## Companies (CRM accounts)

Tenant isolation via JWT `companyId`. These are customer companies (`accounts` table), not the tenant workspace.

| Method | Path | Permission |
|--------|------|------------|
| GET | `/api/v1/companies` | `view:companies` |
| GET | `/api/v1/companies/:id` | `view:companies` |
| POST | `/api/v1/companies` | `manage:companies` |
| PATCH | `/api/v1/companies/:id` | `manage:companies` |
| DELETE | `/api/v1/companies/:id` | `manage:companies` |

## Contacts

| Method | Path | Permission |
|--------|------|------------|
| GET | `/api/v1/contacts` | `view:contacts` |
| GET | `/api/v1/contacts/:id` | `view:contacts` |
| GET | `/api/v1/contacts/:id/timeline` | `view:contacts` |
| POST | `/api/v1/contacts` | `manage:contacts` |
| PATCH | `/api/v1/contacts/:id` | `manage:contacts` |
| DELETE | `/api/v1/contacts/:id` | `manage:contacts` |
| POST | `/api/v1/contacts/:id/notes` | `manage:contacts` |
| POST | `/api/v1/contacts/import` | `import:contacts` |
| GET/POST | `/api/v1/contacts/tags` | view/manage contacts |

CSV import body:

```json
{
  "contacts": [
    { "name": "Su Su", "email": "su@acme.com", "accountName": "Acme", "tags": "vip,lead" }
  ]
}
```

# Deals API

| Method | Path | Permission |
|--------|------|------------|
| GET | `/api/v1/deals/pipeline` | `view:deals` |
| GET | `/api/v1/deals/forecast` | `forecast:deals` |
| GET | `/api/v1/deals` | `view:deals` |
| GET | `/api/v1/deals/:id` | `view:deals` |
| POST | `/api/v1/deals` | `manage:deals` |
| PATCH | `/api/v1/deals/:id` | `manage:deals` |
| PATCH | `/api/v1/deals/:id/stage` | `manage:deals` |
| DELETE | `/api/v1/deals/:id` | `manage:deals` |
| POST | `/api/v1/deals/:id/activities` | `manage:activities` |
| POST | `/api/v1/deals/:id/attachments` | `manage:deals` |

Stages: `LEAD → QUALIFIED → PROPOSAL → NEGOTIATION → WON | LOST`
