# Users / Team API

Base: `/api/v1/users` (JWT required)

| Method | Path | Permission | Description |
|--------|------|------------|-------------|
| GET | `/` | `view:users` | List users in current tenant |
| POST | `/` | `manage:users` | Invite user (`name`, `email`, `password`, `role`) |
| PATCH | `/:id` | `manage:users` | Update `name`, `role`, `isActive`, `password` |

Assignable roles: `ADMIN`, `SALES_MANAGER`, `SALES_REP`, `SUPPORT_AGENT`, `MARKETING_MANAGER`.
