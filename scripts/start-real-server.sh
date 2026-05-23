#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
Start Quicksand against a real IMAP/SMTP account (not demo mode).

Assumes the project is already built:
  mvn -DskipTests package

Configuration (pick one):
  1. config/application-local.conf (recommended)
     cp config/application-local.conf.example config/application-local.conf
  2. Environment variables (generates a temporary config for this run):
     IMAP_HOST, IMAP_USER, IMAP_PASSWORD
     optional: IMAP_PORT, IMAP_NO_SSL=1, ACCOUNT_NAME
     optional SMTP: SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD

Environment overrides:
  QUICKSAND_CONFIG        Config file path (default: config/application-local.conf)
  QUICKSAND_CREDENTIAL_KEY  Base64 32-byte key for SQLite password encryption (see docs/account-credentials.md)
  HOST                    Bind host override (default: 127.0.0.1)
  PORT                    Bind port override (default: 8080)
  WIPE_DB=1               Delete the SQLite database before startup
  PROBE=1                 Run ./scripts/imap-probe.sh before starting

Flags:
  --wipe-db          Same as WIPE_DB=1
  --probe            Same as PROBE=1

Examples:
  cp config/application-local.conf.example config/application-local.conf
  # edit credentials, then:
  ./scripts/start-real-server.sh

  IMAP_HOST=mail.example.com IMAP_USER=alice IMAP_PASSWORD=secret \
    IMAP_NO_SSL=1 SMTP_PORT=25 ./scripts/start-real-server.sh --wipe-db

Notes:
  - IMAP/SMTP passwords in SQLite are encrypted at rest; the server needs a credential
    key (QUICKSAND_CREDENTIAL_KEY or config/credential-key). See docs/account-credentials.md.
  - Accounts are inserted on first startup only; use --wipe-db when switching
    credentials or the account row in SQLite will stay stale.
  - IMAPS (993) and SMTP STARTTLS (587/465) require §4a TLS support in the app.
    Cleartext IMAP/SMTP and ./scripts/imap-probe.sh against 993 work today.
EOF
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
CONFIG_FILE="${QUICKSAND_CONFIG:-${REPO_ROOT}/config/application-local.conf}"
WIPE_DB="${WIPE_DB:-0}"
PROBE="${PROBE:-0}"

for arg in "$@"; do
  case "${arg}" in
    --wipe-db) WIPE_DB=1 ;;
    --probe) PROBE=1 ;;
    *)
      echo "Unknown argument: ${arg} (use --help)" >&2
      exit 2
      ;;
  esac
done

cd "${REPO_ROOT}"

# shellcheck source=ensure-credential-key.sh
source "${SCRIPT_DIR}/ensure-credential-key.sh"
ensure_credential_key "${REPO_ROOT}"

JAR="${REPO_ROOT}/target/quicksand.jar"
if [[ ! -f "${JAR}" ]]; then
  cat >&2 <<EOF
Error: ${JAR} not found.

Build the project first:
  mvn -DskipTests package
EOF
  exit 1
fi

CONFIG_DIR=""
cleanup() {
  if [[ -n "${CONFIG_DIR}" && -d "${CONFIG_DIR}" ]]; then
    rm -rf "${CONFIG_DIR}"
  fi
}
trap cleanup EXIT

write_config() {
  local dest="$1"
  cat >"${dest}" <<EOF
server = {
  port: 8080,
  host: "127.0.0.1"
}

database = {
  path: "./target/db/quicksand-real.sqlite"
}

mail_fetcher = {
  enabled: true,
  period_seconds: 15
}

mail_sender = {
  enabled: true,
  period_seconds: 15,
  max_attempts: 3,
  retry_delay_seconds: 60
}

mailbox_action_sync = {
  enabled: true,
  period_seconds: 15,
  retry_delay_seconds: 60,
  draft_debounce_seconds: 5
}

demo = {
  enabled: false
}

accounts = [
  {
    name: "${ACCOUNT_NAME}"
    imap_host: "${IMAP_HOST}"
    imap_port: ${IMAP_PORT}
    imap_username: "${IMAP_USER}"
    imap_password: "${IMAP_PASSWORD}"
    smtp_host: "${SMTP_HOST}"
    smtp_port: ${SMTP_PORT}
    smtp_username: "${SMTP_USER}"
    smtp_password: "${SMTP_PASSWORD}"
  }
]
EOF
}

if [[ -f "${CONFIG_FILE}" ]]; then
  CONFIG_DIR="$(mktemp -d)"
  cp "${CONFIG_FILE}" "${CONFIG_DIR}/application.conf"
elif [[ -n "${IMAP_HOST:-}" && -n "${IMAP_USER:-}" && -n "${IMAP_PASSWORD:-}" ]]; then
  ACCOUNT_NAME="${ACCOUNT_NAME:-${IMAP_USER}}"
  if [[ "${IMAP_NO_SSL:-}" == "1" ]]; then
    IMAP_PORT="${IMAP_PORT:-143}"
  else
    IMAP_PORT="${IMAP_PORT:-993}"
  fi
  SMTP_HOST="${SMTP_HOST:-${IMAP_HOST}}"
  SMTP_PORT="${SMTP_PORT:-587}"
  SMTP_USER="${SMTP_USER:-${IMAP_USER}}"
  SMTP_PASSWORD="${SMTP_PASSWORD:-${IMAP_PASSWORD}}"

  CONFIG_DIR="$(mktemp -d)"
  write_config "${CONFIG_DIR}/application.conf"
else
  cat >&2 <<EOF
Error: no account configuration found.

Either create ${CONFIG_FILE}:
  cp config/application-local.conf.example config/application-local.conf

Or pass IMAP_HOST, IMAP_USER, and IMAP_PASSWORD in the environment.

Use --help for details.
EOF
  exit 1
fi

DB_PATH="$(
  python3 - <<'PY' "${CONFIG_DIR}/application.conf"
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r'path:\s*"([^"]+)"', text)
if not match:
    raise SystemExit("Could not read database.path from config")
print(match.group(1))
PY
)"

if [[ "${PROBE}" == "1" ]]; then
  PROBE_ARGS=(--host "${IMAP_HOST:-}")
  if [[ -n "${IMAP_PORT:-}" ]]; then
    PROBE_ARGS+=(--port "${IMAP_PORT}")
  fi
  if [[ -n "${IMAP_USER:-}" ]]; then
    PROBE_ARGS+=(--user "${IMAP_USER}")
  fi
  if [[ -n "${IMAP_PASSWORD:-}" ]]; then
    PROBE_ARGS+=(--password "${IMAP_PASSWORD}")
  fi
  if [[ "${IMAP_NO_SSL:-}" == "1" ]]; then
    PROBE_ARGS+=(--no-ssl)
  fi

  if [[ -f "${CONFIG_FILE}" ]]; then
    mapfile -t PARSED < <(
      python3 - <<'PY' "${CONFIG_DIR}/application.conf"
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
account = text.split("accounts = [", 1)[1].split("}", 1)[0]
fields = {
    "host": r'imap_host:\s*"([^"]+)"',
    "port": r"imap_port:\s*(\d+)",
    "user": r'imap_username:\s*"([^"]+)"',
    "password": r'imap_password:\s*"([^"]+)"',
}
values = {key: re.search(pattern, account).group(1) for key, pattern in fields.items()}
print(values["host"])
print(values["port"])
print(values["user"])
print(values["password"])
PY
    )
    PROBE_ARGS=(--host "${PARSED[0]}" --port "${PARSED[1]}" --user "${PARSED[2]}" --password "${PARSED[3]}")
    if [[ "${PARSED[1]}" == "143" ]]; then
      PROBE_ARGS+=(--no-ssl)
    fi
  fi

  echo "Probing IMAP server..."
  "${SCRIPT_DIR}/imap-probe.sh" "${PROBE_ARGS[@]}"
  echo
fi

if [[ "${WIPE_DB}" == "1" ]]; then
  if [[ "${DB_PATH}" != /* ]]; then
    DB_PATH="${REPO_ROOT}/${DB_PATH#./}"
  fi
  echo "Removing database: ${DB_PATH}"
  rm -f "${DB_PATH}" "${DB_PATH}-wal" "${DB_PATH}-shm"
fi

IMAP_PORT_HINT="$(
  python3 - <<'PY' "${CONFIG_DIR}/application.conf"
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
account = text.split("accounts = [", 1)[1].split("}", 1)[0]
match = re.search(r"imap_port:\s*(\d+)", account)
print(match.group(1) if match else "")
PY
)"
SMTP_PORT_HINT="$(
  python3 - <<'PY' "${CONFIG_DIR}/application.conf"
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
account = text.split("accounts = [", 1)[1].split("}", 1)[0]
match = re.search(r"smtp_port:\s*(\d+)", account)
print(match.group(1) if match else "")
PY
)"

if [[ "${IMAP_PORT_HINT}" == "993" || "${SMTP_PORT_HINT}" == "587" || "${SMTP_PORT_HINT}" == "465" ]]; then
  : # TLS/STARTTLS is enabled automatically for standard ports
elif [[ "${IMAP_PORT_HINT}" == "143" || "${SMTP_PORT_HINT}" == "25" ]]; then
  cat >&2 <<EOF
Note: using cleartext mail ports (IMAP ${IMAP_PORT_HINT}, SMTP ${SMTP_PORT_HINT}).
STARTTLS on 143 is enabled automatically; port 25 remains cleartext.
EOF
fi

echo "Starting Quicksand at http://${HOST}:${PORT}/"
echo "Config: ${CONFIG_FILE}"
echo "Database: ${DB_PATH}"
echo

exec java \
  -cp "${CONFIG_DIR}:${JAR}:${REPO_ROOT}/target/libs/*" \
  -Dserver.host="${HOST}" \
  -Dserver.port="${PORT}" \
  -Ddemo.enabled=false \
  -Dmail_fetcher.enabled=true \
  -Dmail_sender.enabled=true \
  -Dmailbox_action_sync.enabled=true \
  -Duser.language=en -Duser.country=DE -Duser.timezone=Europe/Berlin \
  net.aggregat4.quicksand.Main
