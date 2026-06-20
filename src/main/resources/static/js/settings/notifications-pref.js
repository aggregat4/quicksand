import { onceDOMReady } from 'quicksand/lib/dom-ready.js'

const DESKTOP_PREF_KEY = 'quicksand.desktopNotifications'

function initNotificationSettings() {
    const checkbox = document.getElementById('desktop-notifications-enabled')
    const status = document.getElementById('desktop-notifications-status')
    if (!checkbox || !status) {
        return
    }

    function setStatus(message) {
        status.textContent = message
    }

    function syncCheckbox() {
        const supported = 'Notification' in window
        const granted = supported && Notification.permission === 'granted'
        const enabled = localStorage.getItem(DESKTOP_PREF_KEY) === 'true'

        checkbox.disabled = !supported
        checkbox.checked = enabled && granted

        if (!supported) {
            setStatus('Desktop notifications are not supported in this browser.')
            return
        }
        if (Notification.permission === 'denied') {
            setStatus('Browser blocked desktop notifications. Enable them in browser settings and try again.')
            return
        }
        if (checkbox.checked) {
            setStatus('Desktop notifications are enabled for this browser.')
            return
        }
        setStatus('Show a summary when new mail arrives while this tab is in the background.')
    }

    checkbox.addEventListener('change', async () => {
        if (!checkbox.checked) {
            localStorage.removeItem(DESKTOP_PREF_KEY)
            syncCheckbox()
            return
        }

        const permission = await Notification.requestPermission()
        if (permission === 'granted') {
            localStorage.setItem(DESKTOP_PREF_KEY, 'true')
        } else {
            localStorage.removeItem(DESKTOP_PREF_KEY)
        }
        syncCheckbox()
    })

    syncCheckbox()
}

onceDOMReady(initNotificationSettings)
