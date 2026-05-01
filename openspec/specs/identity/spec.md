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

### Requirement: Public endpoints reject authenticated-only headers

The Identity service SHALL classify a configurable set of paths (default
`/signup`) as public, and any request to a public path that carries
`Authorization`, `X-User-Id`, or `X-User-Roles` headers MUST be rejected
with `400 Bad Request` and body
`{"error":"forbidden_header","header":"<canonical-name>"}`. The check
MUST run before request matching so it cannot be bypassed by path
variations, and it MUST be header-name case-insensitive. This guard MUST run before
edge-token authentication for public paths, and `X-Edge-Token` MUST NOT be
treated as a forbidden public header.

#### Scenario: Direct call with Authorization header
- **WHEN** a client sends `POST /signup` directly to Identity with an
  `Authorization: Bearer ...` header (any value)
- **THEN** Identity responds `400 Bad Request` with
  `{"error":"forbidden_header","header":"Authorization"}` and no row is
  written to `user_account`

#### Scenario: Forged X-User-Id rejected
- **WHEN** a client sends `POST /signup` directly to Identity with
  `X-User-Id: 00000000-0000-0000-0000-000000000000`
- **THEN** Identity responds `400 Bad Request` with
  `{"error":"forbidden_header","header":"X-User-Id"}`

#### Scenario: Forged X-User-Roles rejected (mixed case)
- **WHEN** a client sends `POST /signup` with `x-user-roles: ADMIN`
- **THEN** Identity responds `400 Bad Request` with
  `{"error":"forbidden_header","header":"X-User-Roles"}` (case
  normalized in the response body)

#### Scenario: Public request with no forbidden headers proceeds to edge-token check
- **WHEN** a client sends a well-formed `POST /signup` body without any
  forbidden header
- **THEN** the public-endpoint guard does not reject the request and the
  edge-token guard decides whether the request may reach signup business logic

#### Scenario: Authenticated routes are unaffected
- **WHEN** a (future) authenticated route receives `Authorization` and
  `X-User-Id` headers from the gateway
- **THEN** the public-endpoint guard MUST NOT reject the request because the
  route's path is not in the configured public-paths list

#### Scenario: Gateway edge token is allowed on signup
- **WHEN** the Gateway forwards `POST /signup` with a valid `X-Edge-Token`
- **THEN** the public-endpoint guard MUST NOT reject the request because of
  the `X-Edge-Token` header

### Requirement: Business API requests require edge token
The Identity service SHALL require a `X-Edge-Token` header on every business
API request. The header value MUST match the configured `IDENTITY_EDGE_TOKEN`
secret using constant-time comparison. Requests with a missing or mismatched
edge token MUST receive `401 Unauthorized` with body `{"error":"edge_unauthorized"}`
before resource business logic, persistence, password hashing, or outbox
writes run. Quarkus management endpoints (`/q/health/*` and `/q/metrics`) MUST
remain exempt from this business API edge-token requirement.

#### Scenario: Signup missing edge token
- **WHEN** a caller sends `POST /signup` without forbidden auth headers and
  without `X-Edge-Token`
- **THEN** Identity responds `401 Unauthorized` with
  `{"error":"edge_unauthorized"}` and no signup business logic runs

#### Scenario: Signup with mismatched edge token
- **WHEN** a caller sends `POST /signup` with `X-Edge-Token: wrong`
- **THEN** Identity responds `401 Unauthorized` with
  `{"error":"edge_unauthorized"}` and no row is written to `user_account`

#### Scenario: Signup with valid edge token
- **WHEN** the Gateway forwards `POST /signup` with an `X-Edge-Token` value
  matching the configured `IDENTITY_EDGE_TOKEN`
- **THEN** Identity allows the request to continue to validation,
  rate-limiting, and signup business logic

#### Scenario: Health endpoint remains probeable
- **WHEN** a caller sends `GET /q/health/ready` without `X-Edge-Token`
- **THEN** Identity does not reject the request because of the edge-token
  requirement

#### Scenario: Blank edge-token configuration is rejected
- **WHEN** Identity starts in a non-test runtime profile with a missing or
  blank `IDENTITY_EDGE_TOKEN`
- **THEN** Identity fails startup before serving business API requests

### Requirement: Per-IP rate limit on /signup

The Identity service SHALL apply a per-resolved-client-IP fixed-window
rate limit on `POST /signup` of `5` requests per `60` seconds (both
configurable). State MUST be held in Redis under keys prefixed
`ratelimit:identity:signup:` so multiple Identity replicas share the
counter. Requests above the limit MUST receive `429 Too Many Requests`
with a `Retry-After` header carrying the remaining window in seconds and
body `{"error":"rate_limited"}`. The limiter MUST fail open if Redis is
unreachable: the request proceeds and a counter
`identity.signup.ratelimit.errors` is incremented.

#### Scenario: Sixth request from the same IP within the window
- **WHEN** a single client IP sends a 6th `POST /signup` within 60
  seconds (the first 5 having been allowed)
- **THEN** Identity responds `429 Too Many Requests` with
  `Retry-After: <seconds>` and body `{"error":"rate_limited"}`, and the
  request does not reach the signup use case

#### Scenario: Window reset
- **WHEN** more than `window-seconds` have elapsed since a key's first
  hit
- **THEN** the next request from that IP is allowed and the window
  restarts

#### Scenario: Counters are shared across replicas
- **WHEN** two Identity replicas both increment the same IP's counter
  via Redis
- **THEN** the combined count is what the limit applies to (single
  shared counter), not per-replica counts

#### Scenario: Redis outage does not block signups
- **WHEN** Redis is unreachable
- **THEN** `POST /signup` is processed (fail-open) and
  `identity.signup.ratelimit.errors` is incremented; a warning is
  logged with `request_id`

### Requirement: Trusted-proxy client IP resolution

The Identity service SHALL resolve the client IP for rate-limiting and
logging by inspecting `X-Forwarded-For` only when the TCP peer falls
within the `IDENTITY_TRUSTED_PROXIES` CIDR allow-list. Otherwise the TCP
peer address MUST be used and `X-Forwarded-For` MUST be ignored. The
allow-list defaults to empty (trust no proxy).

#### Scenario: Direct caller spoofs X-Forwarded-For
- **WHEN** an untrusted TCP peer sends `X-Forwarded-For: 1.2.3.4` on a
  signup request
- **THEN** the rate-limit key MUST use the TCP peer address, not
  `1.2.3.4`

#### Scenario: Trusted gateway forwards a real client IP
- **WHEN** the TCP peer matches an entry in `IDENTITY_TRUSTED_PROXIES`
  and `X-Forwarded-For: 203.0.113.7` is present
- **THEN** the rate-limit key uses `203.0.113.7`

#### Scenario: Trusted peer with no XFF
- **WHEN** the TCP peer is trusted but no `X-Forwarded-For` header is
  present
- **THEN** the rate-limit key falls back to the TCP peer address

### Requirement: Bounded concurrent signup hashing (carry-over)

The Identity service SHALL continue to bound concurrent signup hashing
work via a server-side bulkhead so a flood of valid requests cannot
exhaust CPU or memory. Excess requests beyond the bulkhead's queue MUST
be rejected with `503 Service Unavailable`. This requirement is
independent of and complementary to the per-IP rate limit.

#### Scenario: Bulkhead saturation
- **WHEN** more concurrent signup requests are in-flight than the
  bulkhead's capacity plus queue
- **THEN** excess requests are rejected with `503 Service Unavailable`
  and the in-flight requests complete normally

