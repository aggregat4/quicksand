export function normalizeKey(event) {
    const key = event.key
    if (key === 'Enter') {
        return 'enter'
    }
    if (key === 'Escape') {
        return 'escape'
    }
    if (key.length === 1) {
        return key.toLowerCase()
    }
    return key.toLowerCase()
}

export function keysEqual(bindingKeys, sequence) {
    const expected = Array.isArray(bindingKeys) ? bindingKeys : [bindingKeys]
    if (expected.length !== sequence.length) {
        return false
    }
    return expected.every((key, index) => key === sequence[index])
}

export function isChordPrefix(bindingKeys, sequence) {
    const expected = Array.isArray(bindingKeys) ? bindingKeys : [bindingKeys]
    if (sequence.length >= expected.length) {
        return false
    }
    return expected.slice(0, sequence.length).every((key, index) => key === sequence[index])
}

export function modifiersMatch(event, modifiers = {}) {
    const key = normalizeKey(event)
    const shiftAgnostic = key === '?' || key === '!'

    if (modifiers.shift === true && !event.shiftKey && !shiftAgnostic) {
        return false
    }
    if (modifiers.shift === false && event.shiftKey && !shiftAgnostic) {
        return false
    }
    if (modifiers.alt === true && !event.altKey) {
        return false
    }
    if (modifiers.alt === false && event.altKey) {
        return false
    }
    if (modifiers.ctrl === true && !event.ctrlKey) {
        return false
    }
    if (modifiers.ctrl === false && event.ctrlKey) {
        return false
    }
    if (modifiers.meta === true && !event.metaKey) {
        return false
    }
    if (modifiers.meta === false && event.metaKey) {
        return false
    }
    if (modifiers.ctrl !== true && (event.ctrlKey || event.metaKey)) {
        return false
    }
    if (modifiers.alt !== true && event.altKey) {
        return false
    }
    return true
}

/**
 * @param {KeyboardEvent} event
 * @param {string[]} pendingKeys
 * @param {object[]} bindings
 * @param {Set<string>} activeContexts
 */
export function matchBinding(event, pendingKeys, bindings, activeContexts) {
    const key = normalizeKey(event)
    const sequence = pendingKeys.length > 0 ? [...pendingKeys, key] : [key]

    const eligible = bindings.filter((binding) =>
        binding.contexts.some((context) => activeContexts.has(context))
        && modifiersMatch(event, binding.modifiers))

    const exact = eligible.find((binding) => keysEqual(binding.keys, sequence))
    if (exact) {
        return { binding: exact, pendingKeys: [] }
    }

    const hasPrefix = eligible.some((binding) => isChordPrefix(binding.keys, sequence))
    if (hasPrefix) {
        return { binding: null, pendingKeys: sequence }
    }

    if (pendingKeys.length > 0) {
        const fresh = eligible.find((binding) => keysEqual(binding.keys, [key]))
        if (fresh) {
            return { binding: fresh, pendingKeys: [] }
        }
    }

    return { binding: null, pendingKeys: [] }
}

export function formatBindingKeys(keys) {
    const parts = Array.isArray(keys) ? keys : [keys]
    if (parts.length === 1) {
        return formatSingleKey(parts[0])
    }
    return parts.map(formatSingleKey).join(' then ')
}

function formatSingleKey(key) {
    if (key === 'enter') {
        return 'Enter'
    }
    if (key === 'escape') {
        return 'Esc'
    }
    if (key.length === 1) {
        return key.toUpperCase()
    }
    return key
}
