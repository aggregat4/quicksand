# TODO

## Verified Baseline (2026-05-19)

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn clean test` is green (re-verify after merging `feature/imap-action-sync-spec`)
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

## Branch: `feature/imap-action-sync-spec`

Active work for local-first mailbox actions with queued IMAP replay. Spec: `specs/imap-action-sync.md`.

### Shipped on this branch (committed)

| Area | What |
|------|------|
| Schema | v3: folder remote metadata, `account_folder_mappings`, `mailbox_action_queue` |
| Discovery | UIDVALIDITY + SPECIAL-USE on folder sync |
| Setup | Folder mapping settings UI, remote folder creation, account blocker when mappings missing |
| Local actions | Transactional enqueue; block when required mapping missing |
| Inbound | Suppress re-import of UIDs with pending move-like actions |
| UI | Account sync status view (`/accounts/{id}/sync`) + header warning when attention needed |
| Background sync | `MailboxActionSync`: read/unread, move-like (`UID MOVE`), **Sent append**, **Drafts upsert/delete** |
| Send gate | Composer send blocked until **Sent** mapping is configured |
| Drafts gate | Composer blocked until **Drafts** mapping is configured |
| Drafts sync | Debounced `UPSERT_DRAFT` (v4 migration for remote UID); `DELETE_DRAFT` on delete/send |
| Tooling | Typed action enums, IMAP capability probe CLI |

### Remaining on this branch (to close the action-sync slice)

1. **Sync status actions** (read-only table today; no POST handlers yet):
   - retry now
   - dismiss resolved rows
   - abandon (with confirmation)
   - rollback for eligible failed move-like actions
   - reset local mirror (account recovery; spec: last resort)
3. **Queue retention job** — 30d succeeded / 90d resolved rows (schema has timestamps; no cleanup job yet)
4. **Integration tests** — retry paths, sync-status actions, full Sent/Drafts flows per spec

### Deferred on this branch (someday / maybe)

- **UIDPLUS COPY/delete fallback** for servers without `UID MOVE`. Not needed when the server advertises MOVE (`./scripts/imap-probe.sh`). See `specs/imap-action-sync.md`.

---

## Next steps (recommended order)

### A. Close `feature/imap-action-sync-spec`

1. **Sync status: retry now + dismiss** — smallest recovery win; unblocks users with stuck queue rows
2. **Sync status: abandon** (+ rollback for move-like failures if you want v1 recovery complete)
3. **Optional before merge:** §1b SPECIAL-USE setup UX (below) if first-connect friction is still painful

### B. After action-sync branch merges

| Priority | Slice | Why |
|----------|-------|-----|
| 1 | **§1b SPECIAL-USE folder setup UX** | Auto-detect after sync, confirm-all UI, smarter pickers — high UX on modern servers |
| 2 | **§2a CONDSTORE inbound sync** | Biggest poll-cost win for large mailboxes |
| 3 | **§2b QRESYNC → §2c IDLE** | After CONDSTORE checkpoints exist |
| 4 | **§3 incoming attachments** | Real attachment download during IMAP sync |
| 5 | **§4 runtime/schema hardening** | Per-account folder uniqueness, indexes, secrets |

---

## Backlog reference

### 1b. SPECIAL-USE folder setup UX

Discovery is wired; presentation and timing are not.

**Already done:** attribute parsing → `folders.special_use`; unambiguous auto-detect → `account_folder_mappings`; hints in `folder-settings.peb`.

**Still needed:**

1. Auto-detect after first folder sync (not only on settings/blocker visit)
2. Confirm-style UI when all five roles are `AUTO_DETECTED`
3. Smarter per-role pickers (candidates first, “other folders” collapsed)
4. Clear conflict/missing UX
5. Tests (GreenMail SPECIAL-USE folders, blocker bypass)

Reference: `ImapStoreSync.java`, `AccountFolderMappingService.java`, `folder-settings.peb`, `AccountWebService.java`.

### 2. Inbound IMAP Sync (incremental improvements)

Today `MailFetcher` polls (~15s) and `naiveFolderSync` fetches every UID’s flags each run. Folder rows store `uidvalidity`; per-folder MODSEQ / QRESYNC checkpoints are not persisted.

Probe: `./scripts/imap-probe.sh` · Reference: `ImapStoreSync.java`, RFC 4549 / 7167 / 5162.

#### 2a. CONDSTORE-aware sync (do first)

**Goal:** Re-check only changed messages via `CHANGEDSINCE` instead of full UID walks every poll.

**Needs:** `CONDSTORE` + `ENABLE`; persist `highest_modseq` per folder; periodic full reconciliation; fallback to `naiveFolderSync`.

#### 2b. QRESYNC (after CONDSTORE)

**Goal:** Process VANISHED/new UID sets instead of inferring expunges by full UID diff.

**Needs:** persisted `uidvalidity`, `modseq`, `uidnext`; integrate with pending action queue; fallback full sync on stale checkpoint.

#### 2c. IDLE (optional, after CONDSTORE)

**Goal:** Wake fetcher on server push; keep polling as backstop (`mail_fetcher.idle_enabled`).

**Order:** CONDSTORE → QRESYNC → IDLE (each degrades gracefully).

### 3. Other mailbox gaps

- **Incoming attachment extraction** during IMAP sync (`ImapStoreSync.downloadNewMessages` still passes empty attachments).

### 4. Runtime, schema, and storage hardening

- safe default bind (`0.0.0.0` vs local)
- SQLite/Hikari pooling clarity
- indexes (e.g. `imap_uid` lookups)
- per-account folder uniqueness (`folders.name` is globally unique today)
- folder-scoped UID identity on messages
- `NOT NULL` / FK cascades where invariants are clear
- replace plaintext account passwords before non-local use
- targeted schema/constraint regression tests

---

## Recently completed (changelog)

1. **Drafts debounced sync** — `UPSERT_DRAFT` / `DELETE_DRAFT`, coalesced autosave, remote UID on `drafts` (v4 migration).
2. **Sent append sync** — `APPEND_SENT` after SMTP to configured Sent folder; send gated on Sent mapping (`5b0c2be`).
3. **IMAP capability probe** — CLI + `./scripts/imap-probe.sh`; backlog reprioritized (CONDSTORE, deferred COPY/delete).
3. **Remote read/unread and move-like sync** — `UID MOVE`; GreenMail tests.
4. **IMAP action sync foundation** — v3 schema, mappings UI, blocker, queue, sync status view.
5. **Typed mailbox action queue** — domain enums, `EnumSql`, repository/UI alignment.
6. **Mark read/unread and local mailbox actions** — SSR flow + Playwright coverage.
7. **HTML sanitization and rich demo emails** — fixture tests + demo viewer content.

---

Developer linting/tooling is sufficient for now unless new build pain appears.
