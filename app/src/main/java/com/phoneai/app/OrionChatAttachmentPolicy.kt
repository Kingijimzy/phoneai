package com.phoneai.app

/**
 * Which attachments deserve their own row under the transcript. Images already appear in the bubble that carried them
 * (or in Orion's image result), and generated files appear inline, so listing them again made every picture show twice.
 */
internal object OrionChatAttachmentPolicy {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    fun isImage(a: OrionChatAttachmentStore.Attachment): Boolean =
        a.mimeType.startsWith("image/", ignoreCase = true) || a.name.substringAfterLast('.', "").lowercase() in imageExt

    /** Files that are not already visible elsewhere in the chat, de-duplicated by content hash. */
    fun listed(all: List<OrionChatAttachmentStore.Attachment>, inlineIds: Set<String>): List<OrionChatAttachmentStore.Attachment> =
        all.distinctBy { it.sha256.ifBlank { it.id } }.filterNot { it.id in inlineIds || isImage(it) }
}
