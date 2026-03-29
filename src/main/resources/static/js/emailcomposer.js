if (document.readyState !== 'loading') {
    initEmailComposer()
} else {
    document.addEventListener('DOMContentLoaded', initEmailComposer)
}

function initEmailComposer() {
    const visibleForm = document.getElementById('save-email-form')
    const autosaveForm = document.getElementById('autosave-email-form')
    const autosaveFrame = document.getElementById('draftsaveframe')
    if (!visibleForm || !autosaveForm || !autosaveFrame) {
        return
    }

    const draftFieldNames = ['email-to', 'email-cc', 'email-bcc', 'email-subject', 'email-body']
    let autoSaveTimer = null
    let saveChain = Promise.resolve()
    let lastSavedSnapshot = serializeDraftFields(draftFieldNames)

    for (const fieldName of draftFieldNames) {
        const field = document.getElementById(fieldName)
        if (!field) {
            continue
        }
        field.addEventListener('input', () => debounce(queueDraftSave, 300))
        field.addEventListener('change', () => debounce(queueDraftSave, 300))
    }

    window.addEventListener('message', event => {
        if (event.data?.type !== 'save-draft') {
            return
        }
        clearTimeout(autoSaveTimer)
        queueDraftSave()
        saveChain.finally(() => {
            window.parent.postMessage({ type: 'draft-saved', draftId: document.body.dataset.draftId }, '*')
        })
    })

    function queueDraftSave() {
        saveChain = saveChain
            .then(() => submitDraftSave())
            .catch(error => {
                console.error('Draft save failed', error)
            })
    }

    function submitDraftSave() {
        const snapshot = serializeDraftFields(draftFieldNames)
        if (snapshot === lastSavedSnapshot) {
            return Promise.resolve()
        }
        syncAutosaveFields(draftFieldNames)
        return submitAutosaveForm()
            .then(() => {
                lastSavedSnapshot = snapshot
            })
    }

    function submitAutosaveForm() {
        return new Promise((resolve, reject) => {
            function onLoad() {
                const saveStatus = autosaveFrame.contentDocument?.body?.dataset?.saveStatus
                if (saveStatus === 'ok') {
                    resolve()
                } else {
                    reject(new Error('Draft autosave did not report success'))
                }
            }

            autosaveFrame.addEventListener('load', onLoad, { once: true })
            autosaveForm.requestSubmit()
        })
    }

    function syncAutosaveFields(fieldNames) {
        for (const fieldName of fieldNames) {
            const sourceField = document.getElementById(fieldName)
            const targetField = autosaveForm.elements.namedItem(fieldName)
            if (!sourceField || !targetField) {
                continue
            }
            targetField.value = sourceField.value
        }
    }

    function serializeDraftFields(fieldNames) {
        const params = new URLSearchParams()
        for (const fieldName of fieldNames) {
            params.set(fieldName, document.getElementById(fieldName)?.value ?? '')
        }
        return params.toString()
    }

    function debounce(callback, waitMs) {
        clearTimeout(autoSaveTimer)
        autoSaveTimer = setTimeout(() => {
            callback()
        }, waitMs)
    }
}
