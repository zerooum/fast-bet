## MODIFIED Requirements

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

## ADDED Requirements

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