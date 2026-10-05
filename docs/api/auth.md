# Auth API (Spring Boot)

Base: `/api/v1/auth`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/register` | Public | Create tenant + admin user |
| POST | `/login` | Public | Returns `user` + `tokens` |
| POST | `/refresh` | Public | Rotate refresh token |
| POST | `/logout` | Public | Revoke refresh token |
| GET | `/me` | Bearer | Current user + company |

JWT access (~15m) and refresh (~7d). Secrets via `JWT_ACCESS_SECRET` / `JWT_REFRESH_SECRET`.
