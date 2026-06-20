export function getEmailIdFromNode(node) {
    const emailIdAttribute = node.getAttribute('id')
    return emailIdAttribute.substring('email'.length)
}
