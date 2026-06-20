import { getAccountId } from 'quicksand/lib/account-context.js'
import { isDraftsPage } from 'quicksand/lib/page-context.js'

export async function createEmailAndShowComposer(urlParams) {
    const accountId = getAccountId()
    const queryString = urlParams ? `${urlParams}&redirect=false` : 'redirect=false'
    const response = await fetch(
        `/accounts/${accountId}/emails?${queryString}`,
        { method: 'POST' })
    openComposerDialog(await response.text())
}

export async function onCloseEmailComposerDialog(event) {
    event?.preventDefault()
    await closeEmailComposerDialog()
    return false
}

export async function closeEmailComposerDialog() {
    const composer = document.getElementById('newmail-composer-dialog')
    const composerFrame = document.getElementById('newmail-composer-frame')
    composer.classList.remove('minimized')
    await requestComposerDraftSave()
    composer.close()
    composerFrame.src = 'about:blank'
    if (isDraftsPage()) {
        const preview = await import('quicksand/account/message-preview.js')
        preview.markAllEmailHeadersInactive()
        const url = new URL(window.location.href)
        url.searchParams.delete('selectedEmailId')
        history.pushState(null, '', url.toString())
    }
}

export function openComposerDialog(src) {
    const composerFrame = document.getElementById('newmail-composer-frame')
    composerFrame.src = 'about:blank'
    composerFrame.src = src
    document.getElementById('newmail-composer-dialog').show()
}

function requestComposerDraftSave() {
    const composerFrame = document.getElementById('newmail-composer-frame')
    if (!composerFrame?.contentWindow) {
        return Promise.resolve()
    }
    const composerPath = composerFrame.contentWindow.location?.pathname ?? ''
    if (!composerPath.endsWith('/composer')) {
        return Promise.resolve()
    }
    return new Promise(resolve => {
        let settled = false
        const timeoutId = setTimeout(() => {
            finish()
        }, 1500)

        function onMessage(messageEvent) {
            if (messageEvent.source !== composerFrame.contentWindow) {
                return
            }
            if (messageEvent.data?.type !== 'draft-saved') {
                return
            }
            finish()
        }

        function finish() {
            if (settled) {
                return
            }
            settled = true
            clearTimeout(timeoutId)
            window.removeEventListener('message', onMessage)
            resolve()
        }

        window.addEventListener('message', onMessage)
        composerFrame.contentWindow.postMessage({ type: 'save-draft' }, '*')
    })
}
