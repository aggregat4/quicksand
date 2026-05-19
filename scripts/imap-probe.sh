#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
Probe an IMAP server for capabilities relevant to Quicksand sync.

Builds the application jar if needed, then runs ImapCapabilityProbe.

Usage:
  ./scripts/imap-probe.sh --host HOST --user USER --password PASS [options]

Options (passed to the Java tool):
  --host HOST       IMAP server hostname (required)
  --port PORT       IMAP port (default: 993 with TLS, 143 with --no-ssl)
  --user USER       IMAP username (required)
  --password PASS   IMAP password (required)
  --no-ssl          Use cleartext IMAP

Examples:
  ./scripts/imap-probe.sh --host imap.fastmail.com --user me@fastmail.com --password PASS
  ./scripts/imap-probe.sh --host localhost --port 3143 --no-ssl --user testuser --password testpassword

Environment (optional defaults when flags are omitted):
  IMAP_HOST
  IMAP_PORT
  IMAP_USER
  IMAP_PASSWORD
  IMAP_NO_SSL         Set to 1 for cleartext IMAP

Example with environment variables:
  IMAP_HOST=imap.example.com IMAP_USER=alice IMAP_PASSWORD=secret ./scripts/imap-probe.sh
EOF
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_ROOT}"

ARGS=()
if [[ $# -gt 0 ]]; then
  ARGS=("$@")
else
  [[ -n "${IMAP_HOST:-}" ]] && ARGS+=(--host "${IMAP_HOST}")
  [[ -n "${IMAP_PORT:-}" ]] && ARGS+=(--port "${IMAP_PORT}")
  [[ -n "${IMAP_USER:-}" ]] && ARGS+=(--user "${IMAP_USER}")
  [[ -n "${IMAP_PASSWORD:-}" ]] && ARGS+=(--password "${IMAP_PASSWORD}")
  if [[ "${IMAP_NO_SSL:-}" == "1" ]]; then
    ARGS+=(--no-ssl)
  fi
fi

if [[ ${#ARGS[@]} -eq 0 ]]; then
  echo "Error: no arguments. Use --help for usage." >&2
  exit 2
fi

mvn -q -DskipTests package

exec java -cp "target/quicksand.jar:target/libs/*" \
  net.aggregat4.quicksand.tools.ImapCapabilityProbe \
  "${ARGS[@]}"
