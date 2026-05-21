(function () {
    const POLL_MS = 15000
    const DESKTOP_PREF_KEY = 'quicksand.desktopNotifications'

    function currentFolderId() {
        const main = document.querySelector('main')
        if (!main) {
            return ''
        }
        return main.dataset.currentNamedFolderId || ''
    }

    function composerOpen() {
        return document.getElementById('newmail-composer-dialog')?.open === true
    }

    function messageViewerOpen() {
        return document.getElementById('messagepreview')?.open === true
    }

    function listCursorParams() {
        const messagelist = document.getElementById('messagelist')
        if (!messagelist || messagelist.dataset.liveUpdates !== 'true') {
            return ''
        }
        const firstHeader = messagelist.querySelector('.emailheader')
        const cursorId = firstHeader?.dataset.messageId || messagelist.dataset.listCursorId || '0'
        const cursorReceived =
            firstHeader?.dataset.receivedEpoch || messagelist.dataset.listCursorReceived || '0'
        return `&listCursorMessageId=${encodeURIComponent(cursorId)}&listCursorReceived=${encodeURIComponent(cursorReceived)}`
    }

    function applyFolderBadges(payload) {
        payload.querySelectorAll('.folder-unread-badge[data-folder-id]').forEach((incomingBadge) => {
            const folderId = incomingBadge.dataset.folderId
            const link = document.querySelector(`.folder-sidebar-link[data-folder-id="${folderId}"]`)
            if (!link) {
                return
            }
            let badge = link.querySelector('.folder-unread-badge')
            const unreadCount = parseInt(incomingBadge.textContent, 10) || 0
            if (unreadCount <= 0) {
                if (badge) {
                    badge.remove()
                }
                return
            }
            if (!badge) {
                badge = document.createElement('span')
                badge.className = 'folder-unread-badge'
                badge.dataset.folderId = folderId
                link.appendChild(badge)
            }
            badge.textContent = String(unreadCount)
            badge.hidden = false
        })
    }

    function applyMessageListUpdates(payload) {
        const messagelist = document.getElementById('messagelist')
        if (!messagelist || messagelist.dataset.liveUpdates !== 'true') {
            return
        }

        const updates = payload.querySelector('#messagelist-updates')
        if (!updates) {
            return
        }

        const incomingHeaders = [...updates.querySelectorAll('.emailheader')]
        if (incomingHeaders.length === 0) {
            return
        }

        incomingHeaders.reverse().forEach((header) => {
            if (document.getElementById(header.id)) {
                return
            }
            messagelist.insertBefore(header.cloneNode(true), messagelist.firstChild)
        })

        const firstHeader = messagelist.querySelector('.emailheader')
        if (firstHeader) {
            messagelist.dataset.listCursorId = firstHeader.dataset.messageId
            messagelist.dataset.listCursorReceived = firstHeader.dataset.receivedEpoch
        }

        const status = document.getElementById('pagination-status')
        if (status && !messageViewerOpen()) {
            const match = status.textContent.match(/^(\d+)\s+of\s+(\d+)/)
            if (match) {
                const visible = parseInt(match[1], 10) + incomingHeaders.length
                const total = parseInt(match[2], 10) + incomingHeaders.length
                status.textContent = status.textContent.replace(
                    /^(\d+)\s+of\s+(\d+)/,
                    `${visible} of ${total}`
                )
            }
        }
    }

    function applyPayload(doc) {
        const payload = doc.getElementById('notifications-payload')
        if (!payload) {
            return null
        }

        const incomingStrip = payload.querySelector('#notification-strip')
        const existingStrip = document.getElementById('notification-strip')
        if (incomingStrip && existingStrip) {
            existingStrip.replaceWith(incomingStrip.cloneNode(true))
        }

        applyFolderBadges(payload)
        applyMessageListUpdates(payload)

        const strip = document.getElementById('notification-strip')
        if (strip) {
            return parseInt(strip.dataset.inboxNew || '0', 10)
        }
        return 0
    }

    function maybeDesktopNotify(inboxNewCount) {
        if (localStorage.getItem(DESKTOP_PREF_KEY) !== 'true') {
            return
        }
        if (!('Notification' in window) || Notification.permission !== 'granted') {
            return
        }
        if (!document.hidden || inboxNewCount <= 0) {
            return
        }
        const lastCount = parseInt(sessionStorage.getItem('quicksand.lastInboxNew') || '0', 10)
        if (inboxNewCount <= lastCount) {
            return
        }
        const strip = document.getElementById('notification-strip')
        const body = strip?.textContent?.trim() || `${inboxNewCount} new in Inbox`
        new Notification('Quicksand', { body })
    }

    function poll(accountId) {
        if (composerOpen()) {
            return
        }
        const folderId = currentFolderId()
        let url = folderId
            ? `/accounts/${accountId}/notifications?folderId=${encodeURIComponent(folderId)}`
            : `/accounts/${accountId}/notifications`
        url += listCursorParams()
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

    function init() {
        const accountId = window.quicksand?.currentAccountId
        if (!accountId) {
            return
        }
        poll(accountId)
        window.setInterval(() => poll(accountId), POLL_MS)
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init)
    } else {
        init()
    }
})()
