import { currentFolderId } from 'quicksand/lib/page-context.js'
import { applyNotificationStrip } from 'quicksand/notifications/apply-strip.js'
import { applyFolderBadges } from 'quicksand/notifications/apply-badges.js'
import { maybeDesktopNotify } from 'quicksand/notifications/desktop-notify.js'

const POLL_MS = 15000
const BACKSTOP_POLL_MS = 60000

let eventSource = null
let sseConnected = false
let pollTimer = null

function composerOpen() {
    return document.getElementById('newmail-composer-dialog')?.open === true
}

function applyPayload(doc) {
    const payload = doc.getElementById('notifications-payload')
    if (!payload) {
        return null
    }

    applyNotificationStrip(payload.querySelector('#notification-strip'))
    applyFolderBadges(payload)

    const strip = document.getElementById('notification-strip')
    if (strip) {
        return parseInt(strip.dataset.inboxNew || '0', 10)
    }
    return 0
}

function poll(accountId) {
    if (composerOpen()) {
        return
    }
    const folderId = currentFolderId()
    const url = folderId
        ? `/accounts/${accountId}/notifications?folderId=${encodeURIComponent(folderId)}`
        : `/accounts/${accountId}/notifications`
    fetch(url, { credentials: 'same-origin' })
        .then((response) => response.text())
        .then((html) => {
            const doc = new DOMParser().parseFromString(html, 'text/html')
            const inboxNewCount = applyPayload(doc)
            if (inboxNewCount != null) {
                maybeDesktopNotify(inboxNewCount)
                sessionStorage.setItem('quicksand.lastInboxNew', String(inboxNewCount))
            }
        })
        .catch(() => {})
}

function schedulePoll(accountId) {
    if (pollTimer != null) {
        window.clearInterval(pollTimer)
    }
    pollTimer = window.setInterval(
        () => poll(accountId),
        sseConnected ? BACKSTOP_POLL_MS : POLL_MS
    )
}

function connectEvents(accountId) {
    if (!window.EventSource) {
        return
    }
    if (eventSource) {
        eventSource.close()
        eventSource = null
    }
    eventSource = new EventSource(`/accounts/${accountId}/events`)
    eventSource.addEventListener('mailbox-updated', () => poll(accountId))
    eventSource.onopen = () => {
        sseConnected = true
        schedulePoll(accountId)
    }
    eventSource.onerror = () => {
        sseConnected = false
        eventSource?.close()
        eventSource = null
        schedulePoll(accountId)
        window.setTimeout(() => connectEvents(accountId), 5000)
    }
}

export function initNotifications(accountId) {
    poll(accountId)
    connectEvents(accountId)
    schedulePoll(accountId)
}
