# Activities API

| Method | Path | Notes |
|--------|------|--------|
| GET | `/api/v1/activities` | List / filter (`type`, `contactId`, `dealId`, `completed`) |
| GET | `/api/v1/activities/recent` | Recent feed |
| GET | `/api/v1/activities/calendar?from=&to=` | Calendar range |
| GET | `/api/v1/activities/reminders` | Overdue + due within 48h |
| POST | `/api/v1/activities` | Create (must link contact and/or deal) |
| PATCH | `/api/v1/activities/:id` | Update / complete via `completed` |
| POST | `/api/v1/activities/:id/complete` | Mark complete |
| DELETE | `/api/v1/activities/:id` | Delete |

Types: `CALL`, `MEETING`, `EMAIL`, `TASK`, `NOTE`
