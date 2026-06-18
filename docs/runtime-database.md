# Runtime database notes

Quicksand stores mailbox state in a local SQLite file (default `./target/db/quicksand.sqlite`).

## SQLite configuration

Configured in `Main.createDataSource()`:

| Setting | Value | Why |
|---------|-------|-----|
| Journal mode | WAL | Concurrent readers while the writer syncs |
| Busy timeout | 30s | Wait on `SQLITE_BUSY` instead of failing viewer/list requests during background sync |
| Foreign keys | `ON` (`enforceForeignKeys`) | Schema uses `ON DELETE CASCADE` on mirrored mail tables |
| Open mode | `READWRITE`, `CREATE`, `NOMUTEX` | Single-process JVM; driver handles locking |

Deleting a folder cascades to its mirrored messages and actors. Deleting an account cascades to its folders (and thus messages).

## HikariCP pool

| Setting | Value | Why |
|---------|-------|-----|
| `maximumPoolSize` | `3` | SQLite serializes writers; allow HTTP requests while background sync jobs are active |
| `minimumIdle` | `1` | Keep one warm connection for the long-lived server process |

Repository code that loads related rows (actors, attachments) should reuse the caller's `Connection` instead of opening another pool checkout. Message load and list paths follow that rule; without it a single viewer request could need three connections and exhaust a pool of two under concurrent load.

Do not set the pool to `1` with the current repository layer — some code paths may still open a second connection. If Quicksand ever moves to a server-grade database, revisit pool sizing separately.

## Schema constraints

- **Folders:** unique on `(account_id, name)` — not globally on `name`. Two accounts may both have a display name `Inbox`.
- **Messages:** `folder_id` and `imap_uid` are `NOT NULL`; unique on `(folder_id, imap_uid)` enforces per-folder IMAP identity.
- **Actors:** `message_id` is `NOT NULL` with `ON DELETE CASCADE`.

Schema version is tracked in `schema_version` (currently `2`). Existing databases with an older version must be wiped before starting the server.

## Operations

- **Wipe when schema testing:** `rm -f target/db/quicksand.sqlite{,-wal,-shm}` (see `AGENTS.md` stale-database trap).
- **Backups:** copy the `.sqlite` file while the server is stopped, or use SQLite backup API; WAL sidecar files (`-wal`, `-shm`) matter for hot copies.
- **Credential key:** encrypted mailbox passwords need `config/credential-key` or `QUICKSAND_CREDENTIAL_KEY` — see [`account-credentials.md`](account-credentials.md).
