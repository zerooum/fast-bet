## ADDED Requirements

### Requirement: Public signup HTTP contract
The Identity service SHALL expose `POST /signup` as a public, unauthenticated endpoint that accepts a JSON body with `email` (required), `password` (required), and `displayName` (optional). On success it MUST respond `201 Created` with a sanitized JSON body containing `id`, `email`, `displayName`, `roles`, and `createdAt`, and a `Location` header pointing at the new user resource. The response MUST NOT include the password, password hash, or any other credential material.

#### Scenario: Successful signup
- **WHEN** an unauthenticated client sends a valid `POST /signup` payload with a previously unused email
- **THEN** the service responds `201 Created` with the sanitized user body, sets `Location: /users/{id}`, and the response body's `roles` field equals `["USER"]`

#### Scenario: Response never leaks credentials
- **WHEN** any signup response is produced (success or error)
- **THEN** the response body and headers MUST NOT contain the raw password or the stored password hash

### Requirement: Signup input validation
The Identity service SHALL validate signup input before any persistence side effect. `email` MUST be a syntactically valid address (Hibernate Validator `@Email`) of at most 254 characters, normalized to lowercase before storage. `password` MUST be 12..128 characters and contain at least one letter and one digit. `displayName`, when provided, MUST be 1..60 characters after trimming; when omitted, the service MUST default it to the local-part of the email. Validation failures MUST yield `400 Bad Request` with a structured error body of the form `{ "error": "validation", "fields": { "<field>": "<message>" } }`.

#### Scenario: Invalid email format
- **WHEN** the client submits an email that fails RFC-5322 syntax validation
- **THEN** the service responds `400` with `error = "validation"` and a `fields.email` message, and no row is written to `user_account`

#### Scenario: Weak password
- **WHEN** the client submits a password shorter than 12 characters or missing either a letter or a digit
- **THEN** the service responds `400` with `error = "validation"` and a `fields.password` message

#### Scenario: Display name defaults from email
- **WHEN** the client submits a valid signup body without `displayName`
- **THEN** the persisted `display_name` equals the local-part of the submitted email (the substring before `@`)

### Requirement: Email uniqueness is case-insensitive
The Identity service SHALL treat email addresses as case-insensitive identifiers and reject signups whose normalized email already exists. Conflicts MUST yield `409 Conflict` with body `{ "error": "email_taken" }`.

#### Scenario: Duplicate email differing only by case
- **WHEN** an account with email `alice@example.com` exists and a new signup arrives with `Alice@Example.COM`
- **THEN** the service responds `409 Conflict` with `error = "email_taken"` and no second row is created

### Requirement: Role payload on public signup is ignored
The Identity service SHALL ignore any role-bearing field in a public signup payload. The created account MUST have exactly the role `USER` regardless of client input, and the service MUST NOT echo any client-supplied role in the response.

#### Scenario: Client tries to escalate via payload
- **WHEN** an unauthenticated client submits a signup payload containing `"roles": ["ADMIN"]` or `"role": "ADMIN"`
- **THEN** the field is dropped before persistence and the resulting account's `roles` equals `["USER"]`

### Requirement: Passwords stored as Argon2id PHC strings
The Identity service SHALL hash passwords with Argon2id using parameters at least as strong as `memory=65536 KiB, iterations=3, parallelism=1, saltLength=16, hashLength=32`, and SHALL persist the full PHC-encoded string (including algorithm id, parameters, and salt) in `user_account.password_hash`. The raw password MUST NEVER be persisted, logged, or returned over the wire.

#### Scenario: Password column shape after signup
- **WHEN** a successful signup persists a new account
- **THEN** `password_hash` starts with `$argon2id$` and contains the encoded parameters, salt, and hash

#### Scenario: Logs do not contain the password
- **WHEN** the service logs a signup attempt at any level
- **THEN** the log line MUST NOT contain the raw password value, and MAY include a SHA-256 truncated hash of the email instead of the email itself

### Requirement: User-registered outbox event
On every successful signup, the Identity service SHALL append exactly one `identity.user-registered` event to the local `outbox` table within the same database transaction as the `user_account` insert. The event payload MUST include `eventType = "identity.user-registered"`, `version = 1`, the new `userId`, the normalized `email`, the resolved `displayName`, and the `registeredAt` timestamp. The event MUST be relayed to the Kafka topic `identity.user-registered` and the outbox row MUST be marked `published_at` only after a successful Kafka acknowledgement.

#### Scenario: Atomic outbox write
- **WHEN** a signup transaction inserts a new `user_account` row
- **THEN** exactly one `outbox` row with `event_type = "identity.user-registered"` referencing that user is inserted in the same transaction; if either write fails, neither is committed

#### Scenario: Event reaches Kafka
- **WHEN** the outbox relay processes an unpublished `identity.user-registered` row
- **THEN** the event is published to topic `identity.user-registered` with the payload defined above and the row's `published_at` is set to the publish time

### Requirement: Signup abuse is rate-limited
The Identity service SHALL be protected against signup abuse by a per-IP rate limit applied at the Gateway (see the gateway capability) AND SHALL apply a server-side bulkhead capping concurrent signup hashing work to prevent CPU/memory exhaustion from Argon2 cost.

#### Scenario: Excess concurrent signups
- **WHEN** more concurrent signup requests arrive than the configured bulkhead allows
- **THEN** excess requests are queued or rejected with `503 Service Unavailable`, and the service does not crash or evict in-flight requests
