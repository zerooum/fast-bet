## 1. Identity dependencies & configuration

- [x] 1.1 Add `quarkus-redis-client` to `services/identity/pom.xml`
- [x] 1.2 Add the new properties to `services/identity/src/main/resources/application.properties` with safe defaults: `fastbet.identity.public-paths=/signup`, `fastbet.identity.public-paths.forbidden-headers=Authorization,X-User-Id,X-User-Roles`, `fastbet.identity.signup.rate-limit.requests=5`, `fastbet.identity.signup.rate-limit.window-seconds=60`, `fastbet.identity.trusted-proxies=`, `quarkus.redis.hosts=${REDIS_URL:redis://localhost:6379}`
- [x] 1.3 In the `%test` profile, enable Redis DevServices (or wire a test-scoped in-memory limiter via a `@Mock` CDI alternative) so `@QuarkusTest` does not need an external Redis

## 2. Trusted-proxy IP resolver

- [x] 2.1 Implement `TrustedProxyResolver` (CDI `@ApplicationScoped`) parsing `IDENTITY_TRUSTED_PROXIES` (CIDR list) at startup and exposing `resolveClientIp(SocketAddress peer, String xForwardedForHeader)`
- [x] 2.2 Unit test: untrusted peer + spoofed XFF → peer wins; trusted peer + XFF → leftmost XFF wins; trusted peer without XFF → peer wins; empty allow-list → peer always wins
- [x] 2.3 Handle malformed XFF gracefully (empty entries, IPv6, surrounding whitespace)

## 3. Public endpoint guard filter

- [x] 3.1 Implement `PublicEndpointGuardFilter` as `@Provider @PreMatching` `ContainerRequestFilter` reading the configured public paths and forbidden header set
- [x] 3.2 On match, abort with `400 Bad Request` and body `{"error":"forbidden_header","header":"<canonical-name>"}` using the canonical header name from the configured set (case-normalized in the response)
- [x] 3.3 Ensure the filter sets / propagates `X-Request-Id` on the rejected response (reuse `RequestIdFilter` logic or move ID generation into a shared helper)
- [x] 3.4 Unit tests covering each forbidden header (incl. mixed case), no-header happy path, non-public path with forbidden headers (must NOT be rejected), and request-id presence on the rejection response

## 4. Signup rate limiter

- [x] 4.1 Define `SignupRateLimiter` interface with `Decision check(String key)` (mirroring the gateway's shape: `allowed`, `retryAfterSeconds`)
- [x] 4.2 Implement `RedisSignupRateLimiter` using `quarkus-redis-client`: `INCR ratelimit:identity:signup:<ip>`; if returned value is `1`, `EXPIRE` with the window. Compute `retryAfterSeconds` from `PTTL` (clamped to ≥ 1)
- [x] 4.3 Implement fail-open behavior: on any Redis error, log a warning with `request_id`, increment Micrometer counter `identity.signup.ratelimit.errors`, and return `Decision{allowed=true, retryAfterSeconds=0}`
- [x] 4.4 Implement `InMemorySignupRateLimiter` (test-only `@Alternative @Priority` activated by `%test`) with the same fixed-window semantics
- [x] 4.5 Unit tests for both implementations: 5 allowed → 6th rejected; window reset after `window-seconds`; concurrent calls don't exceed limit (best-effort assertion)

## 5. Wire the limiter into the signup flow

- [x] 5.1 In `SignupResource` (or a new `SignupRateLimitFilter` scoped via `@NameBinding` to `SignupResource`), call `TrustedProxyResolver` then `SignupRateLimiter.check(...)` before delegating to `SignupUseCase`
- [x] 5.2 On rejection, return `429 Too Many Requests` with header `Retry-After: <seconds>` and body `{"error":"rate_limited"}`; emit `signupLogger.attempt(requestId, normalizedEmail=null, "rate_limited")` and increment `identity.signup.attempts{outcome="rate_limited"}`
- [x] 5.3 Verify the existing `@Bulkhead` on `SignupUseCase` still wraps the use case execution and still returns `503` on saturation (no regression)

## 6. Local & deployment topology

- [x] 6.1 Document the local port-publishing trade-off in `services/identity/README.md` and `docs/local-development.md`, with the recommended way to remove the public port for prod-like topologies (e.g., a `gateway-only` compose profile that omits `ports:` on Identity)
- [x] 6.2 Add (commented or profile-gated) the `gateway-only` configuration to `infra/docker-compose.yaml`
- [x] 6.3 Add `IDENTITY_TRUSTED_PROXIES` to the gateway/identity environment template(s) with a note on what value to use in compose vs k8s

## 7. Tests (integration & smoke)

- [x] 7.1 `@QuarkusTest`: direct `POST /signup` with `Authorization: Bearer x` returns `400` with `{"error":"forbidden_header","header":"Authorization"}` and writes no row
- [x] 7.2 `@QuarkusTest`: direct `POST /signup` with `X-User-Id` returns `400`; with `x-user-roles` (lowercase) returns `400`
- [x] 7.3 `@QuarkusTest`: 6th call from the same simulated IP within the window returns `429` with `Retry-After`; after window elapses (or after explicit limiter reset) the next call is allowed
- [x] 7.4 `@QuarkusTest`: untrusted peer with spoofed `X-Forwarded-For` is rate-limited by peer IP, not by XFF
- [x] 7.5 `@QuarkusTest`: when the limiter throws (simulate Redis failure), signup still succeeds and `identity.signup.ratelimit.errors` increments
- [x] 7.6 Gateway integration test: still passes unchanged (the gateway already strips the headers); add an assertion that a request flowing through the gateway with `Authorization` reaches Identity stripped, and therefore is NOT rejected with `400`
- [x] 7.7 Smoke (`infra/scripts/signup-smoke.sh`): extend with a direct-`:8081` call carrying `Authorization` and assert `400`, plus a 6-in-a-row assertion that the 6th returns `429`

## 8. Validation

- [x] 8.1 `mvn -pl services/identity verify` is green
- [x] 8.2 `cargo test -p fastbet-gateway` is green (no behavior change expected)
- [ ] 8.3 `make up && make smoke` succeeds end-to-end against the new defaults
- [x] 8.4 `openspec validate harden-signup-bypass` passes
- [x] 8.5 Update `docs/roadmap.md` to reference this hardening change
