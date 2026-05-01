## Context

The `gateway` capability declares the Gateway as the only public entry point,
and the `add-user-signup` change wired `POST /signup` as a public route with a
Redis-backed per-IP rate limit (`5 req / 60 s`) plus header stripping
([gateway/src/routes/signup.rs](gateway/src/routes/signup.rs#L17-L88)).
However, the Identity service binds `${IDENTITY_HTTP_PORT:8081}` on
`0.0.0.0` ([services/identity/src/main/resources/application.properties](services/identity/src/main/resources/application.properties#L8))
and the local compose stack publishes the port for the smoke-test script.
Identity itself has no filter that recognizes a "public" path nor rejects
forged `X-User-*` headers, and no shared rate limiter — so a direct caller
trivially evades the gateway's protections.

The proposal hardens the direct path. Constraints:

- Quarkus 3.15 / Java 21, single-module identity service.
- Redis is already running in the compose stack (used by the gateway), but
  Identity does not yet depend on it.
- Tests rely on Quarkus DevServices for Postgres and the in-memory Kafka
  connector. Adding Redis requires either DevServices for Redis or a
  test-scoped in-memory limiter.

Stakeholders: identity service owners, gateway owners, ops (deployment
topology / docker-compose).

## Goals / Non-Goals

**Goals:**

- Make `POST /signup` safe to expose even when the gateway is bypassed.
- Keep the gateway behavior unchanged: the gateway's per-IP limit and header
  stripping remain the primary defense.
- Add a single, narrow, well-tested defense-in-depth layer at Identity
  without spreading auth concerns across the codebase.
- Preserve the local-development ergonomics of `make smoke` (which calls
  `:8081` directly).

**Non-Goals:**

- mTLS or shared-secret authentication between gateway and Identity.
- Kubernetes NetworkPolicy / cloud security-group templates.
- Rate-limiting authenticated routes — that is a separate, per-route concern.
- Centralizing public-route routing across services (every service owns its
  own guard until we have a service mesh).

## Decisions

### 1. JAX-RS `@PreMatching` filter rejects forbidden headers on public paths

A `PublicEndpointGuardFilter` (`@Provider`, `@PreMatching`,
`ContainerRequestFilter`) inspects requests whose path matches a configured
list of public path prefixes (default: `/signup`). If any of
`Authorization`, `X-User-Id`, or `X-User-Roles` are present, the filter
aborts with `400 Bad Request` and body `{"error":"forbidden_header","header":"..."}`.

**Why a filter (vs per-resource validation)**: keeps the rule out of the
business code, runs before Bean Validation, and is the standard Quarkus way
to enforce request-shape policy. Using `@PreMatching` ensures the rule fires
even if the resource later changes its `@Path`.

**Why `400` (vs `401`)**: the request is malformed for a public endpoint, not
unauthenticated. Returning `401` would imply the client should retry with
credentials — the opposite of the policy.

**Alternative considered**: an annotation `@PublicOnly` placed on the
resource. Rejected because a developer could forget the annotation; a
filter driven by config is the safer default.

### 2. Server-side per-IP rate limit on `/signup`, Redis-backed, mirroring the gateway

A `SignupRateLimiter` re-implements the same fixed-window algorithm as
[gateway/src/rate_limit.rs](gateway/src/rate_limit.rs#L93-L120) (`INCR` +
conditional `EXPIRE`) using `quarkus-redis-client`. Defaults: `5 req / 60 s`
per resolved client IP. On rejection it returns `429 Too Many Requests` with
a `Retry-After` header.

**Key namespace**: `ratelimit:identity:signup:<ip>`. Distinct prefix from the
gateway's `ratelimit:signup:<ip>` so the two limiters do not share a counter
— defense-in-depth means each layer has its own budget. (The gateway's
counter still trips first for traffic flowing through it, so legitimate
gateway-routed users see no behavior change.)

**Why duplicate the algorithm rather than share a counter**: sharing would
mean a misbehaving direct caller could exhaust the gateway's budget for
legitimate users on the same IP. Independent counters keep the layers
genuinely independent.

**Alternative considered**: SmallRye Fault Tolerance `@Bulkhead` only.
Rejected — the existing bulkhead caps concurrent Argon2 work but does not
throttle bursty unique requests, which is the abuse vector.

### 3. Trusted-proxy IP resolution

A `TrustedProxyResolver` reads a comma-separated `IDENTITY_TRUSTED_PROXIES`
env var (CIDR list). When the TCP peer falls inside that list, the leftmost
entry of `X-Forwarded-For` is used as client IP; otherwise, the TCP peer is
used and `X-Forwarded-For` is ignored.

**Why**: without this, a direct caller could spoof `X-Forwarded-For` and
either dilute the rate-limit key or impersonate another caller. This is the
same threat the gateway addresses by being the trusted hop.

**Default**: empty list — i.e. trust nobody. In compose / k8s, set it to
the gateway's pod CIDR.

### 4. Configuration shape

```
fastbet.identity.public-paths=/signup
fastbet.identity.public-paths.forbidden-headers=Authorization,X-User-Id,X-User-Roles
fastbet.identity.signup.rate-limit.requests=5
fastbet.identity.signup.rate-limit.window-seconds=60
fastbet.identity.trusted-proxies=
quarkus.redis.hosts=${REDIS_URL:redis://localhost:6379}
```

### 5. Tests

- Unit test on `PublicEndpointGuardFilter`: each forbidden header → 400; no
  forbidden header → request proceeds; non-public path → request proceeds
  even with the headers (no false positives on future authenticated routes).
- Quarkus integration test: hit `/signup` directly with `Authorization` →
  400; without → 201/409/400 as today; 6th request from same IP within 60s
  → 429 with `Retry-After`.
- Redis: use the existing devservices Redis (or a Testcontainers Redis if
  unavailable) — the gateway already has a working Redis-in-tests pattern.

### 6. Documentation & topology

Add a short section to the Identity README and `docs/local-development.md`
explaining:

- Why `8081` is published locally (smoke script) and how to remove it for
  prod-like topologies (`docker compose --profile gateway-only up`, or
  remove the `ports:` block).
- That the gateway is still the supported entry point; direct calls work
  but are rate-limited and reject auth headers.

## Risks / Trade-offs

- **Risk**: the in-cluster gateway hop will start carrying its
  request-derived `X-Forwarded-For` to Identity; if `IDENTITY_TRUSTED_PROXIES`
  is misconfigured, all signups will share one rate-limit bucket (the
  gateway IP). → **Mitigation**: ship a working compose default and document
  the var; add a smoke assertion that two signups from different test
  clients land in different buckets.
- **Risk**: introducing Redis as an Identity dependency increases the
  service's blast radius (a Redis outage now also fails signups). →
  **Mitigation**: when Redis is unreachable, the limiter SHALL fail open
  (allow the request, increment a `identity.signup.ratelimit.errors`
  counter) and log a warning. The gateway's limiter still protects the
  primary path.
- **Trade-off**: duplicated rate-limit logic in Rust and Java. Acceptable
  for a 30-line algorithm and avoids a cross-service contract on Redis key
  shape.
- **Risk**: header-name comparison case sensitivity. → **Mitigation**:
  Quarkus `HttpHeaders` is case-insensitive; tests cover mixed casing.
- **Risk**: `@PreMatching` runs before `RequestIdFilter` writes the request
  id, so 400/429 responses from the guard may lack a request id in MDC. →
  **Mitigation**: have the guard generate / read `X-Request-Id` itself, or
  reorder filters; design test asserts the response carries an
  `X-Request-Id`.

## Migration Plan

1. Land filter + limiter behind config defaults that match today's gateway
   limits. No behavior change for traffic via gateway.
2. Roll out to local compose (`make smoke` continues to pass — the smoke
   script makes a single signup call, well under 5/60s).
3. In environments where Identity is currently exposed, set
   `IDENTITY_TRUSTED_PROXIES` to the gateway's address before unpublishing
   the port, so the rate limit keys on real client IPs.
4. Once verified, remove the `8081` port publication in non-local
   compose / k8s manifests (tracked in this change's tasks).

**Rollback**: set
`fastbet.identity.public-paths=` (empty) to disable the guard, and
`fastbet.identity.signup.rate-limit.requests=0` to disable the limiter.
The endpoint reverts to today's behavior.

## Open Questions

- Should the guard also cover health endpoints (`/q/health/*`) or only
  business public paths? Proposed: business only; health stays untouched.
- Do we want a Kafka audit event for direct-bypass attempts (rejected by
  the guard)? Deferred — the metric counter is sufficient for Phase 1.
