# TODO

## Verified Baseline (2026-05-19)

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn clean test` is green on `feature/imap-action-sync-spec` (re-run after merge)
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

## Branch: `feature/imap-action-sync-spec` (16 commits ahead of origin)

Local-first mailbox actions with queued IMAP replay. Spec: [`specs/imap-action-sync.md`](specs/imap-action-sync.md).

### Shipped on this branch

| Area | What |
|------|------|
| Schema | v3 action-sync tables + **v4** `drafts.remote_imap_uid` / `remote_uidvalidity` |
| Discovery | UIDVALIDITY + SPECIAL-USE on folder sync |
| Setup | Folder mapping settings UI, remote folder creation, account blocker when mappings missing |
| Local actions | Transactional enqueue; block when required mapping missing |
| Inbound | Suppress re-import of UIDs with pending move-like actions |
| UI | Sync status view (`/accounts/{id}/sync`) + header warning when attention needed |
| Background sync | `MailboxActionSync`: read/unread, move-like (`UID MOVE`), Sent append, Drafts upsert/delete |
| Gates | Send requires **Sent** mapping; composer requires **Drafts** mapping |
| Drafts | Debounced `UPSERT_DRAFT` (`mailbox_action_sync.draft_debounce_seconds`, default 5s); `DELETE_DRAFT` on delete/send |
| Tooling | Typed action enums; `./scripts/imap-probe.sh` |

**Not in spec v1 (still open):** sync-status recovery POST actions, queue retention cleanup, COPY/delete fallback for servers without MOVE.

### Remaining to close this branch

1. **Sync status actions** — view-only today; wire POST handlers:
   - retry now
   - dismiss resolved rows
   - abandon (with confirmation)
   - rollback for eligible failed move-like actions
   - reset local mirror (account recovery; spec: last resort)
2. **Queue retention job** — purge succeeded rows after 30d, resolved rows after 90d (columns exist; no job yet)
3. **Tests** — retry/backoff integration coverage; sync-status action flows; optional Playwright for sync status page

### Deferred (someday / maybe)

- **UIDPLUS COPY/delete fallback** when the server lacks `UID MOVE` (probe with `./scripts/imap-probe.sh`; see spec)

---

## Next steps

### A. Finish and merge `feature/imap-action-sync-spec`

1. **Sync status: retry now + dismiss** — smallest recovery win
2. **Sync status: abandon** (+ rollback if v1 recovery should be complete before merge)
3. **Optional:** §1b SPECIAL-USE setup UX if first-connect is still painful

### B. After merge

| Priority | Slice | Notes |
|----------|-------|-------|
| 1 | **§1b SPECIAL-USE folder setup UX** | Auto-detect after sync, confirm-all UI, smarter pickers |
| 2 | **§2a CONDSTORE inbound sync** | Largest poll-cost win on modern servers |
| 3 | **§2b QRESYNC → §2c IDLE** | After CONDSTORE checkpoints |
| 4 | **§3 incoming attachments** | Download during IMAP sync |
| 5 | **§4 runtime/schema hardening** | Per-account folders, indexes, secrets |

---

## Backlog reference

### 1b. SPECIAL-USE folder setup UX

**Done:** attribute parsing → `folders.special_use`; unambiguous auto-detect → `account_folder_mappings`; hints in `folder-settings.peb`.

**Still needed:** auto-detect after first folder sync; confirm-all UI for `AUTO_DETECTED`; smarter pickers; conflict/missing UX; tests.

Refs: `ImapStoreSync.java`, `AccountFolderMappingService.java`, `folder-settings.peb`, `AccountWebService.java`.

### 2. Inbound IMAP sync

`MailFetcher` polls (~15s); `naiveFolderSync` fetches every UID’s flags each run. No MODSEQ/QRESYNC checkpoints yet.

- **2a CONDSTORE** — `CHANGEDSINCE` + per-folder `highest_modseq`; periodic full reconciliation; fallback to naive sync
- **2b QRESYNC** — VANISHED/new UID sets after CONDSTORE
- **2c IDLE** — optional push wake-up with polling backstop

Order: CONDSTORE → QRESYNC → IDLE. Probe: `./scripts/imap-probe.sh`.

### 3. Other mailbox gaps

- **Incoming attachment extraction** — `ImapStoreSync.downloadNewMessages` still stores empty attachments

### 4. Runtime, schema, and storage hardening

- bind address defaults (`0.0.0.0` vs local)
- SQLite/Hikari pooling
- indexes (`imap_uid`, folder paging, outbound retry scan)
- per-account folder uniqueness (`folders.name` is globally unique today)
- folder-scoped message UID identity
- `NOT NULL` / FK cascades
- replace plaintext account passwords before non-local use
- schema/constraint regression tests

---

## Recently completed

| When | What |
|------|------|
| `fe3487f` | **Drafts debounced sync** — `UPSERT_DRAFT` / `DELETE_DRAFT`, v4 migration, GreenMail tests |
| `5b0c2be` | **Sent append sync** — `APPEND_SENT` after SMTP |
| `ee3b1d3` | **IMAP capability probe** + backlog/planning refresh |
| branch | Read/unread + move-like `UID MOVE`, typed queue, sync status view, mapping UI/blocker |

---

Developer linting/tooling is sufficient unless new build pain appears.
