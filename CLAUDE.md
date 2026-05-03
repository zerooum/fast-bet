# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape

Fast-Bet is a study/portfolio sports-betting system organized as polyglot microservices behind a single Rust gateway. Phase 1 scope (and the only thing implemented or planned right now) is in `docs/roadmap.md`; bet settlement, real payments, 2FA, and HA are explicitly out of scope.

```
frontend/  Angular + Ionic (not yet populated)
gateway/   Rust (axum) — JWT termination, rate-limit, role routing
services/  Quarkus 3 + Java 21 business services (identity is the only one live)
infra/     docker-compose stack: Postgres 16, Kafka (KRaft), Redis 7
openspec/  Spec-driven change proposals; archived changes under changes/archive/
docs/      architecture.md, conventions.md, local-development.md, roadmap.md
```

The authoritative architectural decisions live in `docs/architecture.md` and `docs/conventions.md`. `openspec/specs/<context>/spec.md` is the capability spec for each bounded context.

## Common commands

Local infra (from repo root, requires `infra/.env` — copy from `infra/.env.example`):

```bash
make up                          # start postgres + kafka + redis (waits for healthchecks)
make down                        # stop, keep volumes
make clean                       # stop AND drop volumes (asks for "yes")
make smoke                       # run infra/scripts/smoke.sh
make psql | make redis-cli       # shells into the respective container
make kafka-topics ARGS="--list"
```

Identity service (Quarkus, Java 21, Maven — `services/identity/`):

```bash
mvn -pl services/identity verify                 # full build + tests + IT (from repo root)
cd services/identity && mvn quarkus:dev          # live-reload dev mode on :8081
mvn -pl services/identity test \
    -Dtest=RedisSignupRateLimiterFailOpenTest    # single test
mvn -pl services/identity verify -DskipITs       # skip integration tests
```

Tests use Quarkus DevServices (Postgres + Redis containers spun up automatically) and an in-memory Kafka connector — a Docker daemon is required, but `make up` is not.

Gateway (Rust, `gateway/`):

```bash
cd gateway && cargo run          # needs make up + identity running
cargo test                       # tests/ uses an in-memory rate limiter + stub identity, no infra needed
```

## Architecture invariants (do not silently break)

These are load-bearing; deviations require updating `docs/architecture.md` or the relevant OpenSpec change.

- **Database-per-service on a single Postgres instance.** Databases `identity`, `catalog`, `wallet`, `bets`; each owned by role `<svc>_svc` with `GRANT ALL` only on its own DB. No cross-DB joins, no service connecting to a DB it does not own. `infra/postgres/init/01-databases.sh` provisions these on first boot only — change it and you must `make clean`.
- **Outbox + Kafka for cross-service side effects.** Event-producing state changes write to a local `outbox` table in the same transaction; a relay (`@Scheduled` worker in identity) publishes to Kafka. HTTP between services is reserved for synchronous *reads* (e.g. bets validating a selection against catalog).
- **JWT RS256, issued by identity, validated by the gateway.** Claims: `sub`, `roles ⊂ {USER, SCHEDULER, ODD_MAKER, ADMIN}`, `iat`, `exp`. Services trust gateway-validated tokens; they do not re-verify signatures.
- **Wallet ledger is append-only.** Signed `numeric(19,4)` amounts; balance = `SUM(amount)`. Reversals are compensating entries (`BET_REFUND`), never `UPDATE`s.
- **Snapshot odds at bet creation.** `bet.odd_value` / `bet.selection_name` are copied; bets never re-resolves them later.
- **Bet saga: `RESERVED → CONFIRMED | REJECTED`.** Driven by `bets.bet-reserved` ↔ `wallet.funds-reserved`/`wallet.funds-rejected`.
- **Gateway is dumb.** Auth termination, rate-limit, role routing only. No business logic, no response composition.

## Conventions (`docs/conventions.md`)

- Kafka topics: `<context>.<event-name>` where event-name is kebab-case past tense (`bets.bet-reserved`, `wallet.funds-rejected`). Topics are not auto-created in prod-like config; each new event must explicitly provision its topic.
- Money: `numeric(19,4)` in Postgres, `BigDecimal` in Java.
- Public URLs through gateway: `/api/v1/<context>/<resource>`. Internal HTTP (when documented): `http://<service>:8080/internal/...`.

## Trust boundary — edge token (non-obvious)

The gateway is the supported entry point, but compose also publishes Identity on `localhost:8081` for diagnostics. To prevent direct-call bypass, Identity enforces its own defense-in-depth on every business request:

- `EdgeTokenFilter` requires `X-Edge-Token` matching `IDENTITY_EDGE_TOKEN` (must be set in both gateway and identity envs; **startup fails if blank**). Quarkus management endpoints (`/q/health/*`, `/q/metrics`) are exempt.
- `PublicEndpointGuardFilter` rejects `Authorization` / `X-User-*` on public paths with `400 forbidden_header` — runs *before* edge-token auth, so forged-auth probes still get the documented 400.
- `RedisSignupRateLimiter` mirrors the gateway's `5 req / 60s` per-IP limit, keyed under `ratelimit:identity:signup:`.
- `TrustedProxyResolver` only honors `X-Forwarded-For` from peers in `IDENTITY_TRUSTED_PROXIES` (CIDRs; default empty).

When testing direct calls to `:8081`, include `-H "X-Edge-Token: $(grep ^IDENTITY_EDGE_TOKEN infra/.env | cut -d= -f2-)"` or use the gateway at `:8080`.

## OpenSpec workflow

Work proceeds as vertical-slice changes under `openspec/changes/<change-id>/` (proposal.md, design.md, tasks.md, deltas under specs/). Completed changes are moved to `openspec/changes/archive/<date>-<change-id>/` and the capability specs in `openspec/specs/<context>/spec.md` are updated. Branch names should match the change id (e.g. `add-wallet-deposit`).

The `.claude/skills/openspec-*` skills and `.claude/commands/opsx/{propose,explore,apply,archive}.md` slash commands document the flow.
