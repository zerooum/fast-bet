# wallet Specification

## Purpose
TBD - created by archiving change init-fastbet-phase1. Update Purpose after archive.
## Requirements
### Requirement: Append-only ledger as source of truth
The Wallet service SHALL store every monetary movement as an append-only entry in a `wallet_ledger` table. Entries MUST NOT be updated or deleted. Reversals MUST be expressed as new compensating entries that reference the original entry.

#### Scenario: Reversing a failed deposit
- **WHEN** a deposit moves from `PENDING` to `FAILED` after partial bookkeeping
- **THEN** any prior credit MUST be reversed by appending a compensating entry, never by mutating the original entry

### Requirement: Balance derived from ledger
A user's available balance SHALL be derived from the sum of their ledger entries. Implementations MAY materialize a per-user balance projection for performance, but the ledger MUST remain the source of truth and the projection MUST be reproducible from the ledger.

#### Scenario: Projection drift detection
- **WHEN** a reconciliation routine compares the materialized balance to `SUM(amount)` of the ledger
- **THEN** the values MUST be equal; any discrepancy is a defect

### Requirement: Decimal precision for monetary values
All monetary fields SHALL use a fixed-precision decimal type with at least 4 decimal places (e.g., `numeric(19,4)`). Floating-point types MUST NOT be used for any monetary field anywhere in the Wallet service.

#### Scenario: API receives floating-point amount
- **WHEN** an API receives a JSON number that cannot be safely represented as decimal
- **THEN** the value MUST be parsed as a decimal string at the boundary or rejected

### Requirement: Concurrent debits cannot overdraft
The Wallet service SHALL guarantee that concurrent debits against the same user cannot result in a negative balance. Implementations MUST use row-level locking (`SELECT ... FOR UPDATE`) on the user record or an equivalent serializable strategy.

#### Scenario: Two concurrent bets exceeding balance
- **WHEN** two bets, each smaller than the balance but together exceeding it, are processed concurrently for the same user
- **THEN** exactly one MUST succeed and the other MUST be rejected with insufficient-funds; the resulting balance MUST NOT be negative

### Requirement: Hexagonal port for payment gateways
The Wallet service SHALL define a domain port `PaymentGateway` with operations `charge`, `payout`, and `status`. Use cases (deposit, withdrawal) MUST depend only on the port. Provider integrations (e.g., Stripe, PIX, Fake) MUST be adapters that implement the port and are selected via configuration without changes to use cases.

#### Scenario: Switching adapters
- **WHEN** the configured payment provider is changed (e.g., from `fake` to `stripe`)
- **THEN** no change is required in the use cases; only adapter wiring/configuration changes

### Requirement: Asynchronous deposit and withdrawal lifecycle
Both deposits and withdrawals SHALL follow an asynchronous lifecycle with statuses `PENDING → COMPLETED` or `PENDING → FAILED`. The lifecycle MUST be the same regardless of which `PaymentGateway` adapter is configured, including the Fake adapter.

#### Scenario: Fake adapter completes a deposit
- **WHEN** a deposit is requested against the Fake adapter
- **THEN** the deposit is created in `PENDING` and a separate process transitions it to `COMPLETED`, just as a real provider would via webhook

### Requirement: Outbox-backed event publication
Whenever the Wallet service writes a state change that other services must react to (e.g., funds reserved/rejected, deposit completed), it SHALL persist the corresponding event in an `outbox` table within the same database transaction. A separate relay MUST publish outbox entries to Kafka and mark them as published.

#### Scenario: Crash between commit and publish
- **WHEN** the service crashes after committing a state change but before publishing the corresponding event
- **THEN** on restart the outbox relay MUST publish the pending event; no event is lost

### Requirement: Withdrawals require confirmed funds
Withdrawals SHALL only be initiated when the user's available balance covers the requested amount at the moment of request. The amount MUST be debited (reserved) at the moment the withdrawal enters `PENDING`; on `FAILED`, the reserved amount MUST be returned to the user via a compensating ledger entry.

#### Scenario: Withdrawal fails after reservation
- **WHEN** a withdrawal in `PENDING` transitions to `FAILED`
- **THEN** a compensating credit entry is appended so the user's balance returns to the pre-reservation amount

