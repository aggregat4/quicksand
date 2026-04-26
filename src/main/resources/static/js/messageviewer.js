// if we are already loaded then DOMContentLoaded will not fire again, just init
if (document.readyState !== 'loading') {
    init()
} else {
    document.addEventListener('DOMContentLoaded', init)
}

function init() {
}

function replyToEmail(emailId) {
    window.parent.postMessage({ type: 'reply-to-email', emailId }, "*")
}

function forwardEmail(emailId) {
    window.parent.postMessage({ type: 'forward-email', emailId }, "*")
}
