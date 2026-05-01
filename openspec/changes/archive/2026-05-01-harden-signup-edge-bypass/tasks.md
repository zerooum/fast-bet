## 1. Identity edge-token configuration

- [x] 1.1 Add `fastbet.identity.edge-token=${IDENTITY_EDGE_TOKEN:}` to `services/identity/src/main/resources/application.properties`
- [x] 1.2 Add a documented local `IDENTITY_EDGE_TOKEN` value to the relevant `.env.example` files and service README
- [x] 1.3 Ensure non-test runtime startup fails when `IDENTITY_EDGE_TOKEN` is missing or blank

## 2. Identity edge-token filter

- [x] 2.1 Implement `EdgeTokenFilter` as a JAX-RS request filter for Identity business API paths
- [x] 2.2 Exempt Quarkus management endpoints (`/q/health/*`, `/q/metrics`) from edge-token enforcement
- [x] 2.3 Compare `X-Edge-Token` to the configured secret using constant-time comparison
- [x] 2.4 Return `401` with `{"error":"edge_unauthorized"}` for missing or mismatched edge tokens before resource logic runs
- [x] 2.5 Preserve existing `PublicEndpointGuardFilter` behavior so forbidden public headers still return `400` and `X-Edge-Token` is not forbidden

## 3. Identity tests

- [x] 3.1 Add unit tests for `EdgeTokenFilter`: valid token allowed, missing token rejected, wrong token rejected, health/metrics exempt
- [x] 3.2 Add `@QuarkusTest` coverage for `POST /signup` without `X-Edge-Token` returning `401` without creating a user
- [x] 3.3 Add `@QuarkusTest` coverage for `POST /signup` with a wrong token returning `401`
- [x] 3.4 Add `@QuarkusTest` coverage for `POST /signup` with a valid token continuing through the existing signup flow
- [x] 3.5 Add regression coverage that `Authorization` or `X-User-*` on `/signup` still returns `400 forbidden_header` even when the edge token is missing

## 4. Gateway configuration and forwarding

- [x] 4.1 Add `identity_edge_token` / `IDENTITY_EDGE_TOKEN` to `gateway/src/config.rs` and fail fast when it is missing or blank
- [x] 4.2 Add the edge token to `GatewayState` so route handlers can access the configured value
- [x] 4.3 Update `gateway/src/routes/signup.rs` to strip inbound `x-edge-token` and inject the configured `X-Edge-Token` on the Identity upstream request
- [x] 4.4 Update gateway `.env.example` and README configuration tables with `IDENTITY_EDGE_TOKEN`

## 5. Gateway tests

- [x] 5.1 Update gateway integration test setup to construct `GatewayState` with a test edge token
- [x] 5.2 Assert a normal `POST /signup` reaches the stub Identity with the configured `X-Edge-Token`
- [x] 5.3 Assert an inbound client `X-Edge-Token` value is overwritten and not forwarded downstream
- [x] 5.4 Add config tests or startup-path coverage for missing/blank `IDENTITY_EDGE_TOKEN`

## 6. Local docs and smoke flow

- [x] 6.1 Update `services/identity/README.md` to describe the supported Gateway signup path and diagnostic direct-call token header
- [x] 6.2 Update `docs/local-development.md` so official signup smoke traffic goes through `http://localhost:8080/signup`
- [x] 6.3 Update `infra/scripts/signup-smoke.sh` so direct Identity diagnostic checks include `X-Edge-Token` only when exercising post-edge-token business behavior
- [x] 6.4 Keep direct forbidden-header diagnostics expecting `400 forbidden_header` without requiring the edge token

## 7. Validation

- [ ] 7.1 Run `openspec validate harden-signup-edge-bypass --strict`
- [x] 7.2 Run `mvn -pl services/identity verify`
- [x] 7.3 Run `cargo test -p fastbet-gateway`
- [ ] 7.4 Run the local signup smoke flow when Identity and Gateway are running