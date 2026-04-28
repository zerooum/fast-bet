# Fast-Bet

Sports-betting study/portfolio project, organized as a small set of microservices.

## Phase 1 scope

- User signup and login (`identity`)
- Staff management — admin creates schedulers and odd-makers (`identity`)
- Event / market / selection catalog managed by staff (`catalog`)
- Wallet deposit and withdrawal with simulated payment gateway (`wallet`)
- Placing bets with a `RESERVED → CONFIRMED|REJECTED` saga (`bets`)
- API Gateway (Rust) terminating auth (JWT RS256), rate-limit, role-based routing
- Frontend (Angular + Ionic) consuming the gateway

Bet settlement, real payment integration, 2FA, password recovery, real-time odd push, and any production-grade hardening are explicitly **out of scope** for Phase 1.

See [openspec/changes/init-fastbet-phase1/proposal.md](openspec/changes/init-fastbet-phase1/proposal.md) and [design.md](openspec/changes/init-fastbet-phase1/design.md) for the full architectural decisions.

## Repository layout

```
infra/        Local infrastructure (docker-compose, init scripts, smoke tests)
services/     Quarkus business services: identity, catalog, wallet, bets
gateway/      Rust API gateway
frontend/     Angular + Ionic app
docs/         Cross-cutting architecture, conventions, roadmap, local-dev guide
openspec/     OpenSpec changes and capability specs
```

## Running the local environment

Prerequisites: Docker / Docker Compose, GNU Make, JDK 21, Rust toolchain, Node 20+.

```bash
make up      # start postgres + kafka + redis (waits for healthchecks)
make smoke   # verify each component is reachable
make down    # stop the stack
make clean   # stop AND remove named volumes (asks for confirmation)
```

See [docs/local-development.md](docs/local-development.md) for details.

## OpenSpec workflow

Changes live under `openspec/changes/`. The bootstrap change is `init-fastbet-phase1`. Subsequent vertical slices follow the order in [docs/roadmap.md](docs/roadmap.md).
