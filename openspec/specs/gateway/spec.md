# gateway Specification

## Purpose
TBD - created by archiving change init-fastbet-phase1. Update Purpose after archive.
## Requirements
### Requirement: Single entry point for the public API
The Gateway SHALL be the only public entry point for the Fast-Bet system.
All client traffic (frontend, future mobile, third-party) MUST reach
business services exclusively through the Gateway. In non-local deployments,
business services (Identity, Catalog, Wallet, Bets) MUST NOT publish a
network port reachable from outside the internal cluster network; only the
Gateway exposes a public port. As defense-in-depth against misconfiguration,
each business service that owns a publicly routed path SHALL also enforce the
public-route protections described in its own capability. For Identity
business API requests, the Gateway-to-Identity hop MUST use the shared
edge-token contract.

#### Scenario: Client bypasses gateway in production topology
- **WHEN** a client attempts to call a business service directly (e.g.,
  Identity, Catalog, Wallet, Bets) outside the Gateway
- **THEN** the deployment topology MUST prevent this (services not reachable
  from outside the internal network)

#### Scenario: Client bypasses gateway in a misconfigured environment
- **WHEN** the deployment topology has accidentally exposed Identity's public
  port and a client reaches `POST /signup` directly without a valid edge
  token and without forbidden auth headers
- **THEN** Identity's own edge guard MUST reject the request with `401`
  before signup business logic runs

#### Scenario: Client sends authenticated-only headers to exposed signup
- **WHEN** a direct caller sends `POST /signup` with `Authorization` or
  `X-User-*` headers
- **THEN** Identity's public-endpoint guard MUST reject the request with
  `400` according to the identity capability, regardless of edge-token state

#### Scenario: Local development routes public signup through the gateway
- **WHEN** a developer runs the official signup smoke flow locally
- **THEN** the flow MUST send public signup traffic to the Gateway
  (`http://localhost:8080/signup`) and not require direct Identity access on
  `localhost:8081`

### Requirement: Stateless JWT validation at the edge
The Gateway SHALL validate inbound JWTs using a public key (RS256) before forwarding any request to a downstream service. Invalid, expired, or missing tokens MUST be rejected at the Gateway, never forwarded.

#### Scenario: Valid token
- **WHEN** the Gateway receives a request with a valid JWT
- **THEN** the Gateway forwards the request to the downstream service and propagates the user identity (`sub` and `roles` claims) via internal headers

#### Scenario: Expired token
- **WHEN** the Gateway receives a request with an expired JWT
- **THEN** the Gateway responds with HTTP 401 without contacting any downstream service

#### Scenario: Public route
- **WHEN** the Gateway receives a request to a route flagged as public (e.g., user signup, login)
- **THEN** the Gateway forwards the request without requiring a JWT

### Requirement: Role-based route authorization
The Gateway SHALL enforce role-based authorization on protected routes using the `roles` claim of the JWT. Routes MUST declare which roles are permitted; the Gateway rejects with HTTP 403 if the user's roles do not match.

#### Scenario: User with insufficient role
- **WHEN** a user with role `USER` calls a route restricted to `ADMIN`
- **THEN** the Gateway responds with HTTP 403 without forwarding the request

### Requirement: Rate limiting backed by shared store
The Gateway SHALL apply rate limits to protect downstream services. Rate-limit state MUST be stored in Redis so multiple Gateway instances share the same counters.

#### Scenario: Rate limit exceeded
- **WHEN** a client exceeds the configured rate limit for a route
- **THEN** the Gateway responds with HTTP 429 and includes a `Retry-After` header

### Requirement: No business logic in the Gateway
The Gateway SHALL NOT contain business rules, response composition, or aggregation across services. Its responsibilities are limited to authentication terminus, authorization, rate limiting, and routing.

#### Scenario: Composite endpoint requested
- **WHEN** a feature requires aggregating data from multiple services
- **THEN** the aggregation MUST be implemented as a separate BFF service or in the requesting client, not in the Gateway

### Requirement: Public signup route registration
The Gateway SHALL register `POST /signup` as a public route that forwards to the Identity service without requiring or validating a JWT. The Gateway MUST NOT inject any `X-User-*` identity headers on this route, since no authenticated principal exists.

#### Scenario: Anonymous signup request
- **WHEN** an unauthenticated client sends `POST /signup` to the Gateway
- **THEN** the Gateway forwards the request to the Identity service without rejecting it for missing credentials, and forwards no synthesized identity headers

#### Scenario: JWT presented on signup
- **WHEN** a client sends `POST /signup` with a `Authorization: Bearer <jwt>` header
- **THEN** the Gateway MUST NOT validate or require the token for this route, and MUST forward the request as anonymous (the Identity service still ignores any role payload per the identity capability)

### Requirement: Per-IP rate limit on public signup
The Gateway SHALL apply a per-IP rate limit to `POST /signup` of at most 5 requests per minute per source IP, backed by the shared Redis store used by the existing rate-limit infrastructure. Excess requests MUST be rejected with `429 Too Many Requests` and a `Retry-After` header, without contacting the Identity service.

#### Scenario: Burst from a single IP
- **WHEN** the same source IP sends a 6th `POST /signup` within a 60-second window
- **THEN** the Gateway responds `429` with a `Retry-After` header and does not forward the request to Identity

### Requirement: Identity upstream edge-token injection
The Gateway SHALL load `IDENTITY_EDGE_TOKEN` from configuration and inject it
as `X-Edge-Token` on every request it forwards to Identity. The Gateway MUST
strip any inbound client-supplied `X-Edge-Token` before forwarding and MUST
use its configured value instead. The Gateway MUST fail fast at startup if the
configured token is missing or blank.

#### Scenario: Signup forwarded with edge token
- **WHEN** an unauthenticated client sends a valid `POST /signup` request to
  the Gateway
- **THEN** the Gateway forwards the request to Identity with `X-Edge-Token`
  set to the configured `IDENTITY_EDGE_TOKEN`

#### Scenario: Client-supplied edge token is not forwarded
- **WHEN** a client sends `POST /signup` to the Gateway with
  `X-Edge-Token: attacker-value`
- **THEN** the Gateway strips the inbound value and forwards only the
  Gateway-configured edge token to Identity

#### Scenario: Missing gateway token configuration
- **WHEN** the Gateway starts without a nonblank `IDENTITY_EDGE_TOKEN`
- **THEN** startup fails before the Gateway accepts traffic for Identity
  routes

