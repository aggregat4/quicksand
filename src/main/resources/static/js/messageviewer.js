function initMessageViewer() {
    const emailId = document.body.dataset.emailId
    if (!emailId) {
        return
    }

    document.querySelector('button[name="reply_email"]')?.addEventListener('click', (event) => {
        event.preventDefault()
        replyToEmail(emailId)
    })
    document.querySelector('button[name="forward_email"]')?.addEventListener('click', (event) => {
        event.preventDefault()
        forwardEmail(emailId)
    })
}

function replyToEmail(emailId) {
    window.parent.postMessage({ type: 'reply-to-email', emailId }, '*')
}

function forwardEmail(emailId) {
    window.parent.postMessage({ type: 'forward-email', emailId }, '*')
}

if (document.readyState !== 'loading') {
    initMessageViewer()
} else {
    document.addEventListener('DOMContentLoaded', initMessageViewer)
}
