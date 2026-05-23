(function () {
    const POLL_MS = 15000
    const BACKSTOP_POLL_MS = 60000
    const DESKTOP_PREF_KEY = 'quicksand.desktopNotifications'

    let eventSource = null
    let sseConnected = false
    let pollTimer = null

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

    function visibleMessageIdParams() {
        const messagelist = document.getElementById('messagelist')
        if (!messagelist || messagelist.dataset.liveUpdates !== 'true') {
            return ''
        }
        const ids = [...messagelist.querySelectorAll('.emailheader')]
            .map((header) => header.dataset.messageId)
            .filter(Boolean)
        if (ids.length === 0) {
            return ''
        }
        return `&visibleMessageIds=${encodeURIComponent(ids.join(','))}`
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

    function applyReadStateUpdates(payload) {
        payload.querySelectorAll('.read-state-update[data-message-id]').forEach((updateNode) => {
            const messageId = updateNode.dataset.messageId
            const row = document.getElementById(`email${messageId}`)
            if (!row) {
                return
            }
            const read = updateNode.dataset.read === 'true'
            row.classList.toggle('read', read)
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

        ;[...updates.children].forEach((node) => {
            if (node.classList.contains('emailgroup')) {
                insertGroupHeaderIfNeeded(messagelist, node)
            } else if (node.classList.contains('emailheader')) {
                insertMessageIntoTopGroup(messagelist, node)
            }
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

    function insertGroupHeaderIfNeeded(messagelist, groupNode) {
        const label = groupNode.textContent.trim()
        const firstGroup = messagelist.querySelector('.emailgroup')
        if (firstGroup && firstGroup.textContent.trim() === label) {
            return
        }
        messagelist.insertBefore(groupNode.cloneNode(true), messagelist.firstChild)
    }

    function insertMessageIntoTopGroup(messagelist, headerNode) {
        if (document.getElementById(headerNode.id)) {
            return
        }
        const clone = headerNode.cloneNode(true)
        const firstGroup = messagelist.querySelector('.emailgroup')
        if (!firstGroup) {
            messagelist.insertBefore(clone, messagelist.firstChild)
            return
        }
        let insertBefore = null
        let node = firstGroup.nextElementSibling
        while (node) {
            if (node.classList.contains('emailheader')) {
                insertBefore = node
                break
            }
            if (node.classList.contains('emailgroup')) {
                break
            }
            node = node.nextElementSibling
        }
        if (insertBefore) {
            messagelist.insertBefore(clone, insertBefore)
        } else {
            firstGroup.insertAdjacentElement('afterend', clone)
        }
    }

    function applyNotificationStrip(incomingStrip) {
        const existingStrip = document.getElementById('notification-strip')
        if (!incomingStrip || !existingStrip) {
            return
        }
        existingStrip.replaceWith(incomingStrip.cloneNode(true))
    }

    function applyPayload(doc) {
        const payload = doc.getElementById('notifications-payload')
        if (!payload) {
            return null
        }

        const incomingStrip = payload.querySelector('#notification-strip')
        applyNotificationStrip(incomingStrip)

        applyFolderBadges(payload)
        applyReadStateUpdates(payload)
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
        url += visibleMessageIdParams()
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

    function init() {
        const accountId = window.quicksand?.currentAccountId
        if (!accountId) {
            return
        }
        poll(accountId)
        connectEvents(accountId)
        schedulePoll(accountId)
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init)
    } else {
        init()
    }
})()
