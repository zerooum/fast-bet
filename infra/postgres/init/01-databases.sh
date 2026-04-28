#!/usr/bin/env bash
#
# 01-databases.sh
#
# Creates the four Fast-Bet service databases and dedicated roles, granting
# each role privileges only on its own database. Runs once on first start of
# the Postgres container (mounted at /docker-entrypoint-initdb.d).
#
# Required environment variables (set in docker-compose.yaml):
#   IDENTITY_SVC_PASSWORD
#   CATALOG_SVC_PASSWORD
#   WALLET_SVC_PASSWORD
#   BETS_SVC_PASSWORD
# Plus the standard POSTGRES_USER (used by psql via the entrypoint).
#
# Security notes:
#   * Passwords are NOT passed via `psql -v` (which would expose them in
#     /proc/<pid>/cmdline and `ps auxe`). They are streamed to psql on stdin,
#     so they live only in psql's process memory while the role is created.
#   * Each value is wrapped as a SQL string literal, with embedded single
#     quotes doubled (the standard SQL escape).
#   * Postgres masks the password value when logging CREATE ROLE in the
#     server log, so DDL logging is also safe.

set -euo pipefail

: "${IDENTITY_SVC_PASSWORD:?IDENTITY_SVC_PASSWORD must be set}"
: "${CATALOG_SVC_PASSWORD:?CATALOG_SVC_PASSWORD must be set}"
: "${WALLET_SVC_PASSWORD:?WALLET_SVC_PASSWORD must be set}"
: "${BETS_SVC_PASSWORD:?BETS_SVC_PASSWORD must be set}"

# sql_quote <var-name>
#   Prints a single-quoted SQL string literal with the value of the named
#   shell variable, doubling any embedded single quote.
sql_quote() {
  local v="${!1}"
  printf "'%s'" "${v//\'/\'\'}"
}

IDENTITY_PW_LIT="$(sql_quote IDENTITY_SVC_PASSWORD)"
CATALOG_PW_LIT="$(sql_quote CATALOG_SVC_PASSWORD)"
WALLET_PW_LIT="$(sql_quote WALLET_SVC_PASSWORD)"
BETS_PW_LIT="$(sql_quote BETS_SVC_PASSWORD)"

# Heredoc is intentionally UNQUOTED so the *_PW_LIT variables expand into
# the stream piped to psql's stdin. The raw password values never appear
# in psql's argv.
psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER}" \
     --dbname  "postgres" <<EOSQL
    -- Roles
    CREATE ROLE identity_svc LOGIN PASSWORD ${IDENTITY_PW_LIT};
    CREATE ROLE catalog_svc  LOGIN PASSWORD ${CATALOG_PW_LIT};
    CREATE ROLE wallet_svc   LOGIN PASSWORD ${WALLET_PW_LIT};
    CREATE ROLE bets_svc     LOGIN PASSWORD ${BETS_PW_LIT};

    -- Databases (each owned by its service role)
    CREATE DATABASE identity OWNER identity_svc;
    CREATE DATABASE catalog  OWNER catalog_svc;
    CREATE DATABASE wallet   OWNER wallet_svc;
    CREATE DATABASE bets     OWNER bets_svc;

    -- Privileges: ALL on own DB, REVOKE PUBLIC CONNECT, GRANT CONNECT to owner only.
    GRANT ALL PRIVILEGES ON DATABASE identity TO identity_svc;
    GRANT ALL PRIVILEGES ON DATABASE catalog  TO catalog_svc;
    GRANT ALL PRIVILEGES ON DATABASE wallet   TO wallet_svc;
    GRANT ALL PRIVILEGES ON DATABASE bets     TO bets_svc;

    REVOKE CONNECT ON DATABASE identity FROM PUBLIC;
    REVOKE CONNECT ON DATABASE catalog  FROM PUBLIC;
    REVOKE CONNECT ON DATABASE wallet   FROM PUBLIC;
    REVOKE CONNECT ON DATABASE bets     FROM PUBLIC;

    GRANT CONNECT ON DATABASE identity TO identity_svc;
    GRANT CONNECT ON DATABASE catalog  TO catalog_svc;
    GRANT CONNECT ON DATABASE wallet   TO wallet_svc;
    GRANT CONNECT ON DATABASE bets     TO bets_svc;
EOSQL

# Defence in depth: drop the literals from this script's environment as soon
# as psql returns. The process exits right after, but explicit is better.
unset IDENTITY_PW_LIT CATALOG_PW_LIT WALLET_PW_LIT BETS_PW_LIT

echo "fast-bet: created databases identity, catalog, wallet, bets and their service roles."
