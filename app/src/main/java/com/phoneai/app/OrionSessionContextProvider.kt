package com.phoneai.app

import android.content.Context

/** The only entry points for provider-facing conversation context. */
internal object OrionSessionContextProvider {
    fun forChat(context: Context, request: String): String =
        OrionCentralBrain.contextFor(context.applicationContext, request)

    fun forLiveVoice(context: Context, request: String = "Live voice conversation"): String =
        OrionCentralBrain.contextFor(context.applicationContext, request)
}
