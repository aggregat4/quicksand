import { isDraftsPage } from 'quicksand/lib/page-context.js'
import {
    closeEmailComposerDialog,
    createEmailAndShowComposer
} from 'quicksand/account/composer-host.js'

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
            await createEmailAndShowComposer(`replyEmail=${event.data.emailId}`)
        } else if (event.data.type === 'forward-email') {
            await createEmailAndShowComposer(`forwardEmail=${event.data.emailId}`)
        } else if (event.data.type === 'close-email-composer') {
            await closeEmailComposerDialog()
        }
    })
}
