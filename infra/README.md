# Fast-Bet — local infrastructure (`infra/`)

This folder contains everything needed to bring up the local Fast-Bet
infrastructure: a single Postgres instance (4 isolated databases), Kafka in
KRaft mode (single broker), and Redis.

All commands below assume you run them from the **repository root** through
the root `Makefile`.

## Prerequisites

- Docker Engine + Docker Compose v2 (`docker compose ...`)
- GNU Make
- A populated `infra/.env` file (copy from `infra/.env.example`)

## First-time setup

```bash
cp infra/.env.example infra/.env
# edit infra/.env and replace every "change-me-*" value
make up
```

The first `make up` runs `infra/postgres/init/01-databases.sh` against the
fresh Postgres data volume. That script creates the four service databases and
their dedicated roles:

| Database  | Owner role     |
|-----------|----------------|
| identity  | `identity_svc` |
| catalog   | `catalog_svc`  |
| wallet    | `wallet_svc`   |
| bets      | `bets_svc`     |

Each role has `ALL PRIVILEGES` only on its own database. `PUBLIC` is revoked.

## Default ports

| Service    | Container port | Host port (override env var) |
|------------|----------------|------------------------------|
| Postgres   | 5432           | `POSTGRES_PORT` (default 5432) |
| Kafka      | 9092           | `KAFKA_PORT`    (default 9092) |
| Redis      | 6379           | `REDIS_PORT`    (default 6379) |

To override, set the variable in `infra/.env`, e.g.:

```
POSTGRES_PORT=55432
KAFKA_PORT=59092
REDIS_PORT=56379
```

## `make` targets

Run `make help` from the repo root to see them. Cheatsheet:

| Target          | What it does                                                                |
|-----------------|-----------------------------------------------------------------------------|
| `make up`       | Start the compose stack and wait for **all** healthchecks to be green.      |
| `make down`     | Stop containers, keep named volumes (data persists).                        |
| `make logs`     | Tail logs from every service.                                               |
| `make ps`       | Show service status + health.                                               |
| `make psql`     | Open a `psql` shell as the Postgres superuser inside the container.         |
| `make kafka-topics ARGS="--list"` | Run `kafka-topics` inside the broker (forward `ARGS`).    |
| `make redis-cli`| Open `redis-cli` inside the redis container.                                |
| `make smoke`    | Run `infra/scripts/smoke.sh` (described below).                             |
| `make clean`    | Stop the stack **and remove the named volumes** — asks for `yes` first.     |

## Smoke verification

`make smoke` runs `infra/scripts/smoke.sh`, which probes each component using
the running compose services. Expected output on a healthy stack:

```
fast-bet smoke: postgres
  ok   psql identity_svc@identity -> SELECT 1
  ok   psql catalog_svc@catalog -> SELECT 1
  ok   psql wallet_svc@wallet -> SELECT 1
  ok   psql bets_svc@bets -> SELECT 1
fast-bet smoke: kafka
  ok   create topic fastbet-smoke-<pid>-<ts>
  ok   delete topic fastbet-smoke-<pid>-<ts>
fast-bet smoke: redis
  ok   redis SET/GET/DEL fastbet:smoke:<pid>:<ts>

fast-bet smoke: ALL OK
```

Any line printed as `FAIL` is the diagnostic to investigate (typical causes:
wrong password in `.env`, container not yet healthy, port collision on host).

## Resetting the environment

If you change `infra/postgres/init/01-databases.sh` or want a clean state:

```bash
make clean   # removes volumes (asks for confirmation)
make up      # re-bootstraps everything from scratch
```
