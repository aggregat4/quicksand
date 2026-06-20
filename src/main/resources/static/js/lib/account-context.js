export function initRuntime() {
    window.quicksand = window.quicksand || {}
    const accountId = document.body.dataset.accountId
    if (accountId) {
        window.quicksand.currentAccountId = Number.parseInt(accountId, 10)
    }
}

export function getAccountId() {
    const accountId = window.quicksand?.currentAccountId
    return Number.isFinite(accountId) ? accountId : null
}
