# Fast-Bet — Roadmap

Phase 1 is delivered as a sequence of **vertical-slice OpenSpec changes**.
Each slice adds one end-to-end use case (frontend → gateway → service →
DB / Kafka) on top of the foundation laid by `init-fastbet-phase1`.

The slices are intended to be implemented **in order**. Later slices assume
the artifacts of earlier ones (roles, topics, ledger, etc.).

| # | Change id              | Goal                                                                                                  |
|---|------------------------|-------------------------------------------------------------------------------------------------------|
| 0 | `init-fastbet-phase1`  | This change. Repo structure, Phase 0 infra, architectural principles, capability skeletons.           |
| 1 | `add-user-signup`      | **In progress.** Public user signup. Identity service skeleton, Argon2id hashing, outbox + Kafka producer for `identity.user-registered`, public `POST /signup` route on the gateway with per-IP rate limit. Login / JWT issuance is deferred to a follow-up slice. |
| 1.5 | `harden-signup-bypass` | **Defense-in-depth on Identity itself.** Reject `Authorization` / `X-User-*` on public paths (`PublicEndpointGuardFilter`), enforce a per-IP Redis rate limit at Identity (mirrors the gateway), and add `IDENTITY_TRUSTED_PROXIES` for safe `X-Forwarded-For` resolution so direct `:8081` calls cannot bypass gateway protections. |
| 2 | `add-staff-management` | Admin creates `SCHEDULER` and `ODD_MAKER` accounts. Introduces role-based routing in the gateway.     |
| 3 | `add-event-catalog`    | Scheduler creates events/markets/selections; odd-maker adjusts odds. Suspension semantics.            |
| 4 | `add-wallet-deposit`   | User deposits via the `Fake` payment adapter. First Outbox + Kafka producer. Async `PENDING → COMPLETED`. |
| 5 | `add-betting`          | User places a bet. First saga: `bets.bet-reserved` ↔ `wallet.funds-reserved`/`-rejected`. No settlement. |
| 6 | `add-wallet-withdrawal`| User withdraws funds. Second async payment flow; reuses ledger and `PaymentGateway` port.             |

## Out of roadmap (Phase 2+)

- Bet settlement (winners, losers, void).
- Real payment gateway integration (Stripe, PIX, ...).
- 2FA, password reset, email confirmation.
- Real-time odd push (WebSocket / SSE).
- Operational concerns: high availability, replication, observability stack,
  CI/CD pipelines, secrets management, anti-fraud.

Each Phase 2+ item will get its own change proposal when prioritized.
