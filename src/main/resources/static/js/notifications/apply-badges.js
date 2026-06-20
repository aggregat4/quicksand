export function applyFolderBadges(payload) {
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
