const DESKTOP_PREF_KEY = 'quicksand.desktopNotifications'

export function maybeDesktopNotify(inboxNewCount) {
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
