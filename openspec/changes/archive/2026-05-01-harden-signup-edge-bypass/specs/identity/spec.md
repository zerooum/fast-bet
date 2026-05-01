## MODIFIED Requirements

### Requirement: Public endpoints reject authenticated-only headers

The Identity service SHALL classify a configurable set of paths (default
`/signup`) as public, and any request to a public path that carries
`Authorization`, `X-User-Id`, or `X-User-Roles` headers MUST be rejected
with `400 Bad Request` and body
`{"error":"forbidden_header","header":"<canonical-name>"}`. The check
MUST run before request matching so it cannot be bypassed by path variations,
and it MUST be header-name case-insensitive. This guard MUST run before
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

## ADDED Requirements

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