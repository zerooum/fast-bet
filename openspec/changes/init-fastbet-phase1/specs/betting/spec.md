## ADDED Requirements

### Requirement: Bets reference selections, not events
Each bet SHALL reference a single `Selection` from the Catalog service. References to `Event` or `Market` are not sufficient: bets are placed on outcomes (selections), and the selection identifier MUST be persisted with the bet.

#### Scenario: Bet without selection
- **WHEN** a bet creation request omits the selection identifier
- **THEN** the request MUST be rejected

### Requirement: Odd snapshot at bet creation
At bet creation time, the Bets service SHALL copy the current `odd_value` and `selection_name` from the Catalog into the bet record. The bet's odd MUST NOT be re-read from the Catalog later (e.g., at settlement); the snapshot is final.

#### Scenario: Odd changes after bet
- **WHEN** the `ODD_MAKER` changes the odd of a selection after a bet was created on it
- **THEN** the existing bet retains the odd from the moment of its creation

### Requirement: Synchronous validation against Catalog at creation
Before persisting a new bet, the Bets service SHALL validate the selection synchronously against the Catalog service via HTTP. The validation MUST confirm that the selection exists, the parent event is `OPEN`, and the selection is `ACTIVE`. The current odd MUST be the odd snapshotted into the bet.

#### Scenario: Stale odd in client request
- **WHEN** a client submits a bet declaring an expected odd that no longer matches the current Catalog odd
- **THEN** the bet creation MUST be rejected with an explicit `ODD_CHANGED` error so the client may decide to retry

### Requirement: Bet lifecycle in Phase 1
A bet SHALL pass through the statuses `RESERVED → CONFIRMED` (success) or `RESERVED → REJECTED` (failure). Settlement statuses (`WON`, `LOST`, `VOID`) are out of scope for Phase 1; confirmed bets remain pending in the business sense.

#### Scenario: Confirmed bet remains pending
- **WHEN** a bet has reached status `CONFIRMED`
- **THEN** there is no settlement workflow in Phase 1; the bet stays `CONFIRMED` indefinitely until a future phase

### Requirement: Distributed reservation saga via Kafka
Bet confirmation SHALL be implemented as a saga between Bets and Wallet over Kafka: Bets persists the bet as `RESERVED` and publishes a `bet-reserved` event via outbox; Wallet consumes the event, attempts the debit, and publishes either a `funds-reserved` or `funds-rejected` response event; Bets consumes the response and transitions the bet to `CONFIRMED` or `REJECTED`. Synchronous distributed transactions (e.g., 2PC) MUST NOT be used.

#### Scenario: Insufficient funds
- **WHEN** Wallet receives a `bet-reserved` event but the user has insufficient balance
- **THEN** Wallet publishes `funds-rejected` and Bets transitions the bet to `REJECTED`

#### Scenario: Successful reservation
- **WHEN** Wallet receives a `bet-reserved` event and the debit succeeds
- **THEN** Wallet publishes `funds-reserved` and Bets transitions the bet to `CONFIRMED`

### Requirement: Idempotent saga consumers
Both the Bets and Wallet consumers in the bet saga SHALL be idempotent. Re-delivery of the same event MUST NOT cause duplicate state transitions or duplicate ledger entries.

#### Scenario: Event re-delivered by Kafka
- **WHEN** the same `bet-reserved` event is delivered twice to the Wallet consumer
- **THEN** the Wallet performs the debit at most once and publishes the response at most once per logical bet

### Requirement: Outbox-backed publication from Bets
Whenever the Bets service writes a bet state change that other services must react to, it SHALL persist the corresponding event in an `outbox` table within the same database transaction, and a separate relay MUST publish to Kafka.

#### Scenario: Crash between bet write and publish
- **WHEN** the Bets service crashes after creating a `RESERVED` bet but before publishing
- **THEN** on restart the outbox relay MUST publish the pending `bet-reserved` event; no event is lost
