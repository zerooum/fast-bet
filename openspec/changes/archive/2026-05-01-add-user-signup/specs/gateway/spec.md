## ADDED Requirements

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
