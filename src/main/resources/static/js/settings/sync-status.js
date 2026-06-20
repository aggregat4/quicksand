import { onceDOMReady } from 'quicksand/lib/dom-ready.js'

function initSyncStatusPage() {
    document.querySelectorAll('.sync-confirm-trigger').forEach((button) => {
        button.addEventListener('click', () => {
            document.getElementById(button.dataset.dialog)?.showModal()
        })
    })
    document.querySelectorAll('.dialogcloser-button').forEach((button) => {
        button.addEventListener('click', () => {
            button.closest('dialog')?.close()
        })
    })
}

onceDOMReady(initSyncStatusPage)
