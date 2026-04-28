# Fast-Bet — Conventions

Project-wide naming and protocol conventions. Vertical-slice changes MUST
follow these rules.

## Kafka topics

- Format: `<bounded-context>.<event-name>`
- `bounded-context` is one of: `identity`, `catalog`, `wallet`, `bets`.
- `event-name` is **kebab-case**, past tense, describes a fact that happened.
- Examples:
  - `bets.bet-reserved`
  - `wallet.funds-reserved`
  - `wallet.funds-rejected`
  - `wallet.deposit-completed`
  - `catalog.event-published`
- Topics are **not** auto-created. Each vertical-slice change that introduces
  a new event MUST also document and provision the topic explicitly.

## Postgres roles and databases

- One database per service: `identity`, `catalog`, `wallet`, `bets`.
- One role per service, named `<svc>_svc`: `identity_svc`, `catalog_svc`,
  `wallet_svc`, `bets_svc`.
- Each role has `GRANT ALL PRIVILEGES` only on its own database. `PUBLIC` is
  revoked from every service database.
- A service NEVER connects to a database it does not own. Cross-context data
  access happens via HTTP (synchronous read) or Kafka (asynchronous event).

## JWT

- Algorithm: **RS256**.
- Issuer: `identity` service. Public key distributed to all consumers.
- Required claims:
  - `sub` — user id (UUID).
  - `roles` — array of strings, subset of
    `["USER", "SCHEDULER", "ODD_MAKER", "ADMIN"]`.
  - `iat` — issued-at (unix seconds).
  - `exp` — expiration (unix seconds).
- The Gateway rejects tokens that fail signature, are expired, or lack
  required claims. Services trust validated tokens without re-checking the
  signature; they MAY re-check expiration on long-running operations.

## Money

- All monetary amounts use `numeric(19,4)` in Postgres and a fixed-precision
  decimal type in code (`BigDecimal` in Java).
- The Wallet ledger uses **signed** amounts: deposits and credits are
  positive, debits are negative. Balance = `SUM(amount)` per user.

## Service identifiers in URLs

- Public-facing URLs go through the gateway: `/api/v1/<context>/<resource>`.
  Example: `/api/v1/wallet/deposits/{id}`.
- Internal HTTP between services uses the bare service name and version-less
  paths: `http://catalog:8080/internal/selections/{id}` (only when documented
  in the relevant change).

## Git / commits

- Conventional Commits style is encouraged but not enforced in Phase 1.
- One change → one feature branch → one PR. The branch name SHOULD match the
  OpenSpec change id (e.g., `add-wallet-deposit`).
