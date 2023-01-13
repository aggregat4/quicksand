// if we are already loaded then DOMContentLoaded will not fire again, just init
if (document.readyState !== 'loading') {
    init()
} else {
    document.addEventListener('DOMContentLoaded', init)
}

function init() {
    const messagepreview = document.getElementById("emailbodyframe")
    if (messagepreview) {
        const iframeDoc = messagepreview.contentDocument || messagepreview.contentWindow.document
        if (iframeDoc.readyState === 'complete') {
            setHeightToContent(messagepreview)
        } else {
            messagepreview.addEventListener("load", () => {
                setHeightToContent(messagepreview)
            })
        }
    }
}

function setHeightToContent(iframe) {
    iframe.height = Math.max( iframe.contentWindow.document.body.scrollHeight, iframe.contentWindow.document.body.offsetHeight, iframe.contentWindow.document.documentElement.clientHeight, iframe.contentWindow.document.documentElement.scrollHeight, iframe.contentWindow.document.documentElement.offsetHeight ) + 'px';
}

function replyToEmail(emailId) {
    window.parent.postMessage({ type: 'reply-to-email', emailId }, "*")
}

function forwardEmail(emailId) {
    window.parent.postMessage({ type: 'forward-email', emailId }, "*")
}
