export function applyNotificationStrip(incomingStrip) {
    const existingStrip = document.getElementById('notification-strip')
    if (!incomingStrip || !existingStrip) {
        return
    }
    existingStrip.replaceWith(incomingStrip.cloneNode(true))
}
