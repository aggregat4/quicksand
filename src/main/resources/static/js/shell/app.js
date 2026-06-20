import { onceDOMReady } from 'quicksand/lib/dom-ready.js'
import { initRuntime, getAccountId } from 'quicksand/lib/account-context.js'
import { initHeader } from 'quicksand/shell/header.js'
import { initBackForwardCache } from 'quicksand/shell/bfcache.js'
import { initPostMessageHub } from 'quicksand/shell/post-message.js'

function boot() {
    initRuntime()
    initHeader()
    initBackForwardCache()
    initPostMessageHub()
    if (document.getElementById('messagelist')) {
        import('quicksand/account/index.js').then((module) => module.initAccountPage())
        const accountId = getAccountId()
        if (accountId != null) {
            import('quicksand/notifications/index.js').then((module) => module.initNotifications(accountId))
        }
    }
}

onceDOMReady(boot)
