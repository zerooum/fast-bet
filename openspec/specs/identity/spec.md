# identity Specification

## Purpose
TBD - created by archiving change init-fastbet-phase1. Update Purpose after archive.
## Requirements
### Requirement: Four canonical roles
The Identity service SHALL recognize exactly four roles in Phase 1: `USER`, `SCHEDULER`, `ODD_MAKER`, `ADMIN`. Every authenticated principal MUST carry at least one role; multiple roles per user are permitted.

#### Scenario: Newly signed-up user
- **WHEN** a person completes the public signup flow
- **THEN** the resulting account is granted exactly the role `USER`

#### Scenario: Unknown role requested
- **WHEN** any operation tries to assign a role not in the canonical set
- **THEN** the operation MUST be rejected

### Requirement: Public signup is restricted to common users
The public signup endpoint SHALL be the only endpoint that can create accounts without authentication, and it SHALL only create accounts with role `USER`. Staff accounts (`SCHEDULER`, `ODD_MAKER`, `ADMIN`) MUST NOT be creatable via public signup.

#### Scenario: Public signup with elevated role payload
- **WHEN** an unauthenticated request to the signup endpoint includes a payload attempting to set a role other than `USER`
- **THEN** the request MUST be rejected or the role field MUST be ignored, and the resulting account has role `USER` only

### Requirement: Only ADMIN can create staff accounts
The Identity service SHALL allow creation of `SCHEDULER` and `ODD_MAKER` accounts only by principals with role `ADMIN`. The creation of additional `ADMIN` accounts MUST also require an existing `ADMIN`.

#### Scenario: Non-admin tries to create staff
- **WHEN** a `USER`, `SCHEDULER`, or `ODD_MAKER` calls the staff-creation endpoint
- **THEN** the request MUST be rejected with HTTP 403

### Requirement: Bootstrap of the first ADMIN
The Identity service SHALL provide a deterministic mechanism to create the first `ADMIN` at system bootstrap time, configured via environment variables. This mechanism MUST run only when no `ADMIN` exists yet.

#### Scenario: First boot
- **WHEN** the Identity service starts and the database has no `ADMIN`
- **THEN** an `ADMIN` is created from the configured bootstrap credentials

#### Scenario: Subsequent boots
- **WHEN** the Identity service starts and at least one `ADMIN` already exists
- **THEN** the bootstrap mechanism MUST NOT create or modify any account

### Requirement: JWT issuance with role claims
On successful authentication, the Identity service SHALL issue a signed JWT (RS256) containing at minimum the claims `sub` (user id), `roles` (array), `iat`, and `exp`. The signing key MUST be private to Identity; the public key MUST be distributed to the Gateway and downstream services for verification.

#### Scenario: Successful login
- **WHEN** a user authenticates with valid credentials
- **THEN** Identity returns a JWT containing the user id and the canonical roles

### Requirement: Credentials stored with strong password hashing
User passwords SHALL be stored using a memory-hard adaptive hash (Argon2id or bcrypt with cost ≥ 12). Plaintext passwords MUST NEVER be persisted or logged.

#### Scenario: Account creation
- **WHEN** any account is created (signup or admin-driven)
- **THEN** the password is stored only as its hash; the original value is never written to disk or logs

