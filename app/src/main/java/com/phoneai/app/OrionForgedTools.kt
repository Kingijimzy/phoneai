package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Tools Orion forged for himself, exposed as real callable tools (ext_<id>) instead of
 * a detour through extension_run. Ultron does the same: a forged action joins the registry.
 */
internal object OrionForgedTools {
    const val PREFIX = "ext_"
    private const val MAX_EXPOSED = 12

    fun definitions(context: Context): List<ToolDefinition> = runCatching {
        SelfExtendingExtensionStore(context).all()
            .filter { it.enabled && it.verified && it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source) }
            .take(MAX_EXPOSED)
            .map { definition(it.id, it.description) }
    }.getOrDefault(emptyList())

    internal fun definition(id: String, description: String): ToolDefinition {
        val summary = description.replace('\n', ' ').take(180).ifBlank { "tool Orion built for himself" }
        return ToolDefinition(PREFIX + id, "$PREFIX$id {input?: json object}: $summary (a tool you forged; pass INPUT, read RESULT)", summary, ToolAccess.WRITE)
    }

    fun isForged(name: String) = name.startsWith(PREFIX) && name.length > PREFIX.length

    /** Maps a direct call onto the governed extension_run path so every guard still applies. */
    fun rewrite(name: String, args: JSONObject): Pair<String, JSONObject> {
        if (!isForged(name)) return name to args
        val input = args.optJSONObject("input") ?: JSONObject(args.toString())
        return "extension_run" to JSONObject().put("id", name.removePrefix(PREFIX)).put("input", input)
    }
}
