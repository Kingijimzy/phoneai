package com.phoneai.app

import org.json.JSONObject

/**
 * Owns messaging and call-intent operations. Outbound actions retain an immediate, exact-action
 * approval gate; SMS only opens a draft and dialing only opens the dialer.
 */
internal object OrionCommunicationCapabilityExecutor {
    private val supportedTools = setOf(
        "telegram_status", "telegram_inbox", "telegram_send", "dial", "sms", "notification_action"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        stopped: () -> Boolean,
        confirm: (String) -> Boolean
    ): String = when (tool) {
        "telegram_status" -> tools.telegramStatus()
        "telegram_inbox" -> if (stopped()) {
            "error: task was stopped before Telegram inbox retrieval."
        } else TelegramBridge.checkUpdates(service)
        "telegram_send" -> {
            val message = args.optString("message").trim()
            when {
                message.isBlank() -> "error: Telegram message cannot be blank."
                stopped() -> "error: task was stopped before Telegram send; no message was sent."
                !confirm("Send this Telegram message to the saved target? Message: " + message.take(500)) ->
                    "error: user cancelled Telegram send"
                stopped() -> "error: task was stopped after approval; no Telegram message was sent."
                else -> tools.telegramSend(message)
            }
        }
        "dial" -> {
            if (stopped()) "error: task was stopped before opening the dialer."
            else tools.dial(args.optString("number"))
        }
        "sms" -> {
            val number = args.optString("number").trim()
            val message = args.optString("text")
            when {
                number.isBlank() -> "error: a recipient phone number is required."
                message.isBlank() -> "error: SMS draft text cannot be blank."
                stopped() -> "error: task was stopped before opening the SMS draft."
                !confirm("Open an SMS draft to $number with this exact text? Orion will not send it; you must press Send yourself. Message: " + message.take(500)) ->
                    "error: user cancelled SMS draft"
                stopped() -> "error: task was stopped after approval; no SMS draft was opened."
                else -> tools.sms(number, message.take(4000))
            }
        }
        "notification_action" -> {
            val action = args.optString("action").trim().lowercase()
            val key = args.optString("key").trim()
            val reply = args.optString("text")
            when {
                action !in setOf("reply", "dismiss") -> "error: action must be reply or dismiss"
                key.isBlank() -> "error: a current notification key is required"
                action == "reply" && reply.isBlank() -> "error: reply text cannot be blank"
                stopped() -> "error: task was stopped before notification action; no action was dispatched"
                action == "reply" && !confirm("Reply to the selected notification with this exact text? " + reply.take(500)) ->
                    "error: user cancelled notification action"
                action == "dismiss" && !confirm("Dismiss the selected safe notification? This removes it from the notification shade.") ->
                    "error: user cancelled notification action"
                stopped() -> "error: task was stopped before notification action; no action was dispatched"
                else -> OrionNotificationListener.notificationAction(key, action, reply.take(4000))
            }
        }
        else -> "error: communication executor does not implement '$tool'."
    }
}
