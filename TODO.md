# TODO

## Verified Baseline (2026-05-19)

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn clean test` is green
- `npm run test:e2e` is green
- Maven enforces the Java/Maven baseline
- Spotless with `google-java-format` and `removeUnusedImports` is wired into the Maven validate phase
- Error Prone is wired as a warning-only correctness check with a small selected rule set
- demo mail server and demo account are opt-in
- the home route has deliberate account-state handling: zero accounts render an empty state, one account redirects to that mailbox, and multiple accounts render a large account picker
- account and folder pages render from the local SQLite mirror
- initial IMAP sync bulk-fetches UIDs, selects display/search body parts with `BODY.PEEK[...]`, stores new messages transactionally, and avoids one slow body materialization per message
- account-wide message search runs against a local SQLite FTS index with paging, reset, server-rendered highlighting, and HTML body match highlighting inside sanitized viewer content
- persisted message viewer routes load real stored messages
- inbox paging, sorting, and temporal grouping have deterministic browser coverage
- demo inbox seeding is deterministic for tests and manual review
- drafts are persisted in SQLite, visible in a synthetic Drafts folder, and autosaved through the composer flow
- draft attachments are persisted in SQLite with content hashes and served back through real attachment routes
- send creates real outbound messages in a synthetic Outbox folder
- outbound messages are delivered through SMTP with persisted status and retry state
- browser and GreenMail-backed integration coverage covers draft send, attachment handoff, SMTP delivery, retry scheduling, IMAP round-trips, targeted search highlighting regressions, and common MIME body-selection shapes
- **HTML email sanitization** is covered by unit tests against realistic fixtures (newsletter, malicious vectors, styled inline CSS) and exercised end-to-end through demo inbox HTML emails
- **rich HTML demo emails** are available in demo mode for manual viewer testing (product launch digest, summer sale, flight confirmation, monthly invoice, security alert)

## Current Backlog

### 1. IMAP Action Sync (in progress on `feature/imap-action-sync-spec`)

Local-first mailbox actions with a queued remote replay model are specified in `specs/imap-action-sync.md`.

**Done:**

- schema for folder remote metadata, account folder mappings, and `mailbox_action_queue`
- folder sync captures UIDVALIDITY and special-use metadata
- account folder mapping settings UI, remote folder creation, and setup blocker for missing mappings
- local actions enqueue remote work transactionally; inbound sync suppresses pending move-like source UIDs
- account sync status view and header warnings
- background `MailboxActionSync` applies **read/unread** to IMAP with retry/conflict handling

**Still needed:**

- **remote move-like actions** — implement `UID MOVE` (then safe COPY/UID-expunge fallback where UIDPLUS allows) for MOVE, DELETE→Trash, ARCHIVE, and MARK_SPAM in `MailboxActionSync`
- **Sent append sync** after SMTP delivery
- **Drafts sync** with debounced/coalesced remote updates
- **broader GreenMail integration tests** for move/delete/trash, retry, mapping flows, Sent, and Drafts
- **sync status actions** — retry now, rollback, abandon, dismiss resolved rows, reset local mirror (spec; not all wired yet)

### 2. Other Mailbox Interaction Gaps

**Confirmed still needed:**

- **extract and persist incoming message attachments during IMAP sync**. `ImapStoreSync.downloadNewMessages` hardcodes `Collections.emptyList()` for attachments and skips `Part.ATTACHMENT` dispositions in `ImapBodyExtractor`. Stored messages therefore never expose real downloaded attachments.
- **improve IMAP sync beyond the current naive folder/message scan**. The codebase has commented-out QRESYNC/CONDSTORE paths and TODOs for `UIDVALIDITY` tracking at the message level, but only the naive UID scan is active.

### 3. Runtime, Schema, And Storage Hardening

The current defaults are acceptable for a local prototype, but they should be made explicit before treating Quicksand as a non-local or long-lived mail client.

**Confirmed still needed:**

- **decide safe default bind behavior** for local runs versus Docker. `application.conf` sets `host: "0.0.0.0"`, which is convenient for containers but broad for direct local use.
- **resolve the SQLite/Hikari pooling question** in `Main.createDataSource` or document the intended connection setup. The commented-out `SQLiteDataSource` path and the `TODO` are still there.
- **add indexes** for the query paths now known to matter. `QuicksandMigrations` has an explicit `TODO index on imap_uid since we do lookups there`. Other hot paths include folder paging/sorting (`received_date_epoch_s, id`), actor lookup by message, and queued outbound retry scanning.
- **revisit folder uniqueness**. The schema declares `folders(name TEXT UNIQUE)`, making folder names globally unique rather than per-account unique.
- **make UID lookups folder/validity-aware at the message level** before relying on UIDs as durable identity. Folder rows store `uidvalidity`, but message-level identity in queued actions and reconciliation can still be strengthened.
- **add `NOT NULL`, uniqueness, and foreign-key cascade constraints** where application invariants are now clear. The migration has a `TODO consider adding NOT NULL constraints`. Some tables (e.g. `messages.folder_id`, `messages.imap_uid`) still allow NULLs that the application never expects.
- **replace plaintext account password storage** with an explicit local secret-storage strategy before non-local use. `QuicksandMigrations` has `TODO: Store password bcrypt encrypted and salted`. Recoverable mail credentials cannot simply be bcrypt-hashed, so this needs a real design (e.g. OS keychain, master-password-encrypted store).
- **add targeted regression coverage** for schema constraints, multi-account folders, IMAP UID behavior, and deletion/cascade behavior.

## Recently Completed (since last review)

1. **IMAP action sync foundation** — v3 migration, folder metadata, account folder mappings, mapping settings UI, setup blocker, transactional enqueue, inbound UID suppression, and sync status view (`feature/imap-action-sync-spec`).
2. **Remote read/unread sync** — `MailboxActionSync` background job claims queued rows, applies `\Seen` on IMAP, and records success/retry/conflict; GreenMail-backed `MailboxActionSyncTest`.
3. **Mark read/unread wired end-to-end** — local update plus queue row; integration and Playwright coverage for toolbar and bulk actions.
4. **Archive, spam, move, and delete (local)** — local folder moves with queued remote intent; integration tests verify redirects, folder counts, and cross-account rejection.
5. **HTML sanitization and rich demo emails** — fixture-backed unit tests and visually complex demo messages for viewer testing.

## Recommended Next Slice

If picking one product-facing task next:

1. **implement remote move-like actions in `MailboxActionSync`** (`UID MOVE` for move, delete-as-trash, archive, and spam) so queued local intent is replayed to IMAP
2. **add GreenMail integration tests** for at least one move-like action end-to-end
3. **add incoming attachment extraction/persistence** when message attachments become the next mail-reading slice
4. **handle runtime/schema/storage hardening incrementally**, especially per-account folder uniqueness, before treating multi-account local state as durable

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
