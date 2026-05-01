## 1. Identity service skeleton

- [x] 1.1 Create `services/identity/` Quarkus 3 module (Maven, Java 21) with the standard package root `com.fastbet.identity`
- [x] 1.2 Add Quarkus extensions: `quarkus-resteasy-reactive-jackson`, `quarkus-hibernate-validator`, `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`, `quarkus-smallrye-reactive-messaging-kafka`, `quarkus-smallrye-fault-tolerance`
- [x] 1.3 Add `de.mkammerer:argon2-jvm` as a runtime dependency
- [x] 1.4 Configure `application.properties` to connect to the `identity` DB using the `identity_svc` role (driven by env vars; never hardcoded)
- [x] 1.5 Wire Flyway to run on startup against schema `public`
- [x] 1.6 Add a top-level `README.md` for the service describing how to run it locally against the compose stack

## 2. Database migrations

- [x] 2.1 Add Flyway migration `V1__user_account.sql` creating `user_account` (id UUID PK with `gen_random_uuid()`, email CITEXT UNIQUE, display_name TEXT, password_hash TEXT, roles TEXT[] default `ARRAY['USER']`, created_at, updated_at) and enabling the `citext` and `pgcrypto` extensions
- [x] 2.2 Add a CHECK constraint on `roles` that every element is in `('USER','SCHEDULER','ODD_MAKER','ADMIN')`
- [x] 2.3 Add Flyway migration `V2__outbox.sql` creating the `outbox` table and the `outbox_unpublished_idx` partial index
- [x] 2.4 Verify migrations run cleanly against a freshly-`make up`'d Postgres

## 3. Domain and persistence

- [x] 3.1 Create `UserAccount` JPA entity mirroring `user_account`, with `roles` mapped as `String[]`
- [x] 3.2 Create `UserAccountRepository` (Panache) with `findByEmailIgnoreCase(String)` and `existsByEmailIgnoreCase(String)`
- [x] 3.3 Create `OutboxEvent` JPA entity and `OutboxRepository` with `appendUserRegistered(UUID id, String email, String displayName, Instant at)`
- [x] 3.4 Create `Role` enum (`USER`, `SCHEDULER`, `ODD_MAKER`, `ADMIN`) with a unit test asserting the canonical set matches the JWT-issuer whitelist constant

## 4. Password hashing

- [x] 4.1 Define a `PasswordHasher` port (interface) with `hash(String raw)` and `verify(String raw, String stored)`
- [x] 4.2 Implement `Argon2idPasswordHasher` using `argon2-jvm` with parameters `m=65536, t=3, p=1, saltLen=16, hashLen=32`, producing the PHC-encoded string
- [x] 4.3 Add unit tests: hash output starts with `$argon2id$`, `verify` returns true for the original raw password and false for any altered password, and two hashes of the same raw password differ (random salt)

## 5. Signup use case and HTTP endpoint

- [x] 5.1 Create `SignupRequest` DTO with Bean Validation annotations: `@Email @Size(max=254)` on `email`, `@Size(min=12,max=128) @Pattern` (≥1 letter & ≥1 digit) on `password`, `@Size(min=1,max=60)` on optional `displayName`. The DTO MUST NOT expose any `roles` field
- [x] 5.2 Create `SignupResponse` DTO with `id`, `email`, `displayName`, `roles`, `createdAt` only
- [x] 5.3 Implement `SignupUseCase`: normalize email (trim + lowercase), default `displayName` to email local-part when blank, hash password, persist `UserAccount` with `roles=['USER']`, append outbox event, all in one `@Transactional` method
- [x] 5.4 Wrap the use-case execution in `@Bulkhead(value=4, waitingTaskQueue=8)` to cap concurrent Argon2 work
- [x] 5.5 Implement `SignupResource` (`POST /signup`): consume/produce JSON, return `201 Created` with `Location: /users/{id}` on success, `409 Conflict` with `{"error":"email_taken"}` on duplicate, and `400 Bad Request` with `{"error":"validation","fields":{...}}` on validation errors via a `@Provider ExceptionMapper<ConstraintViolationException>`
- [x] 5.6 Ensure the resource never returns the password or password hash and never accepts a role field (any unknown JSON properties are ignored via Jackson `FAIL_ON_UNKNOWN_PROPERTIES=false`)

## 6. Outbox relay

- [x] 6.1 Implement `OutboxRelay` as a `@Scheduled(every="5s")` Quarkus bean that reads up to N unpublished rows ordered by `occurred_at`, publishes each to Kafka topic `identity.user-registered` via the reactive messaging emitter, and sets `published_at` on success
- [x] 6.2 Define the Kafka outgoing channel in `application.properties` and the JSON envelope (`eventType`, `version`, `userId`, `email`, `displayName`, `registeredAt`)
- [x] 6.3 Add a metric counter `identity.outbox.published` and `identity.outbox.failed`

## 7. Logging & observability

- [x] 7.1 Add a `signupLogger` that emits INFO on each attempt with fields `request_id`, `email_hash` (first 12 hex of SHA-256 of the normalized email), `outcome` (`ok|conflict|invalid|error`), and never the raw email or password
- [x] 7.2 Register Micrometer counter `identity.signup.attempts` tagged with `outcome`
- [x] 7.3 Propagate the `X-Request-Id` header into MDC for the duration of the request

## 8. Gateway public route

- [x] 8.1 In `gateway/`, register `POST /signup` as a public route (no JWT validation, no `X-User-*` injection) forwarding to `http://identity:8080/signup`
- [x] 8.2 Apply a per-IP rate limit of `5 req / 60 s` to that route, backed by the existing Redis rate-limit module, returning `429` with `Retry-After` on excess
- [x] 8.3 Generate (or propagate) `X-Request-Id` and forward it downstream
- [x] 8.4 Add a gateway integration test asserting: anonymous signup is forwarded; an `Authorization` header is not validated; the 6th request from the same IP within 60s is rejected with `429`

## 9. Tests

- [x] 9.1 Unit tests for `SignupUseCase`: happy path, duplicate email (case-insensitive), validation errors, role payload silently dropped, outbox row written in the same transaction (rollback test)
- [x] 9.2 Quarkus integration test (`@QuarkusTest`) hitting `POST /signup` end-to-end against an embedded/Testcontainers Postgres: `201`, `409`, `400` paths, and assertion that the response never contains `passwordHash`
- [x] 9.3 Outbox relay test: an unpublished row is published to an embedded Kafka and `published_at` is set
- [x] 9.4 End-to-end smoke: `make up`, run gateway + identity, `curl POST /signup` through the gateway returns `201` and the user can be re-signed-up to get `409`

## 10. Validation

- [x] 10.1 Run `mvn -pl services/identity verify` and confirm all tests pass
- [x] 10.2 Run the gateway integration tests and confirm they pass
- [x] 10.3 Run `openspec validate add-user-signup` and confirm it passes
- [x] 10.4 Update `docs/roadmap.md` to mark `add-user-signup` as in-progress / done
