# Structure

Project root has two folders:

| Folder | Role |
|--------|------|
| `backend/` | Spring Boot API, Docker Compose, Flyway SQL, API docs |
| `frontend/` | React web app (roles, permissions and form validators live in `frontend/src/shared/`) |

Schema migrations live in `backend/src/main/resources/db/migration/` (Flyway → Neon/Postgres). No Prisma.
