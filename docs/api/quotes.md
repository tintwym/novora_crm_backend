# Quotes API

| Method | Path | Notes |
|--------|------|--------|
| GET | `/api/v1/quotes` | Optional `?dealId=` |
| GET | `/api/v1/quotes/:id` | Detail + line items |
| POST | `/api/v1/quotes` | Create draft (line items required) |
| PATCH | `/api/v1/quotes/:id` | Edit draft only |
| PATCH | `/api/v1/quotes/:id/status` | `DRAFT → SENT → ACCEPTED/REJECTED` |
| GET | `/api/v1/quotes/:id/pdf` | PDF download |
| DELETE | `/api/v1/quotes/:id` | Draft only |

Default currency: **MMK**. Tax % supported.
