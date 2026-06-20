# Mailbox sync identity and action reliability plan

## Status

Draft implementation plan. This plan refines
[`specs/imap-action-sync.md`](../specs/imap-action-sync.md) and replaces the earlier proposal to
deduplicate messages account-wide by Message-ID or content fingerprint.

## Problem

Quicksand applies mailbox actions locally and later replays them against IMAP. Inbound folder sync
can run while those actions are pending or being applied. The current representation and job
boundaries allow the two directions to misinterpret each other's state.

The main failures are:

- inbound sync looks up messages by bare `imap_uid`, although an IMAP UID is scoped to one mailbox
  and one UIDVALIDITY generation;
- a local move changes `messages.folder_id` but leaves the source UID on the row, temporarily
  representing a remote tuple that never existed;
- outbound action sync and inbound mailbox sync can run concurrently for the same account;
- a process failure after remote `UID MOVE` but before the SQLite update makes a successful move
  look like a missing-source conflict on retry;
- mirror eviction deletes action queue rows, losing the durable record needed for retry and
  diagnosis;
- collision handling deletes rows based on matching numeric UIDs in a target folder, even though
  equal UIDs in different folders do not imply equal messages.

Ordering action sync before fetch reduces the frequency of these failures but does not establish
correctness.

## Identity model

### Authoritative remote identity

An observed remote mailbox entry is identified only by:

```text
(account_id, remote_mailbox_name, uidvalidity, uid)
```

Within the local schema, a resolved `folder_id` may stand in for account and remote mailbox name,
but UIDVALIDITY remains part of the identity. No sync or action path may look up a row by bare UID.

### Local identity and desired location

The local row ID is the stable identity used by URLs, drafts, attachments, search, and queued user
intent. The folder shown in the UI is desired local state. During a pending move, it can differ from
the last observed remote mailbox.

The schema must represent these concepts separately:

- desired folder used by the local UI;
- last observed remote mailbox entry, which is nullable while not known;
- immutable source identity captured on the queued action;
- intended target mailbox and, after remote confirmation, its target UIDVALIDITY and UID.

A local move must never relabel a source UID as though it were already a UID in the target mailbox.

### Message-ID and fingerprints

RFC Message-ID is recovery evidence, not identity. The same Message-ID can legitimately appear in
multiple folders or in multiple deliveries. A content fingerprint is weaker still.

Message-ID may be stored and indexed to recover an interrupted action, but it must not:

- have a uniqueness constraint;
- cause account-wide merge, relocation, or deletion during ordinary inbound sync;
- be accepted as proof of a move without source disappearance, the intended target mailbox, and an
  unambiguous target match.

Do not add a fallback fingerprint in the initial refactor. Add one later only if a concrete recovery
case cannot be handled with Message-ID and target UID data.

## Required invariants

1. **Folder-scoped observation:** inbound sync resolves existing entries by exact remote tuple,
   never by UID alone.
2. **Legitimate copies are preserved:** equal Message-ID, content, or numeric UID in different
   mailboxes does not imply duplication.
3. **Desired and observed state are distinct:** local-first UI changes do not fabricate target
   remote identity.
4. **Intent is durable:** a local state change and its queue row are committed in one SQLite
   transaction, with the source tuple captured before the change.
5. **Remote application is recoverable:** every non-idempotent remote operation can be reconciled
   after failure at any point between the IMAP command and local commit.
6. **One IMAP pipeline per account:** outbound actions and inbound folder sync do not overlap for
   the same account. Different accounts may run independently.
7. **Queue history survives mirror eviction:** deleting a message or folder cannot silently delete
   unresolved action rows.
8. **Inbound sync respects intent:** a source entry owned by an unresolved move-like action is not
   re-imported into the visible source folder.
9. **Database constraints are the final guard:** exact remote tuples and active action shapes have
   appropriate unique indexes; conflict handling never deletes data merely to satisfy a constraint.

## Target data model

Implement the smallest schema change that clearly separates desired and observed state. A later
normalization into `messages` plus `mailbox_entries` is reasonable if Quicksand needs first-class
support for Gmail labels or multiple simultaneous mailbox memberships, but is not required to fix
the current races.

### `messages`

Keep `folder_id` as the desired/local folder used by the UI. Replace the ambiguous interpretation of
`imap_uid` with explicit nullable observed identity columns:

| Column | Purpose |
| --- | --- |
| `remote_folder_id` | Local folder record for the last observed remote mailbox entry |
| `remote_uidvalidity` | UIDVALIDITY belonging to `remote_folder_id` |
| `remote_uid` | UID belonging to that mailbox and UIDVALIDITY generation |
| `message_id_header` | Normalized RFC Message-ID used only as recovery evidence |

Migration requirements:

- backfill `remote_folder_id = folder_id`, `remote_uid = imap_uid`, and
  `remote_uidvalidity = folders.uidvalidity` for existing rows;
- when a folder has no stored UIDVALIDITY, leave that part of the observation null and complete it
  only after the folder is opened and its current generation is known;
- make observed identity nullable, because local-only or transitioning rows may not have a confirmed
  remote entry;
- add a unique partial index on `(remote_folder_id, remote_uidvalidity, remote_uid)` when all three
  values are non-null;
- add a non-unique index on `message_id_header` and restrict recovery queries to the account by
  joining through folders; do not denormalize `account_id` without measured need;
- remove or stop using `UNIQUE (folder_id, imap_uid)` once all callers use the new columns;
- retain compatibility accessors only for the duration of the migration and remove them in cleanup.

If SQLite table rebuilding is needed to change `NOT NULL` or foreign-key behavior, do it in one
explicit migration and test upgrade from the current schema version.

### `mailbox_action_queue`

The queue row is the durable operation record and must be useful without its message or folder rows.
Keep the existing source mailbox snapshot and add:

| Column | Purpose |
| --- | --- |
| `target_uidvalidity` | UIDVALIDITY returned or observed for a confirmed target entry |
| `target_uid` | UID returned or observed for a confirmed target entry |
| `message_id_header` | Optional subject-independent recovery evidence captured at enqueue time |
| `message_subject` | Display-only snapshot retained for conflict/status UI after mirror eviction |

Foreign-key and eviction requirements:

- `message_id`, `source_folder_id`, and `target_folder_id` are convenience links, not the sole copy
  of identity;
- rebuild them with `ON DELETE SET NULL`, or clear them explicitly before deletion while preserving
  the queue row;
- preserve source/target remote names, UIDVALIDITY, UIDs, action type, and a display subject snapshot;
- make account deletion cascade queue history; mirror reset and folder reconciliation may not delete
  it.

Use the existing execution-state vocabulary:

- `NOT_ATTEMPTED`: no remote side effect can have occurred;
- `ATTEMPTED_UNKNOWN`: an IMAP command may have succeeded, but its result was not durably confirmed;
- `CONFIRMED_APPLIED`: the remote result has been confirmed and any target tuple is stored.

Status describes scheduling and user attention; execution state describes what may have happened
remotely. Do not overload one for the other.

### Optional normalized model

If implementation reveals that a single local row must represent more than one simultaneous remote
mailbox entry, stop extending `messages` and introduce:

```text
message_contents/local_messages
mailbox_entries(id, message_id, folder_id, uidvalidity, uid, ...)
```

with a unique remote tuple on `mailbox_entries`. This is the correct extension point for label and
copy semantics. Do not approximate it with account-wide Message-ID deduplication.

## Sync architecture

### Account-scoped coordinator

Introduce an `AccountSyncCoordinator` that owns all IMAP work for an account:

```text
syncAccount(accountId):
  acquire account lock
  recover/apply due actions for account
  sync remote folders for account
  reconcile queue state from observations
  release account lock
```

Requirements:

- scheduled action sync, scheduled fetch, manual fetch, IDLE-triggered fetch, and folder sync started
  after APPEND all enter through the coordinator;
- repository claim APIs accept `accountId`; do not claim a global batch and then acquire locks in an
  arbitrary order;
- a lock covers the complete remote operation and its local result persistence;
- HTTP actions remain SQLite-only and do not wait for IMAP; SQLite transactions and constraints
  handle their concurrency with the coordinator;
- the initial implementation may use an in-process per-account lock because Quicksand is a
  single-process application. Document that multiple JVMs require a database-backed lease;
- remove `MailFetcher.preFetch` after every caller uses the coordinator.

Action-before-fetch remains useful because it shortens suppression time, but correctness must not
depend on it.

### Ordinary inbound folder sync

For each remote folder:

1. Open the folder and validate its UIDVALIDITY.
2. Match remote messages only against observed tuples in that folder and UIDVALIDITY generation.
3. Update flags only on that exact entry, unless a pending local flag action owns the same source
   tuple.
4. Before importing an unknown source tuple, check whether an unresolved move-like action owns it.
   If so, suppress the visible source import and leave reconciliation to the action state machine.
5. Otherwise insert a new local entry. Equal Message-ID elsewhere is not a reason to merge it.
6. Remove vanished/expunged observations by exact tuple, except where unresolved intent requires the
   local row to remain visible in its desired folder.
7. Feed vanished source and observed target tuples into action reconciliation.

The import decision set should therefore stay narrow:

```text
UPDATE_EXACT_ENTRY
INSERT_REMOTE_ENTRY
SUPPRESS_OWNED_SOURCE
RECONCILE_ACTION_TARGET
```

There is no general `RELOCATE_BY_MESSAGE_ID` or `DROP_DUPLICATE_BY_FINGERPRINT` outcome.

### UIDVALIDITY changes

When a folder UIDVALIDITY changes:

- no old UID from that folder may be matched to a new remote message;
- mark unresolved actions whose source tuple uses the old generation for recovery/conflict handling;
- clear observed identities from that generation without deleting durable queue rows;
- rebuild observations from the new generation using exact remote tuples;
- retain desired local rows protected by unresolved intent until that intent is recovered, abandoned,
  rolled back, or declared conflict.

Do not protect a UID merely because the same number appears in a target folder.

## Outbound action state machine

### Idempotent flag actions

Read/unread actions can be retried against their exact source tuple. Batch only actions sharing:

```text
(account_id, source_remote_name, source_uidvalidity, action_type)
```

If a prior move changes the remote tuple, process/reconcile the move first and retarget or supersede
the later flag intent according to queue order.

### Move-like actions

Archive, Trash, Junk, and explicit Move use the same state machine.

#### Before remote execution

In one SQLite transaction:

1. Read the local row and its observed source tuple.
2. Validate that the target belongs to the same account and has a remote mapping.
3. Apply the desired target folder to the local row.
4. Enqueue the action with the immutable source tuple and intended target mailbox.
5. Leave the row's observed tuple pointing to the source until remote success is confirmed, or clear
   it only if all sync queries distinguish desired from observed state.

Do not copy the source UID into target identity, and do not delete a target row because it has the
same numeric UID.

#### Remote execution and crash recovery

1. Claim the action and persist `APPLYING`.
2. Immediately before issuing `UID MOVE`, persist `ATTEMPTED_UNKNOWN`. This state is conservative:
   after a lost connection or process failure, the command may have succeeded.
3. Execute `UID MOVE` only when MOVE support and a usable target UID result are available. Preserve
   the existing decision not to add an unsafe COPY/EXPUNGE fallback.
4. When MOVE returns target UID information, persist in one SQLite transaction:
   - queue `target_uidvalidity` and `target_uid`;
   - message observed target tuple;
   - `execution_state = CONFIRMED_APPLIED`;
   - successful status/timestamps.
5. Inbound observation of source disappearance resolves suppression after the target has been
   confirmed.

There is no transaction spanning IMAP and SQLite. Recovery must explicitly handle a crash after the
server applies MOVE but before step 4 commits.

#### Recovery of `ATTEMPTED_UNKNOWN`

Before retrying a move:

1. Check the exact source tuple.
2. If the source still exists, retrying MOVE is safe.
3. If the source is absent, search only the intended target mailbox using captured Message-ID and
   other conservative metadata.
4. If exactly one strong candidate exists, adopt its target tuple and mark the action confirmed.
5. If no candidate or multiple plausible candidates exist, mark a visible conflict; do not move a
   different message and do not delete local data.

This recovery is required before the old post-success suppression window can be removed. It is not
an optional later enhancement.

### Sequential local intent and coalescing

Do not blindly coalesce all pending move-like rows by `message_id`.

- If a move is still `NOT_ATTEMPTED`, a later move may rewrite its final target while preserving the
  original source tuple.
- If a move is `ATTEMPTED_UNKNOWN` or `CONFIRMED_APPLIED`, retain ordering and reconcile it before
  applying the next move.
- A flag action after a move must ultimately use the confirmed target tuple.
- Add a partial uniqueness constraint only for action shapes the service can safely compose, such
  as one unresolved `NOT_ATTEMPTED` move intent per local message.

Document and test archive-then-delete, move-then-mark-unread, and repeated target changes.

## Mirror eviction and reset

Replace message deletion helpers that currently delete queue rows.

For expunge or UIDVALIDITY eviction:

- detach convenience foreign keys as necessary;
- preserve the queue's copied remote identity and diagnostic fields;
- transition unresolved actions to the correct recovery or conflict state;
- remove search, attachment, actor, and message data only when no desired local row must survive.

For account mirror reset:

- require unresolved actions to be explicitly abandoned/resolved according to the existing reset UI
  semantics;
- preserve local drafts as already specified;
- never silently turn a reset into remote action cancellation.

## Implementation sequence

### Phase 0 — Establish deterministic regression coverage

- Add repository tests proving the same numeric UID in two folders does not alias.
- Add tests proving the same Message-ID in two folders and two deliveries remains distinct.
- Reproduce archive/delete overlap without relying only on sleeps.
- Add fault injection at every boundary around remote MOVE and local persistence.
- Add upgrade tests from the current schema version.

Exit criterion: tests describe the current failures and the intended identity semantics without
asserting account-wide Message-ID uniqueness.

### Phase 1 — Correct folder-scoped lookup

- Replace `findByMessageUid(long)` with exact folder/UID lookup in all full and incremental sync
  paths.
- Ensure flag updates, expunge handling, and QRESYNC vanished handling use the current folder and
  UIDVALIDITY generation.
- Keep existing suppression/protection code temporarily.

Exit criterion: inbound sync cannot update or suppress an entry in another folder because its UID
number matches.

### Phase 2 — Serialize IMAP work per account

- Add `AccountSyncCoordinator`.
- Add account-scoped action claim/apply APIs.
- Route scheduled action sync, fetch, manual fetch, IDLE, and nested folder sync through it.
- Remove `MailFetcher.preFetch`.

Exit criterion: logs and concurrency tests prove that no two IMAP pipelines overlap for one account,
while separate accounts can progress independently.

### Phase 3 — Migrate desired and observed identity

- Add and backfill explicit observed remote identity columns.
- Add the exact remote tuple partial unique index.
- Change action enqueue to capture observed source identity.
- Change local move code so it updates desired folder without fabricating target identity.
- Remove target collision deletion based on numeric UID only.

Exit criterion: every row can state independently where the UI wants it and where it was last
observed remotely.

### Phase 4 — Make queue records self-contained

- Add target UID fields and captured Message-ID/display metadata.
- Change queue foreign-key behavior so mirror eviction preserves queue rows.
- Replace hard queue deletion in message/folder eviction and mirror reset.
- Add repository operations that update message observation, target tuple, execution state, and
  status in one transaction.

Exit criterion: an unresolved action remains diagnosable and recoverable after its source message
or folder row is evicted.

### Phase 5 — Implement crash-safe action reconciliation

- Implement the move state machine and `ATTEMPTED_UNKNOWN` recovery before retry.
- Persist returned MOVE target UIDVALIDITY and UID.
- Use constrained Message-ID target search only for missing-source recovery.
- Define sequential move and flag intent behavior.
- Resolve succeeded source suppression from full sync and QRESYNC observations through the same
  reconciliation API.

Exit criterion: forced termination after remote MOVE cannot cause a second move, resurrection, or
silent data deletion.

### Phase 6 — Rewrite inbound reconciliation around exact tuples

- Centralize exact-entry update, source suppression, target confirmation, and expunge handling.
- Rebuild UIDVALIDITY-change handling without queue deletion or numeric target protection.
- Remove general-purpose duplicate resolution and any Message-ID/fingerprint relocation logic.

Exit criterion: inbound sync is expressible entirely in terms of exact observations plus explicit
queued intent.

### Phase 7 — Remove compatibility band-aids

Remove only after the replacement behavior has regression coverage:

- global `findByMessageUid`;
- `moveMessageToFolderResolvingDuplicates`;
- target protection derived from the current row's source UID;
- open-ended post-success suppression heuristics;
- ad hoc source-absence resolution SQL superseded by the action reconciler;
- old `imap_uid` compatibility columns/accessors, when no callers remain.

Run unit, repository, GreenMail, and Playwright suites after removal.

### Rollout constraints

- Phase 1 and the coordinator in phase 2 are independently deployable.
- Land additive schema changes before switching readers and writers to observed identity.
- Treat phases 3–5 as one compatibility window: do not enable the new local-move representation in
  a release until the corresponding queue persistence and crash recovery are present.
- During that window, dual-read or compatibility accessors must have one documented precedence
  rule; do not let old `imap_uid` and new observed identity independently drive behavior.
- Remove compatibility columns only after an upgrade test proves that all live rows and unresolved
  queue actions have migrated.

### Phase 8 — Document the implemented architecture

Add a durable architecture document under `docs/`—for example
`docs/mailbox-sync-architecture.md`—after implementation is stable. It must describe:

- desired local state versus observed remote state;
- exact IMAP remote identity and UIDVALIDITY handling;
- queue status versus execution state;
- account coordinator ownership and the single-process assumption;
- MOVE crash windows and recovery;
- inbound suppression/reconciliation rules;
- mirror reset and queue retention behavior;
- supported and deliberately unsupported IMAP capability fallbacks.

Update `docs/runtime-database.md`, `specs/imap-action-sync.md`, and `AGENTS.md` where their schema,
operational, or contributor guidance would otherwise contradict the implementation.

## Verification matrix

| Scenario | Required result |
| --- | --- |
| Same UID number in Inbox and Archive | Two independent remote observations |
| Same Message-ID in two folders | Neither entry is merged or deleted |
| Local archive during source-folder sync | UI stays archived; source import is suppressed by queue identity |
| Crash immediately after `UID MOVE` | Restart reconciles target without issuing an unsafe second move |
| Crash after target tuple commit | Restart finalizes the same action idempotently |
| UIDVALIDITY changes with pending action | Old tuple is never matched to new generation; queue survives |
| Archive followed by Delete | Final intent is ordered/composed without source identity loss |
| Move followed by Mark Unread | Flag applies to the confirmed current remote tuple |
| Target has unrelated row with equal numeric UID | Neither row is deleted as a collision workaround |
| Expunge or mirror reset | Queue is retained or explicitly resolved according to policy |
| Scheduled fetch and IDLE fire together | One account pipeline runs; no overlapping IMAP mutation/read cycle |

Useful invariant checks in integration tests:

- no two rows have the same non-null exact observed remote tuple;
- every unresolved move-like action retains an immutable source tuple;
- every `CONFIRMED_APPLIED` move with a live local row has a matching observed target tuple;
- no ordinary inbound-sync branch merges rows using Message-ID alone;
- no mirror-eviction path deletes unresolved queue rows.

## Completion criteria

The refactor is complete when:

1. all inbound lookups and mutations are scoped to exact remote identity;
2. desired local folder and observed remote identity cannot be confused in the schema or APIs;
3. move-like actions recover correctly across every IMAP/SQLite crash boundary;
4. one coordinator owns all IMAP work per account;
5. mirror eviction preserves unresolved intent;
6. legitimate copies and duplicate Message-IDs are retained;
7. the collision and suppression band-aids listed above are removed;
8. the verification matrix passes without timing-dependent sleeps; and
9. the implemented architecture is recorded in `docs/` and linked from relevant contributor and
   operational documentation.
