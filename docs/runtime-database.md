# Runtime database notes

Quicksand stores mailbox state in a local SQLite file (default `./target/db/quicksand.sqlite`).

## SQLite configuration

Configured in `Main.createDataSource()`:

| Setting | Value | Why |
|---------|-------|-----|
| Journal mode | WAL | Concurrent readers while the writer syncs |
| Busy timeout | 30s | Wait on `SQLITE_BUSY` instead of failing viewer/list requests during background sync |
| Write retries | 8 attempts, exponential backoff | `DbUtil.withConFunction` retries transient `SQLITE_BUSY` when concurrent HTTP requests and sync jobs overlap |
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
- **Messages:** `folder_id` is desired UI location. Nullable `remote_folder_id`,
  `remote_uidvalidity`, and `remote_uid` store the last observed IMAP entry; a partial unique index
  enforces exact remote identity. The older `imap_uid` column remains for presentation callers; it
  is not a sync identity.
- **Actors:** `message_id` is `NOT NULL` with `ON DELETE CASCADE`.
- **Full-text search:** `message_search` is an FTS5 virtual table (`subject`, `body_excerpt`,
  `body`, `actors`; tokenizer `unicode61 remove_diacritics 2`). The application builds the
  `MATCH` expression from a parsed query (quoted phrases, exact tokens, and a final-token prefix
  with a three-character minimum); user input is never passed through as raw FTS syntax. Relevance
  ordering uses `bm25(message_search, 8.0, 2.0, 1.0, 5.0)` (subject, body excerpt, body, actors)
  with `received_date_epoch_s DESC, id DESC` tiebreakers; the relevance pagination cursor is
  `(rank, received_date_epoch_s, id)`. No FTS prefix index is configured yet.

Run the repeatable synthetic search benchmark before adding a prefix index or changing search query
shape:

```bash
./scripts/search-benchmark.sh
./scripts/search-benchmark.sh --sizes 10000,250000 --iterations 20
```

It reports index construction time plus median and p95 latency for result counting, newest-first
pages, and BM25 best-match pages. The benchmark is diagnostic and deliberately has no wall-clock
pass/fail threshold.

Schema version is tracked in `schema_version` (currently `4`). Existing version 3 databases are
migrated in place; stale development databases may still be wiped when fresh demo data is needed.

## Operations

- **Wipe when schema testing:** `rm -f target/db/quicksand.sqlite{,-wal,-shm}` (see `AGENTS.md` stale-database trap).
- **Backups:** copy the `.sqlite` file while the server is stopped, or use SQLite backup API; WAL sidecar files (`-wal`, `-shm`) matter for hot copies.
- **Credential key:** encrypted mailbox passwords need `config/credential-key` or `QUICKSAND_CREDENTIAL_KEY` — see [`account-credentials.md`](account-credentials.md).
