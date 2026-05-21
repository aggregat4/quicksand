# New-mail notifications

## Status

Implemented plan for Quicksand's SSR + local SQLite mirror architecture.

## Goal

Tell the user about new and unread mail without turning Quicksand into a SPA or querying IMAP from the browser.

## Principles

- **SQLite mirror is truth** — counts and “new since view” derive from `messages` + folder view cursors.
- **Sync discovers mail** — `MailFetcher` / IDLE update the mirror; notifications read the mirror.
- **SSR first** — badges and strips render in Pebble; JavaScript only refreshes HTML fragments.
- **Separate from sync failures** — `MailboxSyncStatus` (action queue) stays distinct from arrival cues.

## Server model

Per folder (`folders.last_viewed_epoch_s`):

| Concept | Definition |
|---------|------------|
| **Unread count** | `COUNT(*)` where `read = 0` (sidebar badge) |
| **New since last view** | `received_date_epoch_s > COALESCE(last_viewed_epoch_s, 0)` |
| **View cursor update** | On folder page render: set `last_viewed_epoch_s` to `MAX(received_date_epoch_s)` in folder, or `now` if empty |

INBOX drives the optional header strip (“N new in Inbox”).

## API

| Route | Response |
|-------|----------|
| `GET /accounts/{id}/notifications?folderId=&listCursorReceived=&listCursorMessageId=` | HTML fragment: strip, folder badges, optional new message rows for live list updates |

Rendered by `templates/partials/notifications.peb`.

## In-app UX

1. **Sidebar badges** — unread count next to each mirrored folder when `read = 0` (not Outbox/Drafts/Sent).
2. **Header strip** — quiet link when INBOX has new mail since last view and user is not already on Inbox.
3. **Poll** — `notifications.js` fetches the fragment every 15s and patches badges/strip; when viewing the first page of a folder (default sort), prepends new rows to `#messagelist` without reloading the open viewer.

Suppress strip when composer dialog is open (`#newmail-composer-dialog[open]`).

## Desktop (optional)

- Opt-in via `localStorage` key `quicksand.desktopNotifications=true` after `Notification.requestPermission()`.
- Fire only when `document.hidden` and INBOX new-since-view count increases between polls.
- One summary: “3 new messages in Inbox” with click → focus tab (navigate via link in strip).

No Service Worker in v1.

## Phases

| Phase | Scope | Status |
|-------|--------|--------|
| A | View cursors + unread sidebar badges on full page load | **Done** |
| B | Notification fragment + `notifications.js` poll + header strip | **Done** |
| C | Desktop opt-in on poll | **Done (minimal)** |

## Testing

- `NotificationServiceTest` — unread counts, new-since-view, mark viewed
- `DbFolderRepositoryTest` — `markFolderViewed` persistence
- Manual: open Inbox, sync new mail in another tab, strip appears on poll

## Non-goals (v1)

- WebSocket / SSE push channel
- JSON inbox API as primary UI
- Per-message desktop spam during bulk initial sync (cursor starts unset; first folder visit sets baseline)
