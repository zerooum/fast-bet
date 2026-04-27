## 1. Repository structure

- [ ] 1.1 Create top-level directories: `infra/`, `services/`, `gateway/`, `frontend/`, `docs/`
- [ ] 1.2 Add a root `README.md` describing the project, the Phase 1 scope, and how to run the local environment
- [ ] 1.3 Add `.gitignore` covering JVM (`target/`, `*.class`), Rust (`target/`, `Cargo.lock` for libs), Node (`node_modules/`, `dist/`), Postgres data volumes, and editor folders
- [ ] 1.4 Add `.editorconfig` enforcing UTF-8, LF, 2-space indent for YAML/JSON, 4-space for Java
- [ ] 1.5 Add a `LICENSE` file (or `NOTICE` placeholder if undecided)

## 2. Local infrastructure (`infra/`)

- [ ] 2.1 Create `infra/docker-compose.yaml` declaring services: `postgres`, `kafka`, `zookeeper-or-kraft`, `redis`, with named volumes and a shared network
- [ ] 2.2 Add Postgres healthcheck (`pg_isready`), Kafka healthcheck (broker API), and Redis healthcheck (`PING`)
- [ ] 2.3 Pin all images to explicit minor versions (e.g., `postgres:16.x`, `confluentinc/cp-kafka:7.x`, `redis:7.x`)
- [ ] 2.4 Create `infra/postgres/init/01-databases.sql` that creates four databases (`identity`, `catalog`, `wallet`, `bets`) and four service roles (`identity_svc`, `catalog_svc`, `wallet_svc`, `bets_svc`) with `GRANT ALL` only on their respective database
- [ ] 2.5 Mount the init folder at `/docker-entrypoint-initdb.d` so the script runs on first start
- [ ] 2.6 Document expected ports in `infra/README.md` (Postgres 5432, Kafka 9092, Redis 6379) and how to override via `.env`
- [ ] 2.7 Add `infra/.env.example` with placeholders for Postgres superuser password and service-role passwords

## 3. Build and run ergonomics

- [ ] 3.1 Add a root `Makefile` (or `justfile`) with targets: `up`, `down`, `logs`, `psql`, `kafka-topics`, `redis-cli`, `clean`
- [ ] 3.2 `make up` MUST start the compose stack and wait for healthchecks to be green before returning
- [ ] 3.3 `make clean` MUST stop the stack and remove named volumes (with confirmation prompt)
- [ ] 3.4 Document `make` targets in `infra/README.md`

## 4. Smoke verification of infra

- [ ] 4.1 Add `infra/scripts/smoke.sh` that connects to each Postgres database with the corresponding service role and runs `SELECT 1`
- [ ] 4.2 The smoke script MUST verify Kafka can create and delete a temporary topic
- [ ] 4.3 The smoke script MUST verify Redis with `SET`/`GET`/`DEL` of a temp key
- [ ] 4.4 Add a `make smoke` target that runs the smoke script
- [ ] 4.5 Document expected smoke output in `infra/README.md`

## 5. Cross-cutting conventions

- [ ] 5.1 Add `docs/architecture.md` summarizing the principles from `design.md` (microservices, database-per-service on a single Postgres, Outbox + Kafka, hexagonal payment port, JWT RS256, Gateway responsibilities)
- [ ] 5.2 Add `docs/conventions.md` declaring naming rules: Kafka topics `<bounded-context>.<event-name>`, Postgres role names `<svc>_svc`, JWT claims `sub`/`roles`/`iat`/`exp`
- [ ] 5.3 Add `docs/roadmap.md` listing the planned vertical-slice changes in order: `add-user-signup`, `add-staff-management`, `add-event-catalog`, `add-wallet-deposit`, `add-betting`, `add-wallet-withdrawal`
- [ ] 5.4 Add `docs/local-development.md` describing prerequisites (Docker, JDK 21, Rust toolchain, Node 20+), how to run `make up`, and how to run `make smoke`

## 6. Validation

- [ ] 6.1 Run `make up` on a clean machine and confirm all healthchecks become green
- [ ] 6.2 Run `make smoke` and confirm all probes pass
- [ ] 6.3 Run `make clean` and confirm volumes are removed
- [ ] 6.4 Run `openspec validate init-fastbet-phase1` and confirm it passes
