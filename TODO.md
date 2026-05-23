# TODO

## Verify on real IMAP

After `mvn -DskipTests package` and `./scripts/start-real-server.sh` (see [`docs/account-credentials.md`](docs/account-credentials.md) for the credential key):

- [ ] Folder mappings persist: `SELECT special_use, folder_id, remote_name, status FROM account_folder_mappings WHERE account_id = 1;` — required roles `USER_CONFIRMED` with non-null `folder_id` / `remote_name` (re-save mappings once after upgrade if needed)
- [ ] Incoming attachments (docx/pdf/html) visible in viewer after sync
- [ ] SSE `/accounts/1/events` — no `Broken pipe` spam on tab close
- [ ] `npm run test:e2e`

---

## §4 Runtime, schema, and storage hardening

- [ ] SQLite/Hikari pool tuning documentation
- [ ] Per-account folder uniqueness cleanup (`folders.name` legacy global unique)
- [ ] Folder-scoped message UID identity enforcement
- [ ] Broader `NOT NULL` / FK cascades
- [ ] Schema/constraint regression tests

---

## Notifications

Spec: [`specs/new-mail-notifications.md`](specs/new-mail-notifications.md)

- Playwright coverage for SSE wake-up
- Desktop notification opt-in UI

---

## Accounts

- Account management UI
- OAuth2 sign-in (e.g. Gmail) instead of config-file passwords
