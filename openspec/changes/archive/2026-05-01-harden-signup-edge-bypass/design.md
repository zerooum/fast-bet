## Context

The current signup path already has three defense-in-depth controls from the
archived `harden-signup-bypass` change:

- the Gateway strips `Authorization`, `X-User-*`, and spoofed
  `X-Forwarded-For` on `POST /signup`;
- Identity rejects authenticated-only headers on public paths;
- Identity applies its own Redis-backed per-IP signup rate limit and resolves
  client IPs only through trusted proxies.

Those controls reduce damage from direct Identity access, but they do not
prove that a valid anonymous signup request came through the Gateway. Because
future Identity routes will rely on the same internal trust boundary, this
change adds a narrow shared edge token for the Gateway-to-Identity hop.

The repository currently runs Identity manually on `localhost:8081` for local
development; `infra/docker-compose.yaml` only contains shared infrastructure.
So this change updates code and docs for the supported path through the
Gateway, while avoiding a fake compose service migration that does not exist
yet.

## Goals / Non-Goals

**Goals:**

- Require a configured `X-Edge-Token` on Identity business API requests.
- Have the Gateway inject the configured token on every request it forwards
  to Identity.
- Prevent clients from smuggling their own `X-Edge-Token` through the
  Gateway.
- Preserve the existing forbidden-header and rate-limit hardening on
  `POST /signup`.
- Keep health and metrics probes usable without an edge token.
- Update local docs and smoke behavior so public signup traffic goes through
  the Gateway.

**Non-Goals:**

- mTLS, service mesh policy, token rotation tooling, or Kubernetes
  NetworkPolicy manifests.
- Re-implementing the existing forbidden-header guard, signup rate limiter,
  or trusted-proxy resolver.
- Changing the public HTTP contract for `POST /signup`.
- Protecting Quarkus management endpoints with the edge token.

## Decisions

### 1. Use a shared `X-Edge-Token` header for the internal hop

Identity will read `fastbet.identity.edge-token` from `IDENTITY_EDGE_TOKEN`.
For business paths, it will compare the inbound `X-Edge-Token` value with the
configured secret and return `401 Unauthorized` when the token is absent or
wrong.

Why this over mTLS now: mTLS is the stronger production answer, but it also
requires certificate issuance, rotation, local dev setup, and deployment
manifests that this project does not have yet. A shared token is a small,
testable boundary improvement that fits the current Phase 1 stack.

Alternative considered: rely only on network topology. Rejected because the
existing spec already calls for service-side defense when topology is
misconfigured.

### 2. Scope token enforcement to business API paths

The edge-token filter will protect business API paths such as `/signup` and
future Identity routes, but it will skip `/q/health/*` and `/q/metrics`.

Why: health checks and metrics are operational endpoints, not public business
APIs. Requiring the app-level shared secret there would make local probes and
orchestrator health checks harder without addressing the signup bypass.

Alternative considered: protect every HTTP request including health. Rejected
because it couples deployment probes to an internal app secret and raises the
chance of false outages.

### 3. Keep the public forbidden-header guard ahead of token auth

The existing `PublicEndpointGuardFilter` returns `400` for `Authorization`,
`X-User-Id`, or `X-User-Roles` on `/signup`. That behavior should remain so
the current public-endpoint contract and tests do not regress.

The edge-token filter therefore must not treat `X-Edge-Token` as a forbidden
header, and it must allow the existing guard to reject malformed public
requests. If a request has no forbidden public headers but lacks the edge
token, Identity returns `401`.

Alternative considered: run edge-token auth first for every request. Rejected
because direct signup calls with forged auth headers would change from the
documented `400 forbidden_header` to `401`, weakening the specificity of the
public-path guard.

### 4. Gateway owns the forwarded token value

The Gateway will strip inbound `X-Edge-Token` from clients, then inject its
own configured token on the upstream request to Identity. The Gateway config
will require `IDENTITY_EDGE_TOKEN`; if absent in runtime config, the Gateway
must fail fast before serving traffic rather than proxying broken requests.

Alternative considered: forward an inbound client token when present. Rejected
because the edge token is an internal credential, not a public API field.

### 5. Local default uses an explicit non-production secret

Local `.env.example` files can provide a clearly named development value so
manual runs are ergonomic. Production and shared environments must override it
with a strong random secret supplied by the orchestrator.

Alternative considered: generate a random token at startup. Rejected because
the Gateway and Identity must agree on the same value before they can talk.

## Risks / Trade-offs

- [Risk] A shared secret can leak through logs or env inspection.
  Mitigation: never log token values; tests should assert only presence and
  behavior, not print the configured secret.
- [Risk] Mismatched Gateway and Identity token config causes all proxied
  signup calls to fail with `401`.
  Mitigation: fail fast on missing config, document the shared variable, and
  include integration tests for matching and mismatched token behavior.
- [Risk] Direct local diagnostics become less convenient.
  Mitigation: keep the Gateway smoke path simple and document explicit direct
  curl examples with `X-Edge-Token` for service-level debugging.
- [Risk] Filter ordering could regress existing `400 forbidden_header`
  behavior.
  Mitigation: keep existing tests and add a combined test covering forbidden
  header plus missing edge token.

## Migration Plan

1. Add the Identity edge-token config and filter behind the existing public
   endpoint guard.
2. Add Gateway config and inject the token on `POST /signup` upstream calls.
3. Update local env examples, README sections, and signup smoke scripts so the
   supported public path is `http://localhost:8080/signup`.
4. Run Identity and Gateway test suites.

Rollback: disable the filter by reverting the Identity edge-token change and
remove Gateway token injection. No database migration or public API rollback is
needed.

## Open Questions

- Should future non-Identity services reuse `IDENTITY_EDGE_TOKEN` style names
  per service, or should there be a single gateway-to-service token naming
  convention? Proposed answer: per service for now.
- When containerized app services are added to `infra/docker-compose.yaml`,
  should the app compose profile run Identity only on the internal network by
  default? Proposed answer: yes, publish only the Gateway.