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
            .filterNot { isRetired(PREFIX + it.id, OrionForesight.stats()) }
            .take(MAX_EXPOSED)
            .map { definition(it.id, it.description) }
    }.getOrDefault(emptyList())

    internal fun definition(id: String, description: String): ToolDefinition {
        val summary = description.replace('\n', ' ').take(180).ifBlank { "tool Orion built for himself" }
        return ToolDefinition(PREFIX + id, "$PREFIX$id {input?: json object}: $summary (a tool you forged; pass INPUT, read RESULT)", summary, ToolAccess.WRITE)
    }

    /**
     * Natural selection for forged tools: one that has failed three times and never worked drops out of
     * the tool list, so Orion stops reaching for it and forges a better one. Forging the same id again
     * does not resurrect it until its record is cleared, so he picks a new name or fixes the cause.
     */
    fun isRetired(name: String, stats: Map<String, Triple<Int, Int, Long>>): Boolean =
        stats[name]?.let { (ok, failed, _) -> failed >= 3 && ok == 0 } ?: false

    fun isForged(name: String) = name.startsWith(PREFIX) && name.length > PREFIX.length

    /** Maps a direct call onto the governed extension_run path so every guard still applies. */
    fun rewrite(name: String, args: JSONObject): Pair<String, JSONObject> {
        if (!isForged(name)) return name to args
        val input = args.optJSONObject("input") ?: JSONObject(args.toString())
        return "extension_run" to JSONObject().put("id", name.removePrefix(PREFIX)).put("input", input)
    }
}
