## ADDED Requirements

### Requirement: Public endpoints reject authenticated-only headers

The Identity service SHALL classify a configurable set of paths (default
`/signup`) as public, and any request to a public path that carries
`Authorization`, `X-User-Id`, or `X-User-Roles` headers MUST be rejected
with `400 Bad Request` and body
`{"error":"forbidden_header","header":"<canonical-name>"}`. The check
MUST run before request matching so it cannot be bypassed by path
variations, and it MUST be header-name case-insensitive.

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

#### Scenario: Public request with no forbidden headers proceeds
- **WHEN** a client sends a well-formed `POST /signup` body without any
  forbidden header
- **THEN** Identity processes the request normally (validation,
  persistence, outbox) and the guard does not interfere

#### Scenario: Authenticated routes are unaffected
- **WHEN** a (future) authenticated route receives `Authorization` and
  `X-User-Id` headers from the gateway
- **THEN** the guard MUST NOT reject the request because the route's path
  is not in the configured public-paths list

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
