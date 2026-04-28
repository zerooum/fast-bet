## ADDED Requirements

### Requirement: Single entry point for the public API
The Gateway SHALL be the only public entry point for the Fast-Bet system. All client traffic (frontend, future mobile, third-party) MUST reach business services exclusively through the Gateway.

#### Scenario: Client bypasses gateway
- **WHEN** a client attempts to call a business service directly (e.g., Identity, Catalog, Wallet, Bets) outside the Gateway
- **THEN** the deployment topology MUST prevent this (services not reachable from outside the internal network)

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
