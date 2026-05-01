# Fast-Bet Gateway

Rust API gateway for Fast-Bet. Phase 1 responsibilities (per `docs/architecture.md`):

- JWT termination (RS256) on protected routes — validate signature, expiration, and `roles` claim.
- Public routes (e.g. `POST /signup`, `POST /login`) forwarded without JWT validation.
- Per-IP rate limiting backed by Redis (`5 req / 60 s` for `/signup`).
- `X-Request-Id` propagation for cross-service tracing.
- **No** business logic and **no** response composition.

## Run locally

```bash
cd ../infra && make up                  # start postgres, kafka, redis
cd ../services/identity && mvn quarkus:dev  # in another shell
cd gateway
cp .env.example .env                    # adjust values if needed
cargo run
```

Smoke-test:

```bash
curl -i -X POST http://localhost:8080/signup \
  -H 'content-type: application/json' \
  -d '{"email":"ada@example.com","password":"supersecret123","displayName":"ada"}'
```

## Tests

```bash
cargo test
```

Integration tests under `tests/` mount the same router with an in-memory rate
limiter and a stub identity backend, so they need no Redis or Postgres.

## Configuration

| Env var | Default | Description |
| --- | --- | --- |
| `GATEWAY_BIND_ADDR` | `0.0.0.0:8080` | TCP listen address. |
| `IDENTITY_BASE_URL` | `http://identity:8080` | Base URL of the Identity service. |
| `REDIS_URL` | `redis://redis:6379` | Redis URL for shared rate-limit counters. |
| `SIGNUP_RATE_LIMIT` | `5` | Max signup requests per window per IP. |
| `SIGNUP_RATE_WINDOW_SECS` | `60` | Rolling window in seconds. |
| `IDENTITY_EDGE_TOKEN` | **(required)** | Shared edge secret injected as `X-Edge-Token` on every upstream request to Identity. Must match `IDENTITY_EDGE_TOKEN` configured in the Identity service. Startup fails if absent or blank. |
