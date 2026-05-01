## Why

The earlier signup hardening change already made direct Identity calls safer:
public paths reject `Authorization` / `X-User-*`, Identity applies its own
signup rate limit, and trusted-proxy IP resolution is explicit. One bypass
still remains: a caller that can reach Identity directly can submit otherwise
valid anonymous signup traffic without proving it came through the Gateway.

Before more Identity routes are added, the internal Gateway-to-Identity hop
needs an explicit shared edge credential so the Gateway stays the only
supported public entry point even when a port is accidentally exposed.

## What Changes

- Identity SHALL require a shared edge token on every business API
  request: a `X-Edge-Token` header whose value matches a configured
  secret (`IDENTITY_EDGE_TOKEN`). Requests without a matching token MUST
  be rejected with HTTP 401 before business logic runs.
- The gateway SHALL inject `X-Edge-Token` from its own configuration
  (`IDENTITY_EDGE_TOKEN`) on every request it forwards to Identity, and
  SHALL overwrite any client-supplied `X-Edge-Token`.
- Identity SHALL keep the existing public-path forbidden-header guard on
  `/signup`. That guard remains responsible for returning `400` when
  clients send `Authorization`, `X-User-Id`, or `X-User-Roles`; the edge
  token is a separate internal caller credential.
- Health and metrics endpoints are not business APIs and SHALL remain
  usable by local/dev probes without the edge token.
- Official local smoke flows SHALL go through the Gateway. Direct
  `http://localhost:8081/signup` probes become diagnostic-only and must
  include the edge token if they are expected to reach business logic.
- **BREAKING (local dev only)**: scripts and developers that create users
  by hitting Identity directly MUST go through the Gateway
  (`http://localhost:8080/signup`) or explicitly provide the configured
  edge token for diagnostic direct calls.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
- `identity`: adds edge-token authentication for business API requests and
  clarifies ordering with the existing public-path forbidden-header guard.
- `gateway`: adds the requirement to inject the shared edge token on
  every upstream call to Identity, and tightens the local topology guidance
  so official public traffic goes through the Gateway.

## Impact

- **Code**:
  - `services/identity/`: new `EdgeTokenFilter` (JAX-RS
    `ContainerRequestFilter`) that validates `X-Edge-Token` for business
    paths before resource logic. Existing `PublicEndpointGuardFilter`,
    `RedisSignupRateLimiter`, and `TrustedProxyResolver` stay in place.
  - `gateway/`: forward `X-Edge-Token` on the `/signup` proxy and on any
    future Identity routes, while stripping any inbound client value for
    that header.
- **Config**:
  - New env var `IDENTITY_EDGE_TOKEN` consumed by both Identity and the
    gateway. `.env.example` files updated. Production deploys MUST set a
    strong random value.
  - Local docs/scripts updated so the supported signup smoke path is the
    Gateway URL. Direct Identity diagnostics document how to provide the
    edge token.
- **APIs**: no change to the public HTTP contract. Internal contract
  between gateway and Identity gains a mandatory `X-Edge-Token` header.
- **Out of scope**: mTLS between gateway and Identity, full service
  mesh, key rotation tooling, and JWT validation for protected routes —
  these are tracked separately as Phase 2 hardening.
