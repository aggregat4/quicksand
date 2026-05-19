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
- **IMAP capability probe** — `./scripts/imap-probe.sh` reports server extensions and Quicksand-relevant summaries

## Current Backlog

### 1. IMAP Action Sync (in progress on `feature/imap-action-sync-spec`)

Local-first mailbox actions with a queued remote replay model are specified in `specs/imap-action-sync.md`.

**Done:**

- schema for folder remote metadata, account folder mappings, and `mailbox_action_queue`
- folder sync captures UIDVALIDITY and special-use metadata
- account folder mapping settings UI, remote folder creation, and setup blocker for missing mappings
- local actions enqueue remote work transactionally; inbound sync suppresses pending move-like source UIDs
- account sync status view and header warnings
- background `MailboxActionSync` applies **read/unread**, **move-like actions** (`UID MOVE`), and **Sent append** (`APPEND_SENT` via IMAP APPEND after SMTP) with retry/conflict handling
- send is blocked until Sent folder mapping is configured

**Still needed (finish action-sync slice):**
- **Drafts sync** with debounced/coalesced remote updates (`UPSERT_DRAFT` / `DELETE_DRAFT`)
- **sync status actions** — retry now, rollback, abandon, dismiss resolved rows, reset local mirror (spec; not all wired yet)
- **broader GreenMail integration tests** for Sent, Drafts, retry, and sync-status flows

**Deferred (someday / maybe):**

- **UIDPLUS-safe COPY/delete fallback** for servers without `UID MOVE`. Not needed for modern servers that advertise MOVE (probe with `./scripts/imap-probe.sh`). Revisit only if real accounts fail move-like sync or broad legacy IMAP support becomes a goal. See `specs/imap-action-sync.md` for unsafe-fallback notes.

#### 1b. SPECIAL-USE folder setup UX (leverage existing discovery)

RFC 6154 SPECIAL-USE attributes (`\Trash`, `\Sent`, `\Junk`, `\Archive`, `\Drafts`) align with Quicksand’s required folder roles. Discovery is already partially wired; the remaining work is to **apply** and **present** it so setup feels automatic on capable servers.

**Already done:**

- `ImapStoreSync.specialUseFor` / `specialUseFromAttributes` parse LIST attributes into `FolderSpecialUse`
- `folders.special_use` persisted on folder sync
- `AccountFolderMappingService.autoDetectMappings` writes `AUTO_DETECTED` mappings when exactly one folder matches each required role
- folder settings UI shows per-folder “Detected role” hints in dropdown options

**Still needed:**

1. **Auto-detect after folder sync** — call `autoDetectMappings(accountId)` at end of first successful `ImapStoreSync` for an account (not only when visiting settings or hitting the setup blocker). Unambiguous servers should pass `hasRequiredMappings` without manual steps.
2. **Confirm-style setup UI** — when all five roles are `AUTO_DETECTED`, show a single “Server suggested mappings” summary with one **Confirm** instead of five identical pickers.
3. **Smarter folder pickers** — per role, pre-select the matching `special_use` folder; list likely candidates first; collapse “other folders” behind a disclosure. Stop showing every mailbox in every role’s dropdown.
4. **Conflict and missing UX** — when multiple folders share a role (CONFLICT) or none do (MISSING), surface server attribute vs folder name clearly; keep manual override and “create remote folder” paths.
5. **Tests** — GreenMail or fixture folders with SPECIAL-USE attributes; assert auto-detect after sync, blocker bypass, and UI pre-selection.

**Done when:** A SPECIAL-USE-capable account (probe shows `SPECIAL-USE`) completes folder setup in one click or zero clicks; manual mapping is only needed for ambiguous or attribute-less folders.

Reference: `ImapStoreSync.java`, `AccountFolderMappingService.java`, `folder-settings.peb`, account setup blocker in `AccountWebService.java`.

### 2. Inbound IMAP Sync (incremental improvements)

Today `MailFetcher` polls on a fixed interval (default 15s) and `ImapStoreSync.naiveFolderSync` **FETCHes flags and UIDs for every message in every folder** on each run, then diffs locally. Folder rows already store `uidvalidity`; message-level MODSEQ and QRESYNC checkpoints are not persisted yet.

Reference: comments and `naiveFolderSync` in `ImapStoreSync.java`; RFC 4549 (disconnected client), RFC 7167 (CONDSTORE), RFC 5162 (QRESYNC).

Probe server support: `./scripts/imap-probe.sh`

#### 2a. CONDSTORE-aware sync (do first)

**Goal:** Only re-check messages that changed since the last successful folder sync instead of walking the full UID list every poll.

**Prerequisites:**

- Server advertises `CONDSTORE` (and usually `ENABLE`). Store per-folder sync checkpoint after each successful sync.

**Schema (folders table or new `folder_sync_state`):**

- `highest_modseq` — last seen HIGHESTMODSEQ from SELECT response
- `condstore_enabled` — whether this folder/session uses CONDSTORE
- optionally `last_sync_at` for debugging

**Implementation steps:**

1. On folder open, if store supports CONDSTORE, issue `ENABLE CONDSTORE` (via Angus `IMAPFolder` / protocol) once per session or per folder as required.
2. After `SELECT`, read and persist `HIGHESTMODSEQ` from the folder status.
3. Replace or narrow the full `updateLocalMessages` scan:
   - If `highest_modseq` is known: `UID FETCH` (or FETCH) with `CHANGEDSINCE <modseq>` (and `MODSEQ` in fetch items) to get only changed messages.
   - Update local `read` / flags for returned messages only.
4. Still run a **periodic full UID reconciliation** (e.g. daily or on UIDVALIDITY change) to catch servers/clients that miss updates.
5. On `UIDVALIDITY` change: clear folder message mirror for that folder and treat as full resync (same as today’s invalidation story).
6. Gate behind capability check; fall back to `naiveFolderSync` when CONDSTORE is absent.
7. Tests: GreenMail or mocked protocol; assert fewer FETCH calls / only changed UIDs updated when MODSEQ advances.

**Done when:** Large INBOX poll no longer fetches every UID’s flags on every 15s tick; flag changes from another client appear within one poll cycle.

#### 2b. QRESYNC (after CONDSTORE)

**Goal:** Incremental resync using vanished/new UID sets instead of inferring expunges only by “local UID not in remote set.”

**Prerequisites:**

- Server advertises `QRESYNC` and `CONDSTORE`; persisted per-folder checkpoint.

**Schema (extend folder sync checkpoint):**

- `uidvalidity` (already on `folders`)
- `modseq` / `highest_modseq` (from CONDSTORE step)
- `uidnext` — last seen UIDNEXT from SELECT
- optional `last_known_uid` — high-water mark for sanity checks

**Implementation steps:**

1. Use `SELECT` (or `EXAMINE`) with `QRESYNC (uidvalidity modseq uidnext)` parameters from stored checkpoint when checkpoint is valid.
2. Process **VANISHED** (or VANISHED EARLIER) UIDs: remove local messages for those UIDs without scanning the full remote UID set.
3. Process **new** messages from QRESYNC response: enqueue body download for UIDs not in local mirror.
4. Integrate with pending mailbox-action queue: do not re-import UIDs suppressed by pending move-like actions (existing rule).
5. If QRESYNC fails (checkpoint stale, server error), fall back to one full `naiveFolderSync` and refresh checkpoint.
6. Tests: simulate UIDVALIDITY bump and vanished UIDs; verify local expunge without full folder scan.

**Done when:** Expunges and arrivals are detected via QRESYNC on capable servers; full UID scans become rare fallback only.

#### 2c. IDLE (optional, after CONDSTORE)

**Goal:** Reduce latency and polling load: wake `MailFetcher` when the server signals new mail instead of relying only on a fixed timer.

**Prerequisites:**

- Server advertises `IDLE`. Long-lived IMAP connection per account (or per folder for INBOX-only v1).

**Implementation steps:**

1. Add `ImapIdleListener` (or extend `MailFetcher`) with a dedicated thread per connected account.
2. `SELECT INBOX` (or configured folders), enter `IDLE`, block until EXISTS/RECENT/EXPUNGE notification.
3. On notification: exit IDLE, run **targeted sync** for affected folder(s) only (prefer CONDSTORE/QRESYNC path when available), then re-enter IDLE.
4. Handle timeouts: RFC suggests periodic NOOP every 29 minutes; reconnect on connection drop.
5. Make IDLE opt-in via config (`mail_fetcher.idle_enabled`) so polling remains the safe default.
6. When IDLE is enabled, **increase** `mail_fetcher.period_seconds` as a backstop (e.g. 5–15 minutes) rather than replacing polling entirely.
7. Tests: GreenMail IDLE support if available; otherwise integration test with mock/store that signals folder events.

**Done when:** New mail on IDLE-capable servers triggers sync within seconds without 15s poll; polling remains as safety net.

**Recommended order:** CONDSTORE → QRESYNC → IDLE. Each step should degrade gracefully to the previous level.

### 3. Other Mailbox Interaction Gaps

**Confirmed still needed:**

- **extract and persist incoming message attachments during IMAP sync**. `ImapStoreSync.downloadNewMessages` hardcodes `Collections.emptyList()` for attachments and skips `Part.ATTACHMENT` dispositions in `ImapBodyExtractor`. Stored messages therefore never expose real downloaded attachments.

### 4. Runtime, Schema, And Storage Hardening

The current defaults are acceptable for a local prototype, but they should be made explicit before treating Quicksand as a non-local or long-lived mail client.

**Confirmed still needed:**

- **decide safe default bind behavior** for local runs versus Docker. `application.conf` sets `host: "0.0.0.0"`, which is convenient for containers but broad for direct local use.
- **resolve the SQLite/Hikari pooling question** in `Main.createDataSource` or document the intended connection setup. The commented-out `SQLiteDataSource` path and the `TODO` are still there.
- **add indexes** for the query paths now known to matter. `QuicksandMigrations` has an explicit `TODO index on imap_uid since we do lookups there`. Other hot paths include folder paging/sorting (`received_date_epoch_s, id`), actor lookup by message, and queued outbound retry scanning.
- **revisit folder uniqueness**. The schema declares `folders(name TEXT UNIQUE)`, making folder names globally unique rather than per-account unique.
- **make UID lookups folder/validity-aware at the message level** before relying on UIDs as durable identity. Folder rows store `uidvalidity`; message-level identity in queued actions and reconciliation can still be strengthened.
- **add `NOT NULL`, uniqueness, and foreign-key cascade constraints** where application invariants are now clear. The migration has a `TODO consider adding NOT NULL constraints`. Some tables (e.g. `messages.folder_id`, `messages.imap_uid`) still allow NULLs that the application never expects.
- **replace plaintext account password storage** with an explicit local secret-storage strategy before non-local use. `QuicksandMigrations` has `TODO: Store password bcrypt encrypted and salted`. Recoverable mail credentials cannot simply be bcrypt-hashed, so this needs a real design (e.g. OS keychain, master-password-encrypted store).
- **add targeted regression coverage** for schema constraints, multi-account folders, IMAP UID behavior, and deletion/cascade behavior.

## Recently Completed (since last review)

1. **IMAP action sync foundation** — v3 migration, folder metadata, account folder mappings, mapping settings UI, setup blocker, transactional enqueue, inbound UID suppression, and sync status view (`feature/imap-action-sync-spec`).
2. **Sent append sync** — after SMTP delivery, `APPEND_SENT` queue rows are replayed to the configured remote Sent mailbox via IMAP APPEND.
3. **Remote read/unread and move-like sync** — `MailboxActionSync` applies flag and `UID MOVE` actions; GreenMail tests for read/unread, archive, delete, spam, move.
4. **Typed mailbox action queue** — domain enums, `EnumSql`, repository and sync-status UI alignment.
5. **IMAP capability probe** — `ImapCapabilityProbe` CLI and `./scripts/imap-probe.sh`.
6. **Mark read/unread and local mailbox actions** — end-to-end with integration and Playwright coverage.
7. **HTML sanitization and rich demo emails** — fixture-backed unit tests and visually complex demo messages for viewer testing.

## Recommended Next Slice

If picking one product-facing task next:

1. **finish IMAP action sync** — Drafts debounced sync, sync-status actions (per `specs/imap-action-sync.md`); Drafts mapping required for composer
2. **SPECIAL-USE folder setup UX** — small slice, high UX win on modern servers; unblocks frictionless first connect (see §1b)
3. **CONDSTORE-aware inbound sync** — first incremental improvement; biggest win for large mailboxes on modern servers (see §2a)
4. **QRESYNC then IDLE** — after CONDSTORE checkpointing exists (see §2b, §2c)
5. **incoming attachment extraction/persistence** when message attachments become the next mail-reading slice
6. **runtime/schema/storage hardening incrementally**, especially per-account folder uniqueness, before treating multi-account local state as durable

**Someday / maybe:** UIDPLUS COPY/delete fallback for servers without `MOVE` (only if compatibility data or user reports justify it).

Developer linting/tooling is sufficient for now; do not make tooling the next primary slice unless a new build pain appears.
