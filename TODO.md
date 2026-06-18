# TODO

## Verify on real IMAP

After `mvn -DskipTests package` and `./scripts/start-real-server.sh` (see [`docs/account-credentials.md`](docs/account-credentials.md) for the credential key). For local demo mail, `./scripts/start-test-server.sh` also runs mailbox action sync.

- [ ] Folder mappings persist: `SELECT special_use, folder_id, remote_name, status FROM account_folder_mappings WHERE account_id = 1;` — required roles `USER_CONFIRMED` with non-null `folder_id` / `remote_name` (re-save mappings once after upgrade if needed)
- [ ] Header **Settings** opens `/accounts/{id}/settings` and links to folder mappings and sync status
- [ ] Bulk mark-as-read (e.g. 50–100 messages): sync status queue drains to `SUCCEEDED`; messages stay read locally across mail-fetch cycles; IMAP shows `\Seen`
- [ ] If mark-read actions stay `PENDING` / `NOT_ATTEMPTED`, confirm startup log does not say mailbox action sync is disabled (`mailbox_action_sync.enabled` / demo mode)
- [ ] Opening many messages in quick succession does not stall on Hikari pool timeouts
- [ ] Incoming attachments (docx/pdf/html) visible in viewer after sync
- [ ] SSE `/accounts/1/events` — no `Broken pipe` spam on tab close
- [ ] `npm run test:e2e`

Unit/integration coverage already exists for batched mark-read outbound sync, inbound read-state suppression while actions are pending, and settings routes — the items above are still worth checking against a live account.

---

## Notifications

Spec: [`specs/new-mail-notifications.md`](specs/new-mail-notifications.md)

- [x] Playwright coverage for SSE wake-up (`e2e/notifications.spec.js`)
- [x] Desktop notification opt-in UI (`/accounts/{id}/settings`)

---

## Accounts

- Account management UI
