# Fast-Bet — Architecture overview

This document summarizes the architectural principles consolidated in
[openspec/changes/init-fastbet-phase1/design.md](../openspec/changes/init-fastbet-phase1/design.md).
It is intentionally short and prescriptive: each vertical-slice change must
respect these principles or explicitly justify (and document) any deviation.

## High-level shape

```
Frontend (Angular + Ionic)
        │  HTTPS
        ▼
API Gateway (Rust)              ◄── validates JWT, rate-limits, routes by role
        │  HTTP
        ▼
┌──────────┬──────────┬─────────┬────────┐
│ identity │ catalog  │ wallet  │ bets   │   Quarkus business services
└──────────┴──────────┴─────────┴────────┘
        │                              ▲
        │  Outbox + Kafka events       │
        ▼                              │
                 Apache Kafka  ────────┘

Single Postgres instance (4 isolated databases)
Redis (rate-limit, ephemeral state)
```

## Core principles

1. **Microservices from day one.** `identity`, `catalog`, `wallet`, `bets` are
   independently deployable Quarkus services. The Rust gateway is the only
   public entry point.
2. **Database-per-service on a single Postgres instance.** Each service owns
   exactly one database (`identity`, `catalog`, `wallet`, `bets`) accessed by
   its own role (`<svc>_svc`) with `GRANT ALL` only on that database. No
   cross-database joins, no cross-service direct DB access.
3. **Outbox + Kafka for cross-service events.** Every service that publishes
   an event writes it to its local `outbox` table inside the same DB
   transaction as the state change; a relay (Debezium or a dedicated worker)
   reads the outbox and publishes to Kafka. Atomicity > convenience.
4. **HTTP only for synchronous validations.** Bets may call Catalog
   synchronously to validate a selection and capture an odd snapshot.
   Frontend → Gateway → service is HTTP. Anything that produces a side effect
   in another service goes through Kafka.
5. **Snapshot the odd at bet creation.** `bet.odd_value` and
   `bet.selection_name` are copied at creation. Bets never re-resolves the odd
   from Catalog after the fact.
6. **Wallet is an append-only ledger.** `wallet_ledger` rows are immutable;
   balance is derived (`SUM(amount)`) or projected. Reversals are compensating
   entries (`BET_REFUND`, etc.), never `UPDATE`s.
7. **Hexagonal port for payments.** The Wallet domain defines a
   `PaymentGateway` interface with `charge`, `payout`, `status`. Phase 1 ships
   only the `FakePaymentGateway` adapter. Real adapters (Stripe, PIX, ...)
   plug in via CDI without touching the use cases.
8. **Deposits and withdrawals are asynchronous from day one.** Operations
   begin `PENDING` and transition to `COMPLETED` or `FAILED` via callback
   (webhook in real adapters, internal job in `Fake`). Frontend polls the
   resource on Phase 1.
9. **Bet saga: `RESERVED → CONFIRMED | REJECTED`.** Bets publishes
   `bets.bet-reserved`; Wallet reserves funds and publishes
   `wallet.funds-reserved` or `wallet.funds-rejected`; Bets transitions
   accordingly. Settlement is **out of scope** for Phase 1.
10. **JWT RS256 issued by Identity, validated everywhere.** Claims: `sub`,
    `roles`, `iat`, `exp`. The Gateway validates the signature with the
    distributed public key and routes by role. Services trust the validated
    token without round-tripping to Identity.
11. **Gateway has a single responsibility.** Auth termination, rate-limit
    (Redis-backed), role-based routing. **No** business logic, **no** response
    composition. Composition will be a BFF in a later phase if needed.
12. **Bootstrap admin via env vars.** Identity Flyway migrations seed the
    first `ADMIN` from `FASTBET_BOOTSTRAP_ADMIN_EMAIL` /
    `FASTBET_BOOTSTRAP_ADMIN_PASSWORD`. Without this there would be no way to
    create staff (scheduler, odd-maker).

## Out of scope for Phase 1

- Bet settlement (winners/losers).
- Real payment gateway integration.
- 2FA, password recovery, email confirmation.
- Real-time odd push to clients.
- High availability, replication, multi-region, anti-fraud.

See [openspec/changes/init-fastbet-phase1/design.md](../openspec/changes/init-fastbet-phase1/design.md)
for the full reasoning, alternatives considered, and risks.
