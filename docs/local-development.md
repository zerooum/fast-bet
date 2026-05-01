# Fast-Bet — Local development

How to bring the project up on a developer machine.

## Prerequisites

| Tool                | Version                | Notes                                       |
|---------------------|------------------------|---------------------------------------------|
| Docker Engine       | 24+                    | Must include Compose v2 (`docker compose`). |
| GNU Make            | 4+                     | `make help` from the repo root.             |
| JDK                 | 21 (LTS)               | Quarkus services target Java 21.            |
| Maven               | 3.9+                   | Or use the Maven Wrapper inside each service when available. |
| Rust toolchain      | stable (rustup)        | Gateway is built with `cargo`.              |
| Node.js             | 20 LTS+                | Frontend (Angular + Ionic).                 |
| `psql` (optional)   | 16+                    | Only needed if you want to connect from the host. |

> Phase 1 has no production deployment story; everything runs locally.

## Bringing up the infrastructure

```bash
# 1. clone
git clone <this-repo> fast-bet
cd fast-bet

# 2. configure local secrets
cp infra/.env.example infra/.env
# edit infra/.env and replace every "change-me-*" value

# 3. start postgres + kafka + redis (waits for healthchecks)
make up

# 4. verify everything is reachable
make smoke
```

If `make up` returns successfully, all three healthchecks (Postgres, Kafka,
Redis) reported green. If `make smoke` prints `fast-bet smoke: ALL OK`, the
service-role logins, Kafka topic CRUD, and Redis SET/GET/DEL all work.

## Day-to-day commands

```bash
make ps            # show running services + health
make logs          # tail logs across all services
make psql          # psql shell as the superuser (inside the container)
make kafka-topics ARGS="--list"   # list topics
make redis-cli     # redis-cli inside the container
make down          # stop containers, keep volumes
make clean         # stop AND remove volumes (asks for confirmation)
```

## Where things will live

- `services/identity`, `services/catalog`, `services/wallet`, `services/bets`
  — Quarkus services. Each one is created by its own vertical-slice change
  (`add-user-signup`, `add-event-catalog`, ...).
- `gateway/` — Rust API gateway, also created by an upcoming slice.
- `frontend/` — Angular + Ionic application.
- `docs/` — architectural docs (this folder).
- `openspec/` — change proposals and capability specs.

The current change (`init-fastbet-phase1`) only delivers Phase 0
(infrastructure + principles). The vertical slices listed in
[roadmap.md](roadmap.md) populate `services/`, `gateway/`, and `frontend/`.

## Trust boundary in local vs prod-like topologies

The Gateway is the supported entry point for clients. Use
`http://localhost:8080/signup` for all signup smoke tests and local
development. The Gateway injects `X-Edge-Token` on every upstream call to
Identity, so requests through the Gateway just work.

In `infra/docker-compose.yaml` business services (currently Identity) also
publish their port on `localhost` for diagnostics, but direct calls to
`http://localhost:8081/signup` now require the `X-Edge-Token` header.
Forbidden-header checks (`Authorization`, `X-User-*`) still return
`400 Bad Request` without an edge token because that guard fires first.

Each business service enforces these public-route protections at its own edge:

- **`EdgeTokenFilter`**: requires a valid `X-Edge-Token` on every business
  API request. Missing or wrong tokens → `401 Unauthorized`. Configure
  `IDENTITY_EDGE_TOKEN` in both the Gateway and Identity (see
  `infra/.env.example` and `gateway/.env.example`). Quarkus management
  endpoints remain exempt.
- **`PublicEndpointGuardFilter`**: forbidden auth headers (`Authorization`,
  `X-User-*`) on public paths → `400 Bad Request`.
- **`RedisSignupRateLimiter`**: per-IP rate limit shared across replicas via
  Redis, mirroring the gateway's limit.
- **`TrustedProxyResolver`**: `X-Forwarded-For` only honored from CIDRs in
  `IDENTITY_TRUSTED_PROXIES`.

In prod-like topologies, keep business services internal: remove the
`ports:` block on `identity` (or use the `gateway-only` compose profile)
and set `IDENTITY_TRUSTED_PROXIES` to the gateway's address.

## Troubleshooting

- **Port already in use** — override `POSTGRES_PORT`, `KAFKA_PORT`, or
  `REDIS_PORT` in `infra/.env`.
- **`make smoke` Postgres failures** — `infra/postgres/init/01-databases.sh`
  only runs on the **first** start of a Postgres volume. After changing
  passwords, run `make clean` and `make up` again.
- **Kafka takes a long time to become healthy** — KRaft can need ~20s on a
  cold start. `make up --wait` will wait for the healthcheck.
- **Redis appendonly file warnings** — safe; we enabled AOF in
  `docker-compose.yaml` for durability of the local volume.
