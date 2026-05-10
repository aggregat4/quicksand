# IMAP Mailbox Action Sync

## Status

Draft spec for making local mailbox actions propagate to the configured IMAP server without
blocking the server-rendered UI or allowing normal sync to undo local user intent.

## Product Decisions

- Delete means move to Trash, not immediate hard delete.
- Missing required folder mappings block the action and send the user into a configuration flow.
- Folder mappings should also be available from account settings.
- Quicksand should warn at account level for sync failures in the first iteration.
- The warning should link to a dedicated sync status/error view.
- Repeatedly failing moves should eventually offer rollback, but not after the first transient
  failure.
- Remote folder creation should be available from the folder mapping UI.
- Sent and Drafts IMAP sync are part of this feature area, though implementation can still be
  sliced.
- Archive, Trash, Junk/Spam, Sent, and Drafts mappings are required before the account is usable.
- Mailbox view should be blocked until required mappings are configured.
- Sending should be blocked until Sent mapping is configured.
- Draft autosave/composer use should be blocked until Drafts mapping is configured.
- Account-level sync warnings should appear in the global header and link to the account sync
  status view.
- The setup blocker appears after the first successful remote connection/folder discovery if
  required mappings are still missing.
- Successful queue rows are retained for 30 days.
- Resolved failed/conflict queue rows are retained for 90 days.
- Resolved failed/conflict rows can be manually dismissed from the sync status view.
- Rollback is available only from the sync status view.
- The first setup blocker implementation should redirect to the folder mapping settings page.
- Folder mapping settings are account-scoped at `/accounts/{accountId}/settings/folders`.
- Reset local mirror preserves local drafts by default.
- Fine-grained abandon is available from the sync status view when Quicksand can clearly describe
  the consequence of abandoning the specific queued action.

## Problem

Quicksand currently applies mailbox actions only to the local SQLite mirror:

- mark read/unread updates local flags
- delete removes the local message
- archive moves the local message to a local `Archive` folder
- spam moves the local message to a local `Spam` folder
- move changes the local message's folder

The IMAP server is not updated. A later sync can therefore conflict with local state. The most
obvious failure is a local move/delete being undone because the remote message still exists in the
source mailbox and the sync code imports it again.

## Goals

- Keep toolbar and viewer actions responsive.
- Preserve the SSR post/redirect flow.
- Apply local UI state immediately.
- Persist enough remote identity to replay each action against IMAP later.
- Retry transient IMAP failures with bounded backoff.
- Avoid normal inbound sync undoing queued local changes.
- Support account-specific special folders such as Archive, Trash, Junk/Spam, Drafts, and Sent.
- Make folder mapping configurable when server metadata is absent or ambiguous.
- Keep behavior understandable in the UI when remote sync is pending or failed.

## Non-Goals

- Do not turn mailbox interactions into synchronous IMAP requests from HTTP handlers.
- Do not make the browser the source of truth for sync state.
- Do not implement full conflict-free replication; this is a pragmatic local-first action queue.
- Do not require all Sent/Drafts synchronization behavior to ship in the first implementation slice.

## Design Summary

Mailbox actions should be local-first:

1. The HTTP POST validates the action.
2. A single SQLite transaction applies the local state change and enqueues a remote action.
3. The response redirects immediately.
4. A background worker sends queued actions to IMAP.
5. Inbound sync treats pending local actions as authoritative until the remote action succeeds,
   fails permanently, or is resolved as a conflict.

This makes local UI behavior deterministic while remote synchronization becomes retryable,
observable, and recoverable.

## Data Model

### Folder Metadata

Extend `folders` so a local folder can be mapped to a remote IMAP mailbox:

- `account_id`
- `name` - local display name
- `remote_name` - exact IMAP mailbox name
- `special_use` - nullable enum-like text: `INBOX`, `ARCHIVE`, `TRASH`, `JUNK`, `SENT`, `DRAFTS`
- `uidvalidity`
- `last_seen_uid`
- `sync_enabled`
- `mapping_status`: `AUTO_DETECTED`, `USER_CONFIRMED`, `MISSING`, `CONFLICT`

Needed constraints/indexes:

- unique local folder name per account
- unique remote mailbox name per account
- index on `(account_id, special_use)`

### Account Folder Mappings

Add an explicit `account_folder_mappings` table for required per-account special folder choices.
This table represents configuration even when the mapped local folder does not exist yet or has
become missing.

- `id`
- `account_id`
- `special_use`: `ARCHIVE`, `TRASH`, `JUNK`, `SENT`, `DRAFTS`
- `folder_id`
- `remote_name`
- `status`: `MISSING`, `AUTO_DETECTED`, `USER_CONFIRMED`, `CONFLICT`
- `created_at`
- `updated_at`

Constraints/indexes:

- unique `(account_id, special_use)`
- index `(account_id, status)`

The `folders.special_use` column is discovery metadata from the server. The mapping table is the
user/application configuration that decides which folder Quicksand uses for each required role.

### Message Remote Identity

Remote identity should be treated as folder-scoped:

- `messages.folder_id`
- `messages.imap_uid`
- `messages.uidvalidity` or derived through `folders.uidvalidity`

For remote operations, identify a message by:

```text
account + source remote mailbox + source UIDVALIDITY + source UID
```

Do not rely on bare `imap_uid`; UIDs are only meaningful inside one mailbox and UIDVALIDITY.

### Action Queue

Add a `mailbox_action_queue` table:

- `id`
- `account_id`
- `message_id`
- `action_type`: `MARK_READ`, `MARK_UNREAD`, `MOVE`, `DELETE`, `ARCHIVE`, `MARK_SPAM`,
  `APPEND_SENT`, `UPSERT_DRAFT`, `DELETE_DRAFT`
- `source_folder_id`
- `source_remote_name`
- `source_uidvalidity`
- `source_uid`
- `target_folder_id`
- `target_remote_name`
- `target_special_use`
- `payload_json` for future action-specific metadata
- `status`: `PENDING`, `APPLYING`, `SUCCEEDED`, `FAILED_RETRYABLE`, `FAILED_PERMANENT`, `CONFLICT`
- `execution_state`: `NOT_ATTEMPTED`, `ATTEMPTED_UNKNOWN`, `CONFIRMED_APPLIED`
- `resolution_type`: nullable `ROLLED_BACK`, `ABANDONED`, `ABANDONED_BY_RESET`, `DISMISSED`,
  `RESOLVED_REMOTE_MATCHED`
- `attempt_count`
- `next_attempt_at`
- `last_error`
- `created_at`
- `updated_at`
- `succeeded_at`
- `resolved_at`
- `dismissed_at`
- `abandoned_at`

Indexes:

- `(status, next_attempt_at)`
- `(account_id, status)`
- `(message_id, status)`
- `(account_id, source_remote_name, source_uidvalidity, source_uid, status)`
- `(resolution_type, resolved_at)`

## Local Action Semantics

### Mark Read / Mark Unread

Local:

- update `messages.read`
- enqueue action with source remote identity

Remote:

- `UID STORE <uid> +FLAGS.SILENT (\Seen)` for read
- `UID STORE <uid> -FLAGS.SILENT (\Seen)` for unread

These actions are idempotent enough to retry.

### Archive

Local:

- move message to account's configured Archive folder
- enqueue `ARCHIVE` as a remote move to the configured Archive mailbox
- preserve original source mailbox identity in the queue row

Remote:

- prefer `UID MOVE` when supported
- fallback to `UID COPY`, `UID STORE +FLAGS.SILENT (\Deleted)`, then targeted UID expunge when
  UIDPLUS is supported

### Mark Spam

Local:

- move message to account's configured Junk/Spam folder
- enqueue `MARK_SPAM` as a remote move to the configured Junk mailbox

Remote:

- same mechanics as Archive, with the Junk/Spam target mailbox

### Move

Local:

- move message to user-selected target folder
- enqueue `MOVE` with explicit target folder metadata
- reject cross-account target folders before local update

Remote:

- same mechanics as Archive, with the selected target mailbox

### Delete

Local:

- move message to Trash
- enqueue `DELETE` as a remote move to Trash

Permanent delete should be a separate future action, probably only shown inside Trash.

If the account has no Trash mapping, the account setup blocker should already have redirected the
user to folder mapping before the action is available.

### Sent

Sent message sync should use the configured Sent mailbox.

Initial direction:

- after SMTP delivery succeeds, append the sent message to the configured remote Sent mailbox
- update local state with remote APPENDUID when available
- if no Sent mapping exists when sending, block send or require configuration before the final send
  step

Sending is blocked without a configured Sent mapping.

### Drafts

Draft sync should use the configured Drafts mailbox.

Initial direction:

- composer access is blocked until Drafts mapping is configured
- after mappings exist, local draft autosave remains immediate
- remote Drafts sync is queued and debounced/coalesced so every keystroke does not create an IMAP
  update
- when a draft is sent or deleted locally, enqueue cleanup of the corresponding remote draft if one
  exists

Draft sync needs a dedicated design for remote draft identity because repeated saves may replace a
remote message rather than update it in place.

## Retry And Backoff

Add a background `MailboxActionSync` job similar in spirit to `MailSender`:

- scans `PENDING` and `FAILED_RETRYABLE` actions where `next_attempt_at <= now`
- claims rows by changing status to `APPLYING`
- applies one account's actions in bounded batches
- marks success, retry, permanent failure, or conflict

Backoff:

- exponential backoff with jitter
- suggested delays: 15s, 60s, 5m, 15m, 1h, 6h, then every 24h
- continue retryable failures indefinitely at the capped interval unless the user disables the
  account or manually marks the action abandoned
- do not silently drop actions
- surface an account warning once an action has failed enough times or old enough to require user
  attention

There does not appear to be an IMAP protocol rule that defines client retry schedules. Retry policy
is application behavior. The protocol-level guidance is to classify failures by command result and
connection/authentication state, then make queued work observable and manually recoverable.

Failure classification:

- retryable: connection failure, timeout, transient authentication/server errors
- permanent: missing configured target folder, unsupported command with no safe fallback
- conflict: source UID no longer exists or UIDVALIDITY changed before action applied

The UI should eventually expose pending/failed remote sync state, at least at account level and
possibly per-message.

Suggested user-visible thresholds:

- no warning for the first transient failure
- account warning after 3 failed attempts or 15 minutes, whichever comes first
- sync status view shows all pending, retrying, failed, and conflict actions
- rollback option appears for move-like actions after 6 failed attempts, 1 hour of retrying, a
  permanent failure, or a conflict, whichever comes first

Rollback should not happen automatically. The sync status view should offer it as an explicit user
action because the remote server might still apply the original move later.

## Preventing Sync From Undoing Local Actions

Inbound sync must become aware of pending outbound mailbox actions.

Rules:

- process due queued actions before normal folder sync where practical
- while an action is pending for `(account, source mailbox, uidvalidity, uid)`, inbound sync should
  not re-import that source UID into the old folder
- local move/delete/archive/spam should keep a queue row with the original source remote identity
  even though the local message row has moved
- if the remote action succeeds and returns a new target UID, update the local message's remote
  identity to the target mailbox UID
- if the server lacks COPYUID/MOVEUID response metadata, reconcile by message-id header or another
  stable message fingerprint where possible

This is the key invariant:

```text
Queued local user intent wins over naive remote folder listing until the queue item is resolved.
```

## Special Folder Mapping

IMAP servers differ in folder names and capabilities. Quicksand should not hardcode `Archive`,
`Spam`, `Trash`, `Sent`, or `Drafts` as remote mailbox names.

Discovery:

- during folder sync, collect remote mailbox names and special-use attributes where advertised
- automatically map unambiguous special-use folders
- default `INBOX` to the server's INBOX mailbox
- when no special-use attribute exists, use common names only as suggestions, not silent permanent
  configuration

Configuration:

- add account-level folder mapping settings:
  - Archive folder
  - Trash folder
  - Junk/Spam folder
  - Sent folder
  - Drafts folder
- expose a settings UI to change these mappings later
- when an action needs a mapping that has become missing after setup, block the action and show an
  SSR configuration flow instead of performing the action
- allow creating the remote folder only as an explicit user choice

Missing-mapping recovery flow:

This should only happen if a mapping becomes missing after setup, for example because the remote
folder was deleted or renamed.

1. User clicks Archive/Spam/Delete and the configured target mapping is now missing.
2. POST redirects to `/accounts/{accountId}/settings/folders?required=ARCHIVE`.
3. Page lists synced remote folders and any discovered special-use hints.
4. User selects a folder or creates one.
5. Save mapping with post/redirect.
6. User can retry the original action.

This avoids surprising remote folder creation and keeps configuration inspectable.

Account-opening flow:

1. Account page loads after folder sync has discovered remote folders.
2. If required mappings are missing or ambiguous, redirect to the folder mapping settings page.
3. The settings page explains which mappings are missing.
4. The folder mapping page lists synced remote folders and any discovered special-use hints.
5. The user selects existing folders or explicitly creates missing remote folders.
6. After all required mappings are configured, mailbox browsing becomes available.

This is stricter than action-level blocking, but it keeps server synchronization deterministic. The
mapping UI must therefore provide enough folder context that users can make the right choices
without browsing the mailbox first.

Later, this redirect can become a richer account page state that keeps the normal app chrome visible.

Redirect guard:

- do not redirect away from `/accounts/{accountId}/settings/folders`
- do not redirect away from `/accounts/{accountId}/sync`
- do not redirect static assets or non-account routes
- show connection progress/errors instead of redirecting before first successful folder discovery

Required mappings:

- Archive
- Trash
- Junk/Spam
- Sent
- Drafts

INBOX is discovered as the default selected mailbox and does not need user confirmation unless
discovery fails.

If the account has not connected successfully yet, show connection/setup progress or a connection
error instead of the mapping blocker. Folder mapping requires a known remote folder list.

### Remote Folder Creation

The folder mapping UI should support explicit remote folder creation:

- user chooses the special use being configured
- user enters or accepts a suggested mailbox name
- Quicksand creates the remote mailbox
- if the server supports creating with special-use attributes, request the appropriate attribute
- otherwise create a normal mailbox and store the local mapping
- refresh folder list after creation

Failure to create the folder should leave the mapping unset and show the server error.

The setup UI may offer suggested folder names for missing mappings, for example `Archive`, `Trash`,
`Junk`, `Sent`, and `Drafts`. Creating those suggested folders is not a separate automatic workflow;
it is part of the mapping step and should require explicit user confirmation.

## Conflict Handling

Potential conflicts:

- source UID vanished before queued action runs
- UIDVALIDITY changed
- target folder was deleted or renamed remotely
- action was already applied by another client
- message was moved remotely to another folder before local action sync

Recommended initial policy:

- read/unread on missing source UID: mark `CONFLICT`; do not change local state automatically
- move/delete/archive/spam on missing source UID: mark `CONFLICT`; keep local state but surface
  account sync warning
- target folder missing: mark `FAILED_PERMANENT` and ask user to remap folder
- UIDVALIDITY changed: mark `CONFLICT` and require a full folder resync before retry

Recommended robust policy:

1. Detect whether the action may already have been applied by another client.
2. Search likely target folders by Message-ID when available.
3. If one strong match is found in the intended target, mark the queued action as `SUCCEEDED` and
   update local remote identity.
4. If the message is found in a different folder, mark `CONFLICT_REMOTE_MOVED`.
5. If no match is found and the action was delete/move-like, keep local state and show a sync
   warning with options: retry, remap target, rollback local move, abandon after confirmation, or
   reset local mirror.
6. If multiple matches exist, mark `CONFLICT_AMBIGUOUS`.

Message-ID is not guaranteed to exist or be unique, so later iterations should add a stronger
fingerprint from stable headers plus normalized size/date/body excerpt.

Inbound sync should also recognize queued source UIDs before treating them as new messages. This
prevents the most common undo bug even before conflict resolution becomes sophisticated.

### Rollback

Rollback is for local user recovery after a move-like action keeps failing, not a blind attempt to
undo unknown remote state.

Recommended behavior:

1. If the queued remote action never reached the server, rollback only local state:
   - move the message back to its original local folder
   - mark the queued action `ROLLED_BACK`
2. If the action may have partially succeeded remotely, first run conflict discovery:
   - check whether the message exists in the intended target
   - check whether it still exists in the original source
3. If the message is confirmed in the intended target and the user requests rollback, enqueue a new
   remote move from target back to source and update local state optimistically.
4. If remote location is ambiguous, require manual resolution instead of issuing a reverse move.

The queue row should therefore record enough execution state to distinguish "not attempted",
"attempted but unknown result", and "confirmed applied".

This keeps rollback understandable: local-only rollback when safe, remote reverse move only when
Quicksand has enough evidence to identify the current remote source.

## UID MOVE vs COPY/Delete Fallback

`UID MOVE` is preferred because the server treats the move as one operation. It avoids exposing
intermediate COPY/Deleted states to other clients and avoids plain EXPUNGE accidentally removing
other messages that were already marked deleted in the same mailbox.

COPY/delete fallback is useful for servers without MOVE support, but it is riskier:

- it is not atomic
- a failure after COPY but before delete can leave duplicates
- a failure after delete but before UID mapping can lose local knowledge of the target UID
- plain EXPUNGE can affect unrelated deleted messages
- safe targeted expunge requires UIDPLUS `UID EXPUNGE`
- reliable target UID mapping depends on UIDPLUS `COPYUID`

Recommendation:

- first implementation should support `UID MOVE`
- fallback should be allowed only when UIDPLUS is available for targeted `UID EXPUNGE`
- without MOVE and without UIDPLUS, mark the action `FAILED_PERMANENT_UNSUPPORTED` and tell the user
  the server cannot safely perform remote moves from Quicksand yet
- revisit unsafe fallback only if there is a concrete server compatibility need

Unsafe fallback means doing `COPY`, marking the source message `\Deleted`, and then using broad
`EXPUNGE` without UIDPLUS targeted expunge and reliable COPYUID mapping. It can remove unrelated
messages that another client already marked deleted in the same folder, and it can leave Quicksand
unable to tell which UID the copied message received in the target folder. This should not be
offered in normal UI.

## UI Implications

Minimal first iteration:

- account settings page for folder mappings
- account-level warning when remote action sync has failures
- account-level setup blocker when required folder mappings are missing
- dedicated sync status/error view linked from the warning and settings
- pending/failed count in settings or sidebar

Later:

- per-message remote sync badges
- per-message retry/dismiss/remap controls
- conflict resolution page

## Sync Status View

Route suggestion:

- `/accounts/{accountId}/sync`

Related settings route:

- `/accounts/{accountId}/settings/folders`

Contents:

- current account remote connectivity status
- folder mapping status
- pending action count
- retrying action count
- failed action count
- conflict count
- table of queued actions with message subject, action, source folder, target folder, status,
  attempt count, next retry, and last error

Actions:

- retry now
- remap folder
- rollback local move, for eligible failed move-like actions
- abandon queued action, only after confirmation
- dismiss resolved failed/conflict rows, only after confirmation
- reset local mirror from server, only from an explicit recovery flow

Header warning behavior:

- show a compact warning in the global header when the current account has failed, conflicted, or
  long-retrying sync actions
- warning text should be short, for example `Sync needs attention`
- warning links to `/accounts/{accountId}/sync`
- do not show per-message warning badges in the first version
- rollback is not available directly from the header warning

Sync status view detail level:

- user-facing summary at the top
- technical details available in the action table, including IMAP mailbox, UID, UIDVALIDITY,
  command/action type, last error, attempt count, and next retry time
- destructive actions such as rollback or abandon require confirmation

The first version can be account-level only. Per-message badges can come later.

## Queue Retention

Successful queue rows are useful for debugging sync behavior, support requests, and diagnosing
conflicts after the fact. They are not required forever.

Recommendation:

- retain successful rows for 30 days
- retain resolved failed, conflict, abandoned, rolled back, and dismissed rows for 90 days
- unresolved failed and conflict rows remain until resolved, abandoned, or reset
- manual dismissal should be available only for resolved rows from the sync status view and should
  record that the user dismissed the row
- store enough detail for diagnosis, but avoid storing full message bodies in the queue
- add periodic cleanup once the queue exists

## Unresolved Failures And Recovery

Unresolved rows are queue entries where Quicksand cannot yet prove that local state and remote state
agree. Examples:

- retryable failures that have not succeeded yet
- permanent failures such as unsupported server capabilities
- target folder missing or remapped remotely
- source UID missing
- UIDVALIDITY changed
- ambiguous Message-ID/fingerprint matches
- action may have partially succeeded remotely but Quicksand cannot identify the resulting message

These can get the user into trouble if hidden too casually. Dismissing an unresolved row would remove
the visible warning while local state may still diverge from the server. A later inbound sync could
then appear to resurrect, duplicate, or lose track of messages.

Policy:

- do not offer simple dismissal for unresolved failed/conflict rows
- allow explicit actions instead: retry, remap, rollback, abandon, or reset local mirror
- resolved rows may be dismissed from the sync status view
- abandoning an unresolved action must be confirm-only and should state that local and remote state
  may diverge until the next resync
- fine-grained abandon should be offered only when the queued row has enough source/target metadata
  to explain what local intent is being discarded

### Reset Local Mirror From Server

Quicksand should provide a recovery action to return to a known server-derived state.

Suggested behavior:

1. User opens `/accounts/{accountId}/sync`.
2. User chooses `Reset local mirror from server`.
3. Confirmation explains that pending local mailbox actions for the account will be abandoned and
   local mirrored messages/folders will be rebuilt from IMAP.
4. Quicksand marks unresolved queue rows as `ABANDONED_BY_RESET`.
5. Quicksand clears local mirrored message/folder state for that account, preserving local drafts
   and outbound records.
6. Quicksand performs a full folder discovery and message sync.
7. Folder mappings are preserved when possible, but mappings to missing remote folders become
   `MISSING` and send the user back through folder setup.

This is a last-resort recovery tool, not a routine sync operation. It should be account-scoped
initially. A narrower per-folder reset can be added later.

## Implementation Plan

1. Add schema support for folder remote metadata, account folder mappings, and mailbox action queue.
2. Teach folder sync to capture UIDVALIDITY and special-use metadata.
3. Add account folder mapping repository/service/settings UI, including explicit remote folder
   creation.
4. Add account setup blocker when required mappings are missing.
5. Change local actions to block when required mappings are missing.
6. Change local actions to enqueue queue rows transactionally.
7. Teach inbound sync to ignore source UIDs covered by pending move/delete/archive/spam actions.
8. Add sync status/error view.
9. Implement remote read/unread sync.
10. Implement remote move to configured folders using `UID MOVE`.
11. Add safe COPY/delete/UID-expunge fallback only where UIDPLUS support makes it safe.
12. Add Sent append sync.
13. Add Drafts sync with debounced/coalesced queued updates.
14. Add integration tests with GreenMail for read/unread, move, delete-as-trash, retry, folder
    mapping, remote folder creation, pending-action inbound sync suppression, Sent, and Drafts.

## Open Questions

None currently.
