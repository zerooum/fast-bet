## Why

Phase 1 specs declare that public signup is the only unauthenticated path that
creates accounts (with role `USER`), but no concrete HTTP contract,
validation rules, or implementation exists yet under `services/`. We need to
turn that abstract requirement into a usable endpoint so the frontend can
onboard users and the rest of Phase 1 (login, betting, wallet) can be exercised
end-to-end.

## What Changes

- Add a public `POST /signup` endpoint exposed by the Identity Quarkus service
  that creates a `USER` account from `email` + `password` (+ optional
  `displayName`).
- Add input validation (RFC 5322-ish email format, password minimum strength,
  display name length) and uniqueness enforcement on `email`.
- Hash passwords with Argon2id at rest (consistent with the existing identity
  spec) and never log raw credentials.
- Emit an `identity.user-registered` event via the outbox so downstream
  services (Wallet) can provision per-user state when they come online.
- Expose the route as **public** through the Rust Gateway (no JWT required)
  with a per-IP rate limit to deter enumeration / abuse.
- Return a sanitized JSON response (`id`, `email`, `displayName`, `createdAt`,
  `roles: ["USER"]`) — never echo the password or hash.

## Capabilities

### New Capabilities
<!-- None: signup behavior is already covered by the identity capability. -->

### Modified Capabilities
- `identity`: add concrete signup HTTP contract, input validation rules,
  uniqueness constraints, and the user-registered outbox event.
- `gateway`: register `POST /signup` as a public, rate-limited route that
  forwards to the Identity service without requiring a JWT.

## Impact

- **Code**: new Identity service module under `services/identity/` (Quarkus,
  Flyway migration for `user_account` + `outbox`); new public route
  registration in `gateway/`.
- **APIs**: introduces `POST /signup` (public) and the
  `identity.user-registered` Kafka topic contract.
- **Data**: first migrations for the `identity` Postgres database — `user_account` and `outbox` tables.
- **Dependencies**: pulls in Argon2 (e.g. `de.mkammerer:argon2-jvm`),
  Hibernate Validator, and the existing Quarkus Kafka outbox stack.
- **Out of scope**: email verification, password recovery, captcha — these
  remain deferred per the Phase 1 design.
