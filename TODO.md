# TODO

## Verified Baseline (2026-05-19)

- Java 25 application on Helidon 4
- runnable JVM artifact is `target/quicksand.jar`
- `mvn clean test` is green on `main`
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

## IMAP action sync (merged to `main`)

Local-first mailbox actions with queued IMAP replay. Spec: [`specs/imap-action-sync.md`](specs/imap-action-sync.md).

### Shipped

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
| Recovery | Sync status retry/dismiss/abandon/rollback/reset; queue retention cleanup |
| Tooling | Typed action enums; `./scripts/imap-probe.sh` |

**Deferred:** UIDPLUS COPY/delete fallback when the server lacks `UID MOVE`.

---

## Roadmap (post action-sync)

| # | Slice | Goal | When to pick |
|---|-------|------|--------------|
| ~~1~~ | ~~**§1b SPECIAL-USE folder setup UX**~~ | ~~Smoother first connect~~ | **Done** |
| ~~1~~ | ~~**§2a CONDSTORE inbound sync**~~ | ~~Cut poll cost on large mailboxes~~ | **Done** |
| ~~1~~ | ~~**§2b QRESYNC → §2c IDLE**~~ | ~~Faster expunge/new-mail detection; optional push~~ | **Done** |
| 1 | **§3 incoming attachments** | Real attachment bytes during IMAP sync | **Next** — when viewer attachment UX matters |
| 2 | **§4 runtime/schema hardening** | Safer non-local deployment | Before exposing beyond local/dev |
| ~~3~~ | ~~**§4a Real IMAP/SMTP TLS**~~ | ~~Connect to Gmail/Fastmail-style servers~~ | **Done** |
| 4 | **§5 new-mail notifications** | Subtle in-app cues + optional desktop alerts | After §2c or alongside polling |

Probe modern servers before §2: `./scripts/imap-probe.sh`.

---

## Backlog: real IMAP server connections

Use `./scripts/start-real-server.sh` against a configured account (not demo/GreenMail).

1. Build: `mvn -DskipTests package`
2. Copy and edit local credentials: `cp config/application-local.conf.example config/application-local.conf`
3. Start: `./scripts/start-real-server.sh` (or pass `IMAP_HOST` / `IMAP_USER` / `IMAP_PASSWORD`; use `--help`)
   - Uses a separate DB path by default (`target/db/quicksand-real.sqlite`) so demo data stays untouched
   - `--wipe-db` when switching credentials — accounts are bootstrapped with `INSERT OR IGNORE`; config changes do **not** update an existing row
4. Optional faster wake-up: set `mail_fetcher.idle_enabled = true` when the server advertises `IDLE`
5. Open `http://127.0.0.1:8080/` — expect folder-setup redirect on first connect (Archive/Trash/Sent/Drafts mapping)

Probe the server first: `./scripts/imap-probe.sh --host HOST --user USER --password PASS`, or `./scripts/start-real-server.sh --probe`.

| Scenario | Status |
|----------|--------|
| Demo / GreenMail via `./scripts/start-test-server.sh` | Works |
| Real IMAP/SMTP via `./scripts/start-real-server.sh` (993/587 TLS) | Works |
| `./scripts/imap-probe.sh` against IMAPS 993 | Works |
| Account UI / OAuth2 (Gmail sign-in) | Not implemented — config/DB credentials only |
| Password storage | Plaintext in SQLite (pre-production) |

---

## Active: §1b SPECIAL-USE folder setup UX

**Shipped:**

- auto-detect after each IMAP folder sync (`MailFetcher` → `syncMappingsAfterFolderDiscovery`)
- confirm-all banner + POST for `AUTO_DETECTED` mappings
- smarter pickers — suggested role candidates vs other folders (`optgroup`)
- conflict/missing hint copy on folder settings rows
- service + folder-settings web tests

Refs: `MailFetcher.java`, `AccountFolderMappingService.java`, `folder-settings.peb`.

---

## Active: §2a CONDSTORE inbound sync

**Shipped:**

- schema v5: per-folder `highest_modseq` + `last_full_sync_epoch_s` checkpoints
- `ImapFolderSyncEngine`: CONDSTORE incremental sync via `UID FETCH … (CHANGEDSINCE)` when supported
- daily full reconciliation fallback + naive sync on non-CONDSTORE servers (GreenMail)
- UIDVALIDITY change clears local folder mirror before resync
- unit tests for policy, engine (fake IMAP access), checkpoint persistence, GreenMail fallback

Refs: `ImapFolderSyncEngine.java`, `CondstoreSyncPolicy.java`, `ImapFolderAccess.java`.

---

## Active: §2b QRESYNC + §2c IDLE

**Shipped:**

- **QRESYNC:** open folders with stored `UIDVALIDITY` + `highest_modseq` when supported; apply `VANISHED` UIDs without a full UID scan on incremental sync
- **IDLE:** optional `mail_fetcher.idle_enabled` uses a dedicated IMAP connection + `IdleManager` on INBOX; triggers `MailFetcher.fetchNow()` while polling remains the backstop
- unit test for QRESYNC vanished-UID handling in `ImapFolderSyncEngineTest`

Refs: `AngusImapFolderAccess.java`, `ImapIdleMonitor.java`, `ImapIdleWatcher.java`, `MailFetcher.java`.

---

## Backlog reference

### 2. Inbound IMAP sync

`MailFetcher` polls (~15s). CONDSTORE/QRESYNC incremental sync when supported; daily full reconcile fallback; optional IDLE wake-up.

- **2a CONDSTORE** — shipped
- **2b QRESYNC** — shipped
- **2c IDLE** — shipped (opt-in via config)

### 3. Other mailbox gaps

- **Incoming attachment extraction** — `ImapStoreSync.downloadNewMessages` still stores empty attachments

### 5. New-mail notifications

Notify the user when background sync imports new messages, without turning Quicksand into a live SPA.

- **In-app (required):** subtle, SSR-friendly cues when the mailbox changes — e.g. account/folder badge counts, a quiet header or sidebar indicator, optional non-blocking toast/banner after poll/sync. Should respect current account/folder context and not steal focus from compose/viewer flows.
- **Desktop (optional add-on):** browser notifications via the [Notifications API](https://developer.mozilla.org/en-US/docs/Web/API/Notifications_API) for new mail when the tab is backgrounded; explicit opt-in, permission prompt, per-account/per-folder toggles, and sensible deduping (one summary vs one per message).
- **Trigger model:** derive “new since last view” from sync checkpoints / last-seen folder state rather than client-side polling of JSON endpoints; small enhancement JS in `static/js` can subscribe to lightweight poll or future push wake-up (§2c IDLE).
- **Coverage:** unit/integration tests for server-side “unseen since visit” state; Playwright cases for in-app indicator; optional manual/browser test notes for notification permission flows.

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
| main | **§2b QRESYNC + §2c IDLE** — VANISHED UID handling, optional INBOX IDLE wake-up |
| main | **§4a Real IMAP/SMTP TLS** + `./scripts/start-real-server.sh` for manual real-account testing |
| main | **Outbox → Sent mirror fix** — hide delivered rows from Outbox; refresh Sent after `APPEND_SENT` |
| main | **§2a CONDSTORE inbound sync** — incremental CHANGEDSINCE sync, folder checkpoints, daily full reconcile fallback |
| main | **INBOX-first folder sidebar** + folder setup create-and-map fixes |
| `16e6946` | **IMAP action sync merged** — queue replay, folder mappings, sync status recovery, Sent/Drafts sync |

---

Developer linting/tooling is sufficient unless new build pain appears.
