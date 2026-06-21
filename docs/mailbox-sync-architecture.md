# Mailbox sync architecture

## Scope

Quicksand is a local-first IMAP client. HTTP actions update SQLite and redirect immediately. A
background pipeline later applies durable intent to IMAP and merges server observations back into
the local mirror.

## IMAP model in brief

IMAP exposes an account as a collection of **mailboxes** (folders such as `INBOX` and `Archive`).
Each mailbox contains entries with flags such as `\Seen` and `\Deleted`. IMAP does not give a
message one stable, account-wide identifier. Instead, an entry has a numeric **UID** that is unique
only within its mailbox and its current **UIDVALIDITY** generation. IMAP also exposes position-based
sequence numbers, but those can change whenever the mailbox changes; a UID remains stable while its
generation exists.

UIDVALIDITY is a value supplied by the server for a mailbox. If it changes, previously recorded UIDs
for that mailbox can no longer be trusted: UID 42 before the change and UID 42 after it may refer to
unrelated entries. This is why the mailbox name and UIDVALIDITY are both part of identity below.

Copying or moving a message creates an entry in the target mailbox with a target-mailbox UID. A
move also removes the source entry, so the source UID cannot simply be relabelled as belonging to
the target. Servers can also remove entries independently (for example after an expunge), and a
sync must reconcile those disappearances with the local mirror. A full UID scan can reveal that an
entry is gone; servers supporting the QRESYNC incremental-sync extension can report the same fact
more efficiently with `VANISHED` responses. In IMAP, expunge means permanently removing entries
marked `\Deleted`.

The RFC `Message-ID` header is different from an IMAP UID. It is normally assigned by the sending
software and often helps recognize a message, but it is optional and not guaranteed to be unique.
Copies and repeated deliveries can legitimately have the same Message-ID, so Quicksand uses it
only as recovery evidence in a narrowly constrained search.

## Local-first approach

There are two timelines to keep in mind: the state the user should see now, stored in SQLite, and
the latest state observed on the IMAP server. An HTTP action first records the desired local state
and a durable instruction for the server in one database transaction. Background sync then sends
that instruction to IMAP and reconciles later server observations. Keeping desired and observed
state separate prevents a normal inbound sync from undoing a local action that is still pending.

## Folder mappings

Remote mailbox names are server-specific, so Quicksand does not assume that names such as
`Archive`, `Trash`, or `Sent` exist. Folder discovery stores the server's mailbox name,
UIDVALIDITY, and any advertised SPECIAL-USE role. Each account must have configured mappings for
Archive, Trash, Junk, Sent, and Drafts before the related flows are available. INBOX is discovered
directly and is not part of this required mapping set.

Unambiguous SPECIAL-USE attributes can seed mappings, but users confirm them in the account folder
settings. The same settings flow can explicitly create a remote mailbox. Missing or ambiguous
mappings block the affected SSR action and redirect to configuration rather than silently choosing
or creating a server folder.

## Identity

An IMAP mailbox entry is identified by the exact tuple:

```text
(account, remote mailbox, UIDVALIDITY, UID)
```

UIDs are never looked up account-wide. `messages.folder_id` is the desired folder rendered by the
UI. The last observed server entry is stored separately in `remote_folder_id`,
`remote_uidvalidity`, and `remote_uid`. A pending local move changes the desired folder without
pretending that the source UID belongs to the target mailbox.

RFC Message-ID is stored as non-unique recovery evidence. It does not merge deliveries or copies.
Ordinary inbound sync updates only an exact observed tuple.

## Action queue

A mailbox action copies its immutable source mailbox, UIDVALIDITY, and UID into
`mailbox_action_queue` in the same transaction as the local UI change. Target mailbox metadata is
also copied so the action remains diagnosable after local message or folder eviction.

The queue covers read/unread flag changes, moves, archive, delete-as-move-to-Trash, spam, Sent
append, and Draft create/update/delete. Read/unread uses `UID STORE`; all move-like operations use
`UID MOVE`; Sent and Drafts use their configured remote mailboxes. Draft updates are coalesced so
autosave does not produce an unbounded remote queue.

Queue `status` controls scheduling and user attention. `execution_state` records remote uncertainty:

- `NOT_ATTEMPTED`: no IMAP side effect can have occurred;
- `ATTEMPTED_UNKNOWN`: the command may have succeeded;
- `CONFIRMED_APPLIED`: the remote result is known and stored.

Workers claim due `PENDING` and `FAILED_RETRYABLE` rows in bounded batches. Transient failures use
exponential backoff based on the configured retry delay. Permanent failures and conflicts remain
visible on the account sync status page until the user retries, abandons, dismisses, rolls back a
safe local-only move, or resets the local mirror. Rollback is offered only when the remote command
was never attempted; uncertain remote state is not "undone" by guessing.

Mirror eviction detaches nullable convenience IDs but preserves unresolved queue rows and their
copied identities. Successful rows are retained for 30 days, resolved rows for 90 days, and
unresolved rows are not aged out.

## MOVE and recovery

MOVE is not atomic with SQLite. Before issuing `UID MOVE`, Quicksand durably changes the action to
`ATTEMPTED_UNKNOWN`. A successful MOVE stores the returned target UIDVALIDITY and UID, updates the
message observation, and marks the action successful in one SQLite transaction.

If Quicksand restarts after the server moved the message but before that transaction committed, the
source UID is absent. Recovery searches only the intended target mailbox by the captured Message-ID.
Exactly one match is adopted; zero or multiple matches become a visible conflict. Quicksand does
not retry an uncertain MOVE against an unrelated message.

Quicksand requires the IMAP MOVE capability and a returned target UID. It does not use an unsafe
COPY/delete/expunge fallback.

## Recovery operations

The account sync status page exposes queue counts, errors, source and target identity, retry, safe
local rollback, abandon, and dismissal controls. Abandoning an unresolved action explicitly accepts
that local and remote state may differ. Dismissal records resolution for a permanent failure or
conflict; it does not change remote state.

Resetting an account's local mirror is the last-resort recovery path. It marks unresolved actions
as abandoned by reset, removes mirrored folders and messages, marks mappings missing, and triggers
a fresh server sync. Local drafts and outbound-message records are preserved.

## Inbound reconciliation

For each folder generation, inbound sync:

1. resolves flags and content by exact observed tuple;
2. suppresses an unknown source tuple owned by unresolved outbound move intent;
3. imports all other unknown tuples, regardless of matching Message-ID elsewhere;
4. removes vanished observations by folder and UID while protecting exact pending source intent;
5. resolves successful source suppression from full UID scans or QRESYNC VANISHED results.

A UIDVALIDITY change invalidates every old UID in that mailbox. Old observations are removed, while
queued source tuples remain available for recovery or conflict handling.

## Concurrency ownership

`AccountSyncCoordinator` serializes all IMAP work for one account: scheduled action application,
scheduled/manual fetch, IDLE-triggered fetch, and folder reconciliation. Different accounts may run
in parallel. HTTP handlers participate only through SQLite transactions and constraints.

The coordinator is an in-process lock. Running multiple Quicksand JVMs against the same database is
unsupported; that deployment would require a database-backed account lease.

## Database constraints

The partial unique index on `(remote_folder_id, remote_uidvalidity, remote_uid)` prevents duplicate
observations of one server entry. It deliberately permits equal UIDs in different mailboxes,
different UIDVALIDITY generations, and repeated Message-ID values.

The legacy `imap_uid` column remains a presentation compatibility value. Sync identity and
reconciliation never use it without the observed folder and UIDVALIDITY.

See [`runtime-database.md`](runtime-database.md) for SQLite operational settings.
