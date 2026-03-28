#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Start a local demo-backed Quicksand server for manual testing.

Environment overrides:
  HOST                  Server bind host (default: 127.0.0.1)
  PORT                  Server port (default: 8080)
  MAIL_FETCHER_ENABLED  Enable background fetcher (default: true)

Example:
  PORT=9090 ./scripts/start-test-server.sh
EOF
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
MAIL_FETCHER_ENABLED="${MAIL_FETCHER_ENABLED:-true}"

cd "${REPO_ROOT}"

mvn -DskipTests package

exec java \
  -Dserver.host="${HOST}" \
  -Dserver.port="${PORT}" \
  -Ddemo.enabled=true \
  -Dmail_fetcher.enabled="${MAIL_FETCHER_ENABLED}" \
  -jar target/quicksand.jar
