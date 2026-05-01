## Context

Phase 1 architecture (see [docs/architecture.md](../../../docs/architecture.md))
already mandates: Quarkus business services behind a Rust gateway, JWT RS256
issued by Identity, database-per-service on a single Postgres instance, and an
outbox + Kafka pattern for cross-service events. The `identity` capability spec
already declares that public signup creates `USER`-only accounts and that
passwords are stored with Argon2id/bcrypt.

What's missing is a concrete, opinionated design for the first real Identity
endpoint: the HTTP contract, validation rules, persistence schema, hashing
parameters, outbox event shape, and gateway wiring. `services/` is currently
empty, so this change also bootstraps the Identity service module.

## Goals / Non-Goals

**Goals:**
- Land a working `POST /signup` endpoint behind the Gateway, end-to-end, that
  creates a `USER` account and is safe against the most common abuse vectors
  (enumeration, weak passwords, role escalation, log leaks).
- Establish the Identity service skeleton (Quarkus module, Flyway migrations,
  Argon2 hasher, outbox table) so subsequent identity changes (login,
  password change, admin staff creation) plug in without re-architecting.
- Define the `identity.user-registered` event contract so Wallet (and future
  consumers) can subscribe.

**Non-Goals:**
- Email verification, double opt-in, captcha, password recovery, 2FA — all
  explicitly out of Phase 1.
- Login / JWT issuance — covered by a separate change.
- Wallet's reaction to `identity.user-registered` — Wallet will subscribe in
  its own change.
- High availability, multi-region, anti-fraud signals.

## Decisions

### Decision 1: Endpoint contract

`POST /signup` accepts a JSON body:

```json
{ "email": "alice@example.com", "password": "...", "displayName": "Alice" }
```

Returns `201 Created` with a sanitized body and a `Location: /users/{id}`
header. `displayName` is optional; when omitted, the local-part of the email
is used. The endpoint NEVER returns the password hash, and no JWT is issued
here — the client must call `POST /login` next.

**Why not auto-login?** Keeps signup idempotent at the protocol level and
isolates the credential-issuance code path from account creation. Reduces
blast radius if either piece has a bug.

**Alternatives considered:**
- *Return a JWT immediately on signup.* Rejected: couples two security-
  sensitive flows and makes "signup created the account but auth failed"
  states ambiguous.
- *Use form-encoded body.* Rejected: every other Phase 1 endpoint is JSON.

### Decision 2: Password hashing — Argon2id

Use `de.mkammerer:argon2-jvm` (libsodium-backed) with parameters:
`memory=65536 (64 MiB)`, `iterations=3`, `parallelism=1`, `saltLength=16`,
`hashLength=32`. Encode the full PHC string in the column so parameters can
evolve without a migration.

**Why Argon2id over bcrypt?** The identity spec accepts either; Argon2id is
the OWASP-recommended default and resists GPU/ASIC attacks better. The
PHC-string encoding lets us bump cost later by re-hashing on next successful
login.

### Decision 3: Validation rules

- `email`: trimmed, lowercased before storage; must match a pragmatic RFC
  5322 subset (Hibernate Validator `@Email`); max 254 chars.
- `password`: min 12 chars, max 128 chars, must contain at least one letter
  and one digit. We deliberately avoid mandatory special-characters /
  mixed-case rules (NIST 800-63B guidance).
- `displayName`: optional, 1..60 chars, trimmed; defaults to email local-part.
- Any role field in the payload is **silently dropped** before persistence
  (defense in depth on top of the Gateway public-route policy).

Validation errors return `400` with a structured error body
(`{ "error": "validation", "fields": { "email": "must be a valid email" } }`).
Duplicate email returns `409 Conflict` with `{ "error": "email_taken" }`.

### Decision 4: Persistence schema (Identity DB)

```sql
CREATE TABLE user_account (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email         CITEXT NOT NULL UNIQUE,
  display_name  TEXT NOT NULL,
  password_hash TEXT NOT NULL,             -- full Argon2 PHC string
  roles         TEXT[] NOT NULL DEFAULT ARRAY['USER'],
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate     TEXT NOT NULL,
  aggregate_id  TEXT NOT NULL,
  event_type    TEXT NOT NULL,
  payload       JSONB NOT NULL,
  occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON outbox (occurred_at) WHERE published_at IS NULL;
```

`CITEXT` gives case-insensitive uniqueness without us re-implementing it in
application code. `roles` as `TEXT[]` mirrors the JWT `roles` claim shape;
a CHECK constraint ensures every entry is in the canonical set.

### Decision 5: Outbox event

Inside the same transaction as the `user_account` insert, write:

```json
{
  "eventType": "identity.user-registered",
  "version": 1,
  "userId": "<uuid>",
  "email": "alice@example.com",
  "displayName": "Alice",
  "registeredAt": "2026-04-28T12:34:56Z"
}
```

Topic: `identity.user-registered`. The relay (Phase 1: a scheduled Quarkus
worker polling the outbox; Debezium can replace it later without changing
producers) marks rows `published_at` after a successful Kafka send.

**Why not publish synchronously to Kafka?** Atomicity. We need the account
row and the event to commit or roll back together; the outbox is the only
pattern that gives that without distributed transactions.

### Decision 6: Gateway wiring

Register `POST /signup` as a public route in the Rust gateway: no JWT
required, but a per-IP rate limit of `5 requests / minute / IP` backed by
Redis (consistent with the gateway spec's rate-limit requirement). The
gateway forwards to `http://identity:8080/signup` with a generated
`X-Request-Id` propagated downstream.

### Decision 7: Logging & observability

- Log signup attempts at INFO with `email_hash` (SHA-256, first 12 hex
  chars) — never the raw email or password.
- Emit a metric `identity.signup.attempts{outcome=ok|conflict|invalid}`.
- Tag the request with the gateway-generated `request_id` for tracing.

## Risks / Trade-offs

- **[Username enumeration via 409]** → Mitigation: rate-limit per IP at the
  gateway and per-email at the service (sliding window in Redis). Phase 1
  accepts the tradeoff because UX clarity > enumeration resistance for a
  staging product; revisit before public launch.
- **[Argon2 memory pressure]** → 64 MiB × concurrent signups can starve a
  small container. Mitigation: cap inflight signup work via a Quarkus
  bulkhead (`@Bulkhead(value=4)`) on the use case; signup throughput is
  expected to be tiny.
- **[Outbox poller latency]** → A 5-second poll interval delays
  `user-registered` events. Mitigation: acceptable in Phase 1; switch to
  Debezium when latency matters.
- **[Roles array drift from JWT claim]** → Adding a role outside the
  canonical set would break the Gateway. Mitigation: DB CHECK constraint +
  unit test that asserts the canonical set matches the JWT issuer's
  whitelist.
- **[No email verification]** → Anyone can claim any email. Accepted for
  Phase 1; a future change adds a verification token table and a
  `verified_at` column.

## Migration Plan

1. Add the `services/identity` Quarkus module with Flyway migrations
   `V1__user_account.sql` and `V2__outbox.sql`.
2. Apply migrations against a fresh `identity` database — there is no
   existing data, so no rollback dance is required.
3. Deploy Identity service; verify `/q/health/ready` and that the outbox
   poller logs `0 unpublished events`.
4. Deploy Gateway with the new public route; smoke-test `POST /signup`
   end-to-end via the gateway.
5. **Rollback**: if signup is broken in production-like envs, route the
   public path back to a 503 in the gateway and `flyway undo` is unnecessary
   (table is empty / new). The change is additive; no existing flow depends
   on `user_account` yet.

## Open Questions

- Do we want a soft-delete column (`deleted_at`) on `user_account` from day
  one, or add it when admin user-deletion lands? *Tentative answer:* defer.
- Should the gateway rate-limit be per-IP only, or per-IP + per-email?
  *Tentative answer:* per-IP for Phase 1; per-email needs a body-aware limiter
  that we don't have yet.
