## Why

The `gateway` capability requires that all client traffic reach business
services exclusively through the Gateway, but today nothing actually enforces
that for the Identity service: `POST /signup` on `:8081` is reachable from any
network the Identity container is exposed on, with no shared secret, no IP
allow-list, no `X-User-*` rejection, and no service-side rate limit. A
direct caller bypasses the per-IP `5/60s` throttle implemented in the gateway
([gateway/src/routes/signup.rs](gateway/src/routes/signup.rs#L36-L62)) and
could in principle smuggle forged `X-User-Id` / `X-User-Roles` headers — a risk
the gateway code already documents as needing defense-in-depth at Identity
([gateway/src/routes/signup.rs](gateway/src/routes/signup.rs#L25-L35)).

We need to close this bypass before adding more authenticated routes (login,
betting, wallet) that will inherit the same trust model.

## What Changes

- Identity SHALL reject any inbound request whose path is publicly routed
  (currently `/signup`) when it carries `X-User-Id`, `X-User-Roles`, or
  `Authorization` headers — defense-in-depth against a forged principal.
- Identity SHALL apply a per-client-IP rate limit on `/signup`
  (`5 req / 60 s`, mirroring the gateway's limit) so the cap holds even when
  the gateway is bypassed. The limiter MUST share the existing Redis instance
  to keep behavior consistent with the gateway.
- Identity SHALL trust client IP only from a configured `X-Forwarded-For`
  source; when the request did not arrive via a trusted upstream, the
  TCP peer address MUST be used.
- The Gateway capability SHALL document the network-isolation contract:
  in non-local deployments, business services (Identity, …) MUST NOT bind a
  publicly reachable port; only the Gateway is exposed. The local
  `infra/docker-compose.yaml` and the Identity README SHALL document the
  development trade-off (port published for `make smoke`) and how to disable
  it.
- **BREAKING**: requests to Identity `/signup` carrying `Authorization`,
  `X-User-Id`, or `X-User-Roles` now return `400 Bad Request` instead of being
  silently accepted.

## Capabilities

### New Capabilities
<!-- None: this hardens existing capabilities. -->

### Modified Capabilities
- `identity`: add direct-call hardening for `/signup` (forbidden auth headers,
  service-side per-IP rate limit, trusted-proxy IP resolution).
- `gateway`: tighten the "single entry point" requirement with concrete
  deployment-topology guidance and reference the Identity-side bulkhead.

## Impact

- **Code**:
  - `services/identity/`: new `PublicEndpointGuardFilter` (JAX-RS
    `ContainerRequestFilter`, `@PreMatching`) that rejects forbidden headers
    on configured public paths; new `SignupRateLimiter` (Redis-backed,
    same algorithm as `gateway/src/rate_limit.rs`); new
    `TrustedProxyResolver` for client IP.
  - `gateway/src/routes/signup.rs`: continue to strip the same headers (no
    behavior change), but add an integration assertion that Identity also
    rejects them.
  - `infra/docker-compose.yaml`: keep `8081` published for local `make smoke`;
    add an `identity-internal` profile (or comment) showing how to remove the
    public port for prod-like topologies.
- **APIs**: `POST /signup` direct calls with auth headers now → `400`. No
  change for traffic flowing through the gateway (gateway already strips).
- **Dependencies**: reuses existing `quarkus-redis-client` (already pulled in
  by the outbox stack? — confirm during design); no new heavy deps.
- **Out of scope**: mTLS between gateway and services, Kubernetes
  NetworkPolicies, IP allow-lists for staff endpoints. Those are tracked by
  later Phase 1+ work and are referenced from `design.md`.
