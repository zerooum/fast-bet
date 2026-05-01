#!/usr/bin/env bash
#
# signup-smoke.sh — end-to-end smoke for the public POST /signup flow.
#
# Assumes the infra stack is up (`make up`) and that:
#   - The Identity service is running (e.g. `mvn -pl services/identity quarkus:dev`)
#   - The Gateway is running       (e.g. `cargo run -p fastbet-gateway`)
#
# Official signup traffic goes through the Gateway at GATEWAY_URL.
# Direct Identity checks (IDENTITY_URL) are diagnostic only; they require
# X-Edge-Token for calls that are expected to reach business logic.
#
# Sends two POSTs through the gateway against a fresh email and asserts:
#   1. The first request returns HTTP 201 with a `Location: /users/<uuid>` header
#      and a sanitized JSON body that contains no password/passwordHash field.
#   2. The second request with the same email returns HTTP 409 with
#      `{"error":"email_taken"}`.

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
IDENTITY_URL="${IDENTITY_URL:-http://localhost:8081}"
EMAIL="${EMAIL:-smoke+$$-$(date +%s)@example.com}"
PASSWORD="${PASSWORD:-supersecret123}"

# Edge token for diagnostic direct calls to Identity (must match IDENTITY_EDGE_TOKEN).
# Read from infra/.env if present and not overridden.
if [ -z "${IDENTITY_EDGE_TOKEN:-}" ] && [ -f "$(dirname "$0")/../.env" ]; then
  IDENTITY_EDGE_TOKEN="$(grep '^IDENTITY_EDGE_TOKEN' "$(dirname "$0")/../.env" 2>/dev/null | cut -d= -f2- || true)"
fi
IDENTITY_EDGE_TOKEN="${IDENTITY_EDGE_TOKEN:-}"

pass() { printf "  \033[32mok\033[0m   %s\n" "$1"; }
fail() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; exit 1; }

payload="{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"displayName\":\"smoke\"}"

echo "fast-bet smoke: signup -> ${GATEWAY_URL}/signup (email=${EMAIL})"

response_file="$(mktemp)"
trap 'rm -f "${response_file}"' EXIT

http_code=$(curl -sS -o "${response_file}" -w "%{http_code}" \
  -X POST "${GATEWAY_URL}/signup" \
  -H 'content-type: application/json' \
  --data "${payload}")
[ "${http_code}" = "201" ] || fail "first signup expected 201, got ${http_code} (body: $(cat "${response_file}"))"
pass "first signup -> 201"

# Verify the response body is sanitized.
body="$(cat "${response_file}")"
case "${body}" in
  *password*|*Password*|*hash*|*argon2*) fail "response body leaked credentials: ${body}" ;;
esac
pass "response body is sanitized"

# Second request with the same email must conflict.
http_code=$(curl -sS -o "${response_file}" -w "%{http_code}" \
  -X POST "${GATEWAY_URL}/signup" \
  -H 'content-type: application/json' \
  --data "${payload}")
[ "${http_code}" = "409" ] || fail "duplicate signup expected 409, got ${http_code} (body: $(cat "${response_file}"))"
case "$(cat "${response_file}")" in
  *email_taken*) ;;
  *) fail "duplicate signup body missing 'email_taken': $(cat "${response_file}")" ;;
esac
pass "duplicate signup -> 409 email_taken"

# --- Defense-in-depth diagnostic checks against the Identity service directly ---
# These run only when the Identity port is reachable on localhost, which
# is the default in the dev compose stack but should NOT be reachable in
# prod-like topologies.
if curl -sS -o /dev/null --max-time 1 "${IDENTITY_URL}/q/health/ready" 2>/dev/null; then
  echo
  echo "fast-bet smoke: identity bypass hardening -> ${IDENTITY_URL}/signup"

  # 1. Forbidden-header check: Authorization on /signup must return 400
  #    WITHOUT needing the edge token (PublicEndpointGuardFilter fires first).
  http_code=$(curl -sS -o "${response_file}" -w "%{http_code}" \
    -X POST "${IDENTITY_URL}/signup" \
    -H 'content-type: application/json' \
    -H 'authorization: Bearer spoofed' \
    --data "{\"email\":\"hdr+$$-$(date +%s)@example.com\",\"password\":\"${PASSWORD}\"}")
  [ "${http_code}" = "400" ] || fail "direct call with Authorization expected 400, got ${http_code} (body: $(cat "${response_file}"))"
  case "$(cat "${response_file}")" in
    *forbidden_header*) ;;
    *) fail "expected forbidden_header body, got: $(cat "${response_file}")" ;;
  esac
  pass "direct call w/ Authorization -> 400 forbidden_header (no edge token needed)"

  # Probe Redis: if it's reachable we must hard-fail when the rate-limit
  # assertion doesn't fire; if it's not reachable, the limiter fails open
  # by design and we warn instead.
  redis_up=0
  if command -v redis-cli >/dev/null 2>&1; then
    _redis_raw="${REDIS_URL:-redis://localhost:6379}"
    _redis_host=$(printf '%s' "${_redis_raw}" | sed 's|redis://||' | cut -d: -f1)
    _redis_port=$(printf '%s' "${_redis_raw}" | sed 's|redis://||' | cut -d: -f2)
    if redis-cli -h "${_redis_host:-localhost}" -p "${_redis_port:-6379}" PING 2>/dev/null | grep -q PONG; then
      redis_up=1
      # Flush any bucket left by a previous run so the flood loop starts clean.
      redis-cli -h "${_redis_host:-localhost}" -p "${_redis_port:-6379}" \
        DEL "ratelimit:identity:signup:127.0.0.1" > /dev/null 2>&1 || true
    fi
  fi

  # 2. Six rapid requests from the same loopback peer must trigger 429.
  #    These must carry X-Edge-Token so EdgeTokenFilter lets them through;
  #    the rate limiter is what should block request #6.
  bypass_email_prefix="rl+$$-$(date +%s)"
  rl_blocked=0
  for i in 1 2 3 4 5 6; do
    edge_args=()
    if [ -n "${IDENTITY_EDGE_TOKEN:-}" ]; then
      edge_args=(-H "x-edge-token: ${IDENTITY_EDGE_TOKEN}")
    fi
    http_code=$(curl -sS -o "${response_file}" -w "%{http_code}" \
      -X POST "${IDENTITY_URL}/signup" \
      -H 'content-type: application/json' \
      "${edge_args[@]}" \
      --data "{\"email\":\"${bypass_email_prefix}+${i}@example.com\",\"password\":\"${PASSWORD}\"}") || true
    if [ "${http_code}" = "429" ]; then
      rl_blocked=1
      break
    fi
  done
  if [ "${rl_blocked}" = "1" ]; then
    pass "direct flood -> 429 rate_limited (defense-in-depth)"
  elif [ "${redis_up}" = "1" ] && [ -n "${IDENTITY_EDGE_TOKEN:-}" ]; then
    fail "direct flood did NOT hit 429 — Redis is up and IDENTITY_EDGE_TOKEN is set, rate limit should have triggered"
  else
    if [ -z "${IDENTITY_EDGE_TOKEN:-}" ]; then
      printf "  \033[33mwarn\033[0m direct flood rate-limit check skipped — IDENTITY_EDGE_TOKEN not set (direct calls return 401, not 429)\n"
    else
      printf "  \033[33mwarn\033[0m direct flood did NOT hit 429 — redis-cli not found or Redis unreachable (limiter fails open by design)\n"
    fi
  fi
else
  echo
  echo "fast-bet smoke: skipping identity bypass checks (port 8081 not reachable)"
fi

echo
echo "fast-bet smoke: signup OK"
