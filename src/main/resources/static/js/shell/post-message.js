import { isDraftsPage } from 'quicksand/lib/page-context.js'

function loadComposerDialog() {
    return import('quicksand/shell/composer-dialog.js')
}

export function initPostMessageHub() {
    window.addEventListener('message', async (event) => {
        if (event.data.type === 'email-queued') {
            const composer = document.getElementById('newmail-composer-dialog')
            if (composer) {
                composer.classList.add('minimized')
                setTimeout(() => {
                    composer.close()
                    composer.classList.remove('minimized')
                    if (isDraftsPage()) {
                        window.location.href = window.location.pathname
                    }
                }, 7000)
            }
        } else if (event.data.type === 'reply-to-email') {
            const composer = await loadComposerDialog()
            await composer.createEmailAndShowComposer(`replyEmail=${event.data.emailId}`)
        } else if (event.data.type === 'forward-email') {
            const composer = await loadComposerDialog()
            await composer.createEmailAndShowComposer(`forwardEmail=${event.data.emailId}`)
        } else if (event.data.type === 'close-email-composer') {
            const composer = await loadComposerDialog()
            await composer.closeEmailComposerDialog()
        }
    })
}
