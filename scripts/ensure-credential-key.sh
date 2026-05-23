#!/usr/bin/env bash
# Sources QUICKSAND_CREDENTIAL_KEY from config/credential-key when unset.
# Creates the key file on first use. Intended to be sourced, not executed directly.

ensure_credential_key() {
  local repo_root="$1"
  if [[ -n "${QUICKSAND_CREDENTIAL_KEY:-}" ]]; then
    return 0
  fi

  local key_file="${repo_root}/config/credential-key"
  if [[ ! -f "${key_file}" ]]; then
    mkdir -p "${repo_root}/config"
    openssl rand -base64 32 >"${key_file}"
    chmod 600 "${key_file}"
    cat >&2 <<EOF
Created ${key_file}
Back up this file — losing it makes stored mailbox passwords unrecoverable.
See docs/account-credentials.md
EOF
  fi
  export QUICKSAND_CREDENTIAL_KEY="$(tr -d '\n\r' <"${key_file}")"
}
