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

if (document.readyState !== 'loading') {
    initSyncStatusPage()
} else {
    document.addEventListener('DOMContentLoaded', initSyncStatusPage)
}
