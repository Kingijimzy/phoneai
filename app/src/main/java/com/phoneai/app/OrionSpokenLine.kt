package com.phoneai.app

import org.json.JSONObject

/**
 * What Orion says aloud for ordinary chat, hands-free conversation, or a live call. The spoken line is a separate channel from the full written answer.
 *
 * The written answer stays on screen untouched. For speech, the model writes a separate short line and
 * picks a mood; the mood and the sentence shape then set pace and breathing room, so delivery changes
 * with the moment instead of sounding like one fixed narrator voice.
 */
object OrionSpokenLine {
    enum class Mood(val label: String, val speed: Float, val pauseMs: Int) {
        NEUTRAL("neutral", 1.00f, 260),
        WARM("warm", 0.97f, 300),
        CALM("calm", 0.92f, 380),
        FOCUSED("focused", 1.04f, 200),
        URGENT("urgent", 1.10f, 140),
        LIGHT("light", 1.05f, 220);

        companion object {
            fun from(label: String): Mood = values().firstOrNull { it.label == label.trim().lowercase() } ?: NEUTRAL
        }
    }

    /** [text] may be empty: the model decided nothing is worth saying aloud. */
    data class Line(val text: String, val mood: Mood, val source: String)
    data class Chunk(val text: String, val speed: Float, val pauseAfterMs: Int)

    const val SYSTEM: String = "You are Orion's spoken-response composer. This is used for ordinary typed chat as well as hands-free or live voice; no live call or microphone is required. Turn the written reply into what Orion would naturally SAY to Kingi, not a read-aloud version of the text. " +
        "Reply with ONLY JSON: {\"say\": string, \"mood\": \"neutral|warm|calm|focused|urgent|light\"}. " +
        "Rules: sound like a thoughtful person continuing an existing conversation: contractions, plain words, one to three short sentences, the useful point first. Respond to the user's actual intent and relevant context; do not mechanically summarize or repeat the written answer. " +
        "Match the moment for mood: casual or friendly -> light or warm; Kingi stressed, something went wrong, or bad news -> calm; " +
        "technical work in progress or a result to report -> focused; a real risk, failure or time-critical thing -> urgent; otherwise neutral. " +
        "Never read out code, file paths, URLs, JSON, tables, lists, IDs, hashes or tool names. Preserve the important meaning and result while leaving implementation detail in the visible chat. If the written reply is mostly technical material, " +
        "say one sentence about the outcome and point to the screen, for example that the details are on screen. " +
        "No greeting or sign-off unless this is clearly the start or end of the conversation. No emoji, no stage directions, no markdown. " +
        "Use commas and short sentences for rhythm. Ask a question only if it helps. Do not announce what you are about to say. " +
        "Never claim anything the written reply does not confirm. If nothing is worth saying aloud, return {\"say\":\"\",\"mood\":\"neutral\"}."

    fun userMessage(written: String, heard: String, recentContext: String = ""): String =
        "RECENT CONVERSATION (oldest first; use only what is relevant):\\n" +
            recentContext.trim().takeLast(1800).ifBlank { "(no earlier context)" } +
            "\\nLATEST USER MESSAGE: " + heard.trim().take(300).ifBlank { "(no earlier message)" } +
            "\\nWRITTEN REPLY (shown on screen):\\n" + written.trim().take(1400)

    private val jsonShaped = Regex("[`|{}\\[\\]<>#*_]|https?://|\\n\\s*[-•\\d]+[.)]?\\s")

    /** Short plain replies are already speakable; skip the extra model call so they stay instant. */
    fun needsComposing(written: String): Boolean {
        val t = written.trim()
        if (t.isEmpty()) return false
        return t.length > 140 || jsonShaped.containsMatchIn(t)
    }

    /** Null when the model answer is unusable so the caller falls back to the plain speech filter. */
    fun parse(raw: String): Line? {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = runCatching { JSONObject(raw.substring(a, b + 1)) }.getOrNull() ?: return null
        if (!o.has("say")) return null
        return Line(clean(o.optString("say")), Mood.from(o.optString("mood")), "model")
    }

    fun fallback(written: String): Line? =
        OrionVoiceOutputPolicy.forSpeech(written)?.let { Line(clean(it), Mood.NEUTRAL, "fallback") }?.takeIf { it.text.isNotBlank() }

    private val emoji = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]")
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val markup = Regex("[`*_#>~|]")

    /** Makes the line safe for any speech engine: no symbols, links or markup, at most three sentences. */
    fun clean(say: String): String {
        var t = say.replace(url, " ")
            .replace(emoji, "")
            .replace(markup, "")
            .replace(Regex("\\s*[—–]\\s*"), ", ")
            .replace("&", " and ")
            .replace(Regex("(\\d)\\s?%"), "$1 percent")
            .replace(Regex("\\s+"), " ")
            .trim()
        val sentences = splitSentences(t)
        if (sentences.size > 3) t = sentences.take(3).joinToString(" ")
        return t.take(320).trim()
    }

    internal fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Turns a line into per-sentence pace and breathing room. [baseRate] is the owner's chosen speech rate. */
    fun plan(line: Line, baseRate: Float = 1f): List<Chunk> {
        val sentences = splitSentences(line.text)
        if (sentences.isEmpty()) return emptyList()
        return sentences.mapIndexed { i, s ->
            val words = s.split(Regex("\\s+")).size
            var speed = baseRate * line.mood.speed
            var pause = line.mood.pauseMs
            val last = i == sentences.lastIndex
            if (s.endsWith("?")) { speed *= 0.97f; pause += 80 }
            if (words <= 3) pause = (pause * 0.6f).toInt()
            if (last) speed *= 0.97f
            Chunk(s, speed.coerceIn(0.75f, 1.25f), if (last) 0 else pause)
        }
    }
}
