# Account credentials

Quicksand encrypts IMAP/SMTP passwords **at rest** in SQLite. Passwords in `application.conf` stay plaintext (bootstrap only); the database stores `qsenc1:…` ciphertext.

Mail servers still receive the real password (or app password) over TLS when syncing — encryption only protects a copied database file.

## Setup

**1. Generate a 32-byte key (once per machine/deployment):**

```bash
openssl rand -base64 32 > config/credential-key
chmod 600 config/credential-key
```

`config/credential-key` is gitignored. Back it up somewhere safe (password manager, encrypted backup).

**2. Provide the key when starting the JVM** (first match wins):

| Source | Example |
|--------|---------|
| Environment | `export QUICKSAND_CREDENTIAL_KEY="$(cat config/credential-key)"` |
| JVM property | `-Dquicksand.credential.key="$(cat config/credential-key)"` |
| Config | `credentials.encryption_key_base64 = "…"` in gitignored `application-local.conf` |

`./scripts/start-real-server.sh` and `./scripts/start-test-server.sh` load `config/credential-key` automatically if the env var is unset. On first run they create the file and print a warning.

**3. Keep passwords in config as today** (`imap_password` / `smtp_password`). On first insert they are encrypted into SQLite.

## Operational consequences

| Topic | What to expect |
|-------|----------------|
| **Startup** | Server refuses to start without a valid 32-byte base64 key. |
| **Existing DBs** | Plaintext rows in `accounts` are encrypted automatically on startup (`reencryptLegacyCredentials`). |
| **Changing mail passwords** | Edit config and use `--wipe-db`, or update via SQL using encrypted values. `INSERT OR IGNORE` does not refresh an existing account row. |
| **Lost key** | Stored passwords cannot be recovered. Wipe the DB (`--wipe-db`), set a new key, restart with fresh config passwords. |
| **Backups** | Backup **both** the SQLite file and `config/credential-key` (or your key source). A DB backup alone is useless. |
| **Key rotation** | Not supported. To change keys: decrypt is impossible without old key — wipe DB and re-bootstrap. |
| **Config file** | Still contains plaintext passwords; restrict file permissions; never commit `application-local.conf`. |
| **Runtime** | Decrypted passwords exist in the JVM while sync runs. Host compromise still exposes credentials. |
| **Tests / e2e** | Maven Surefire and Playwright use a fixed test key (`QUICKSAND_CREDENTIAL_KEY` in `pom.xml` / `playwright.config.mjs`). |
| **IMAP probe** | `./scripts/imap-probe.sh` still needs the password on the command line; it does not read SQLite. |

## Recommended practice

- Use provider **app passwords**, not your primary mailbox password.
- Run on `127.0.0.1` (default bind) for local use.
- Treat `target/db/*.sqlite` and `config/credential-key` like secret material.
