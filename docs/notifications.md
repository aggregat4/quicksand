# Mailbox notifications

## Scope

Notifications tell the user about new and unread mail without making the browser a mailbox-state
owner. IMAP synchronization updates the SQLite mirror; notification services query that mirror and
render HTML fragments.

Sync failures are separate from arrival notifications. Failed mailbox actions appear through the
sync-attention status described in [`mailbox-sync-architecture.md`](mailbox-sync-architecture.md).

## Server model

Each mirrored folder stores `last_viewed_epoch_s`.

| Value | Definition |
|-------|------------|
| Unread count | Mirrored messages in the folder where `read = 0`; Sent is displayed as zero |
| New in INBOX | Messages received after INBOX's stored view cursor |
| View cursor | Maximum mirrored receive timestamp when the current folder is included in a notification refresh, or current time when empty |

`NotificationService` calculates these values. Full account pages render the same summary used by
the refresh fragment, so initial HTML and enhanced updates have consistent semantics. The mailbox
entry calls the fragment immediately after boot, which records the current folder as viewed.

## Refresh path

`MailFetcher` publishes a `mailbox-updated` event after an account sync. Mailbox pages subscribe to
`GET /accounts/{id}/events` with `EventSource`; an event causes a fetch of
`GET /accounts/{id}/notifications?folderId=…`. A 60-second poll backs up a connected SSE stream,
and a 15-second poll is used when SSE is unavailable. The client reconnects SSE after failure.

The notification response is a Pebble-rendered HTML fragment containing only:

- the INBOX notification strip; and
- unread badges keyed by folder ID.

The browser replaces those nodes in place. It does not insert message rows, patch message read
state, or adjust pagination. Following the strip performs a normal navigation and obtains a fresh
server-rendered mailbox page. Polling pauses while the composer dialog is open.

## Desktop notifications

Desktop notification permission is managed by the notification settings page. The preference is
stored as `localStorage['quicksand.desktopNotifications']`. When permission is granted and the page
is hidden, the client shows one summary notification only when the new-INBOX count increases.

There is no service worker or background push channel. Desktop notification behavior therefore
depends on an open mailbox page and its SSE/poll loop.
