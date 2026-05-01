## MODIFIED Requirements

### Requirement: Single entry point for the public API
The Gateway SHALL be the only public entry point for the Fast-Bet system.
All client traffic (frontend, future mobile, third-party) MUST reach
business services exclusively through the Gateway. In non-local
deployments, business services (Identity, Catalog, Wallet, Bets) MUST NOT
publish a network port reachable from outside the internal cluster
network; only the Gateway exposes a public port. As defense-in-depth
against misconfiguration, each business service that owns a publicly
routed path SHALL also enforce the public-route protections (forbidden
auth headers, per-IP rate limit) described in its own capability.

#### Scenario: Client bypasses gateway in production topology
- **WHEN** a client attempts to call a business service directly (e.g.,
  Identity, Catalog, Wallet, Bets) outside the Gateway
- **THEN** the deployment topology MUST prevent this (services not
  reachable from outside the internal network)

#### Scenario: Client bypasses gateway in a misconfigured environment
- **WHEN** the deployment topology has accidentally exposed a business
  service's public port and a client reaches `POST /signup` directly
- **THEN** the business service's own public-endpoint guard MUST apply
  the same protections as the gateway (reject `Authorization` /
  `X-User-*` with `400`, enforce a per-IP rate limit returning `429`)

#### Scenario: Local development exposes ports for smoke tests
- **WHEN** a developer runs `make up` / `make smoke` and Identity is
  reachable on `localhost:8081`
- **THEN** the configuration MUST be documented as a local-only
  convenience, and the service-side guard MUST still apply (so `make
  smoke` exercises the same rules production traffic would face)
