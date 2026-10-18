package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** What the agent uses to talk to the person. */
interface AgentUi {
    fun say(text: String)
    fun note(text: String)
    /** Blocks until the person answers yes or no. Called from the worker thread. */
    fun ask(question: String): Boolean
}

/** The brain: asks the NVIDIA model what to do, runs the tool it picks, repeats. */
class Agent(private val svc: PhoneAiService, private val ui: AgentUi) {
    private val nim = Nim(svc)
    private val history = JSONArray()

    @Volatile
    var stopped = false

    companion object {
        const val MAX_STEPS = 40

        val SYSTEM = """
You control an Android phone through tools, like a person using it. Reply with ONLY one JSON object per turn:
{"say":"short message to the user (optional)","tool":"tool_name","args":{...}}
When the task is finished, reply {"say":"short summary","tool":"done"}.
After each tool you get RESULT. Take one step at a time.

Tools:
- open_app {name}: open an installed app by its name (e.g. "WhatsApp", "FC Mobile") or by package name
- read_screen {}: list what is on screen as numbered elements. ALWAYS use this before clicking in normal apps.
- click {id}: click element number id from the latest read_screen
- tap {x,y}   long_press {x,y}   swipe {x1,y1,x2,y2,ms}: raw touch in screen pixels (read_screen shows the screen size)
- scroll {direction}: direction is "up" or "down"; scrolls the current list
- type {text}: type into the focused text field (click the field first). It replaces the field text.
- key {name}: back | home | recents | notifications | quick_settings | screenshot | enter
- look {question}: look at a screenshot and describe it (games, images, anything read_screen cannot see)
- play {goal, steps}: vision loop that taps and swipes by itself until the goal is done. Use it for games and for screens read_screen cannot understand. steps up to 300.
- open_url {url}
- wait {seconds}
- ask_user {question}: ask the user yes or no. REQUIRED before anything irreversible: sending a message or email, paying, buying, deleting, posting, or changing account settings.

Rules:
- Never type passwords, PINs, card numbers or verification codes. If a login or payment is needed, tell the user to do it.
- Keep "say" under 20 words. Prefer read_screen over guessing coordinates.
- If a step fails twice, try a different approach or explain the problem.
""".trimIndent()
    }

    init {
        reset()
    }

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    fun reset() {
        while (history.length() > 0) history.remove(0)
        history.put(msg("system", SYSTEM))
    }

    private fun trim() {
        while (history.length() > 41) history.remove(1)
    }

    private fun parseJson(raw: String): JSONObject {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        if (s >= 0 && e > s) {
            try {
                return JSONObject(raw.substring(s, e + 1))
            } catch (ex: Exception) {
            }
        }
        return JSONObject().put("say", raw.trim())
    }

    fun run(command: String) {
        stopped = false
        history.put(msg("user", command))
        trim()
        var steps = 0
        while (!stopped && steps < MAX_STEPS) {
            steps++
            val raw = try {
                nim.chat(history, false)
            } catch (e: Exception) {
                ui.say("AI error: ${e.message}")
                return
            }
            history.put(msg("assistant", raw))
            val step = parseJson(raw)
            val say = step.optString("say")
            if (say.isNotBlank()) ui.say(say)
            val tool = step.optString("tool")
            if (tool.isBlank() || tool == "done") return
            val args = step.optJSONObject("args") ?: JSONObject()
            val result = try {
                exec(tool, args)
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            ui.note(tool + ": " + result.take(80).replace("\n", " "))
            history.put(msg("user", "RESULT: $result"))
        }
        if (stopped) ui.say("Stopped.") else ui.say("I reached the step limit. Tell me to continue if needed.")
    }

    private fun num(a: JSONObject, k: String): Float? {
        if (!a.has(k)) return null
        val d = a.optDouble(k, Double.NaN)
        return if (d.isNaN()) null else d.toFloat()
    }

    private fun xy(a: JSONObject, kx: String, ky: String, f: (Float, Float) -> String): String {
        val x = num(a, kx)
        val y = num(a, ky)
        return if (x == null || y == null) "error: need $kx and $ky" else f(x, y)
    }

    private fun exec(tool: String, a: JSONObject): String {
        return when (tool) {
            "open_app" -> svc.openApp(a.optString("name"))
            "read_screen" -> svc.readScreen()
            "click" -> svc.clickId(a.optInt("id", -1))
            "tap" -> xy(a, "x", "y") { x, y -> svc.tap(x, y) }
            "long_press" -> xy(a, "x", "y") { x, y -> svc.longPress(x, y) }
            "swipe" -> {
                val x1 = num(a, "x1")
                val y1 = num(a, "y1")
                val x2 = num(a, "x2")
                val y2 = num(a, "y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    "error: swipe needs x1,y1,x2,y2"
                } else {
                    svc.swipe(x1, y1, x2, y2, a.optLong("ms", 300L))
                }
            }
            "scroll" -> svc.scroll(a.optString("direction", "down"))
            "type" -> svc.typeText(a.optString("text"))
            "key" -> svc.key(a.optString("name"))
            "look" -> look(a.optString("question", "Describe what is on the screen."))
            "play" -> play(a.optString("goal"), a.optInt("steps", 20))
            "open_url" -> svc.openUrl(a.optString("url"))
            "wait" -> {
                val s = a.optDouble("seconds", 2.0).coerceIn(0.5, 10.0)
                Thread.sleep((s * 1000).toLong())
                "waited"
            }
            "ask_user" -> if (ui.ask(a.optString("question", "Okay to continue?"))) {
                "user said YES"
            } else {
                "user said NO. Do not do it."
            }
            else -> "error: unknown tool $tool"
        }
    }

    private fun imageMessage(prompt: String, shot: Shot): JSONArray {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + shot.b64))
            )
        return JSONArray().put(JSONObject().put("role", "user").put("content", content))
    }

    private fun look(question: String): String {
        val shot = svc.screenshotBase64()
            ?: return "error: could not take a screenshot (the screen may be protected)"
        return nim.chat(imageMessage("$question Answer in 2-3 short sentences.", shot), true, 250, 0.2)
    }

    /** Vision loop for games and screens read_screen cannot understand. */
    private fun play(goal: String, steps: Int): String {
        var note = ""
        val n = steps.coerceIn(1, 300)
        for (i in 1..n) {
            if (stopped) return "stopped at step $i"
            val shot = svc.screenshotBase64() ?: return "error: could not take a screenshot"
            val prompt = "Goal: $goal\n$note\nThe image is ${shot.w}x${shot.h} pixels. " +
                "Pick ONE next action. Reply ONLY JSON: " +
                "{\"thought\":\"short\",\"action\":\"tap|swipe|key|wait|done\",\"x\":0,\"y\":0," +
                "\"x2\":0,\"y2\":0,\"key\":\"back\"}. " +
                "Coordinates are in image pixels. Dismiss pop-ups and ads if they block the goal. " +
                "Use done when the goal is reached."
            val raw = nim.chat(imageMessage(prompt, shot), true, 200, 0.1)
            val step = parseJson(raw)
            val act = step.optString("action", "wait")
            ui.note("[$i] $act - ${step.optString("thought")}")
            when (act) {
                "done" -> return "done after $i steps"
                "tap" -> svc.tap(
                    (step.optDouble("x") * shot.scale).toFloat(),
                    (step.optDouble("y") * shot.scale).toFloat()
                )
                "swipe" -> svc.swipe(
                    (step.optDouble("x") * shot.scale).toFloat(),
                    (step.optDouble("y") * shot.scale).toFloat(),
                    (step.optDouble("x2") * shot.scale).toFloat(),
                    (step.optDouble("y2") * shot.scale).toFloat(),
                    400L
                )
                "key" -> svc.key(step.optString("key", "back"))
            }
            note = "Previous step: $act (${step.optString("thought")})"
            Thread.sleep(1200)
        }
        return "reached $n steps"
    }
}
