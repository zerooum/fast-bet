# Fast-Bet — Identity service

Quarkus 3 / Java 21 service that owns user accounts, public signup, login,
JWT issuance, and the `identity.user-registered` outbox event.

## Run locally against the compose stack

From the repository root:

```bash
# 1) bring up Postgres / Kafka / Redis
make up

# 2) export the identity DB password (matching infra/.env)
export IDENTITY_DB_USER=identity_svc
export IDENTITY_DB_PASSWORD="$(grep ^IDENTITY_SVC_PASSWORD infra/.env | cut -d= -f2-)"
export IDENTITY_DB_URL=jdbc:postgresql://localhost:5432/identity
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
# Edge-token must match IDENTITY_EDGE_TOKEN in gateway/.env
export IDENTITY_EDGE_TOKEN="$(grep ^IDENTITY_EDGE_TOKEN infra/.env | cut -d= -f2-)"

# 3) run in dev mode (live reload)
cd services/identity
mvn quarkus:dev
```

The service starts on `http://localhost:8081` by default and applies Flyway
migrations on startup.

> **Supported public signup path:** go through the Gateway at
> `http://localhost:8080/signup`. Direct access to `http://localhost:8081/signup`
> requires `X-Edge-Token` for business logic to run (see below).

## Build & test

```bash
mvn -pl services/identity verify
```

## Endpoints

| Method | Path       | Auth   | Description                               |
| ------ | ---------- | ------ | ----------------------------------------- |
| POST   | `/signup`  | public | Create a `USER` account (rate-limited at the gateway **and** at Identity) |
| GET    | `/q/health/ready` | public | Readiness probe                          |
| GET    | `/q/metrics` | internal | Prometheus metrics                       |

See [openspec/specs/identity/spec.md](../../openspec/specs/identity/spec.md)
for the authoritative capability spec.

## Trust boundary & direct-call hardening

The Gateway is the supported entry point. It strips `Authorization` /
`X-User-*` from public routes, injects the shared `X-Edge-Token`, and applies
a per-IP rate limit. As defense-in-depth, Identity also enforces those rules
itself so a misconfigured deployment cannot bypass them:

- `EdgeTokenFilter` requires a valid `X-Edge-Token` header on every business
  API request. Missing or wrong tokens are rejected with `401 Unauthorized`
  and `{"error":"edge_unauthorized"}` before business logic runs.
  Configure `IDENTITY_EDGE_TOKEN` (must match `IDENTITY_EDGE_TOKEN` in the
  Gateway). Startup fails if the value is absent or blank.
  Quarkus management endpoints (`/q/health/*`, `/q/metrics`) are exempt.
- `PublicEndpointGuardFilter` rejects requests to public paths
  (`fastbet.identity.public-paths`, default `/signup`) that carry
  `Authorization`, `X-User-Id`, or `X-User-Roles` with `400 Bad Request`.
  This check runs **before** edge-token authentication so direct callers with
  forged auth headers still receive the documented `400 forbidden_header`.
  `X-Edge-Token` is not a forbidden header.
- `RedisSignupRateLimiter` applies the same `5 req / 60 s` per-IP fixed
  window as the gateway (key prefix `ratelimit:identity:signup:`).
- `TrustedProxyResolver` only honors `X-Forwarded-For` when the TCP peer
  is inside `IDENTITY_TRUSTED_PROXIES` (CIDR list). Defaults to empty
  — set it to the gateway's pod/container CIDR in compose / k8s.

### Diagnostic direct calls

When Identity is accessible on `localhost:8081` (the default dev compose
profile) you can call it directly for diagnostics, but **you must provide the
edge token**:

```bash
# Successful signup via direct call (diagnostic only — use Gateway in normal flows)
curl -i -X POST http://localhost:8081/signup \
  -H 'content-type: application/json' \
  -H "X-Edge-Token: $(grep ^IDENTITY_EDGE_TOKEN infra/.env | cut -d= -f2-)" \
  -d '{"email":"test@example.com","password":"Password1234"}'

# Forbidden-header check does NOT require the edge token (guard fires first)
curl -i -X POST http://localhost:8081/signup \
  -H 'content-type: application/json' \
  -H 'Authorization: Bearer spoofed' \
  -d '{"email":"test@example.com","password":"Password1234"}'
# → 400 forbidden_header
```

### Local port publication

For convenience, `infra/docker-compose.yaml` publishes Identity on
`localhost:8081` so `infra/scripts/signup-smoke.sh` can hit it directly.
In prod-like topologies you want to keep Identity inside the internal
network only:

- Remove the `ports:` block from the `identity` service (or run
  `docker compose --profile gateway-only up`, which omits the publication
  by design — see the comment in the compose file).
- Set `IDENTITY_TRUSTED_PROXIES` to the gateway's address(es) so
  rate-limit keys are derived from real client IPs forwarded as
  `X-Forwarded-For`.
