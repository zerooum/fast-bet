#!/usr/bin/env bash
#
# smoke.sh — verifies the local Fast-Bet infrastructure is reachable.
#
# Probes:
#   1. Connect to each Postgres database (identity, catalog, wallet, bets) using
#      the corresponding service role and run `SELECT 1`.
#   2. Create + delete a temporary Kafka topic.
#   3. Run SET / GET / DEL on a temporary Redis key.
#
# Reads passwords from infra/.env via the docker compose environment so we
# don't need them in the host shell. Uses `docker compose exec` against the
# already-running stack — call `make up` first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${INFRA_DIR}/docker-compose.yaml"
ENV_FILE="${INFRA_DIR}/.env"
PROJECT_NAME="${PROJECT_NAME:-fastbet}"

if [ ! -f "${ENV_FILE}" ]; then
  echo "error: ${ENV_FILE} not found. Copy infra/.env.example to infra/.env first." >&2
  exit 1
fi

DC=(docker compose --project-name "${PROJECT_NAME}" --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

# Load env vars locally so we can read service-role passwords for psql probes.
# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

pass() { printf "  \033[32mok\033[0m   %s\n" "$1"; }
fail() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAILED=1; }

FAILED=0

echo "fast-bet smoke: postgres"
declare -A DB_TO_ROLE=(
  [identity]=identity_svc
  [catalog]=catalog_svc
  [wallet]=wallet_svc
  [bets]=bets_svc
)
declare -A ROLE_TO_PW=(
  [identity_svc]="${IDENTITY_SVC_PASSWORD}"
  [catalog_svc]="${CATALOG_SVC_PASSWORD}"
  [wallet_svc]="${WALLET_SVC_PASSWORD}"
  [bets_svc]="${BETS_SVC_PASSWORD}"
)
for db in identity catalog wallet bets; do
  role="${DB_TO_ROLE[$db]}"
  pw="${ROLE_TO_PW[$role]}"
  if "${DC[@]}" exec -T -e PGPASSWORD="${pw}" postgres \
        psql -U "${role}" -d "${db}" -tAc "SELECT 1" >/dev/null 2>&1; then
    pass "psql ${role}@${db} -> SELECT 1"
  else
    fail "psql ${role}@${db} -> SELECT 1"
  fi
done

echo "fast-bet smoke: kafka"
TOPIC="fastbet-smoke-$$-$(date +%s)"
if "${DC[@]}" exec -T kafka \
      kafka-topics --bootstrap-server localhost:9092 \
                   --create --topic "${TOPIC}" \
                   --partitions 1 --replication-factor 1 >/dev/null 2>&1; then
  pass "create topic ${TOPIC}"
else
  fail "create topic ${TOPIC}"
fi
if "${DC[@]}" exec -T kafka \
      kafka-topics --bootstrap-server localhost:9092 \
                   --delete --topic "${TOPIC}" >/dev/null 2>&1; then
  pass "delete topic ${TOPIC}"
else
  fail "delete topic ${TOPIC}"
fi

echo "fast-bet smoke: redis"
KEY="fastbet:smoke:$$:$(date +%s)"
if "${DC[@]}" exec -T redis redis-cli SET "${KEY}" "ok" >/dev/null 2>&1 \
   && [ "$( "${DC[@]}" exec -T redis redis-cli GET "${KEY}" | tr -d '\r' )" = "ok" ] \
   && "${DC[@]}" exec -T redis redis-cli DEL "${KEY}" >/dev/null 2>&1; then
  pass "redis SET/GET/DEL ${KEY}"
else
  fail "redis SET/GET/DEL ${KEY}"
fi

echo
if [ "${FAILED}" -eq 0 ]; then
  echo "fast-bet smoke: ALL OK"
  exit 0
else
  echo "fast-bet smoke: FAILURES detected" >&2
  exit 1
fi
