package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * Compatibility facade for older UI call sites.
 * Action history is intentionally disabled: no action, argument, result or decision is persisted.
 */
data class OrionActionEvent(
    val at: Long,
    val tool: String,
    val outcome: String,
    val failureCategory: String = "",
    val arguments: String = "",
    val result: String = "",
    val verificationStatus: String = "BLOCKED_OR_UNVERIFIED"
)

object ActionLogStore {
    fun record(context: Context, tool: String, result: String) = Unit
    fun record(
        context: Context,
        tool: String,
        args: JSONObject,
        result: String,
        verificationStatus: OrionTaskOutcome.Status
    ) = Unit
    fun recentFailureHints(context: Context, limit: Int = 4): String = ""
    fun all(context: Context): List<OrionActionEvent> = emptyList()
    fun clear(context: Context) = Unit
}
