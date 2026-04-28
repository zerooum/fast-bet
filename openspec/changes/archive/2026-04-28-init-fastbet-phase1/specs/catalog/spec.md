## ADDED Requirements

### Requirement: Three-level catalog model
The Catalog service SHALL model the sports catalog with exactly three levels: `Event` contains one or more `Market`s, and each `Market` contains one or more `Selection`s. Each `Selection` carries a numeric `odd_value` and a human-readable `name`. Bets reference a `Selection`, never an `Event` or `Market` directly.

#### Scenario: Event without markets
- **WHEN** an `Event` is created
- **THEN** it MAY exist without `Market`s, but it MUST NOT be opened for betting until at least one `Market` with at least one `Selection` exists

### Requirement: Event lifecycle
Each `Event` SHALL have a status from the set `{ SCHEDULED, OPEN, CLOSED }`. Bets MAY only be placed while the `Event` is `OPEN`. Closing an `Event` is a terminal state for Phase 1; settlement is not part of Phase 1.

#### Scenario: Bet attempted on non-open event
- **WHEN** a bet is submitted referencing a `Selection` of an `Event` that is `SCHEDULED` or `CLOSED`
- **THEN** the request MUST be rejected

### Requirement: Selection suspension during odd edits
Each `Selection` SHALL have a status from the set `{ ACTIVE, SUSPENDED }`. While `SUSPENDED`, no new bet may reference the selection. Odd edits by `ODD_MAKER` MUST occur with the selection in `SUSPENDED`, transitioning back to `ACTIVE` after the edit is committed.

#### Scenario: Bet on suspended selection
- **WHEN** a bet is submitted referencing a `SUSPENDED` selection
- **THEN** the request MUST be rejected

### Requirement: Role-restricted catalog mutations
Creation and modification of `Event`s MUST require role `SCHEDULER` or `ADMIN`. Modification of `Selection.odd_value` and selection `status` MUST require role `ODD_MAKER` or `ADMIN`. Creation of `Market`s and `Selection`s MUST require role `SCHEDULER` or `ADMIN`.

#### Scenario: Common user attempts to create event
- **WHEN** a principal with role `USER` calls the event-creation endpoint
- **THEN** the request MUST be rejected with HTTP 403

### Requirement: Public read access to active catalog
The Catalog service SHALL expose read endpoints that are reachable by `USER` (and unauthenticated clients where applicable) listing `Event`s and their `Market`s/`Selection`s. Inactive or suspended elements MAY be filtered from public listings but MUST be retrievable by staff roles.

#### Scenario: Public listing
- **WHEN** an unauthenticated or `USER` client requests the list of events
- **THEN** the response includes only `OPEN` events with their `ACTIVE` selections

### Requirement: Settlement explicitly out of scope
The Catalog service SHALL NOT provide endpoints or workflows for recording event results or triggering bet settlement in Phase 1. Bets confirmed in Phase 1 remain pending until a future phase introduces settlement.

#### Scenario: Settlement endpoint requested
- **WHEN** any client (including admin) attempts to settle an event in Phase 1
- **THEN** no such endpoint exists; closing an event does not produce settlement events
