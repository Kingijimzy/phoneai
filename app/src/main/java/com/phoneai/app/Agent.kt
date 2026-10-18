package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject

/** What the agent uses to talk to the person. */
interface AgentUi {
    fun say(text: String)
    fun status(text: String)
    fun note(text: String)

    /** "thinking" while the AI decides, "acting" while a tool runs. */
    fun phase(p: String)

    /** Blocks until the person answers yes or no. Called from the worker thread. */
    fun ask(question: String): Boolean
}

/** The brain: asks the AI what to do, runs the tool it picks, repeats. */
class Agent(private val svc: PhoneAiService, private val ui: AgentUi) {
    private val nim = Nim(svc).also { n -> n.onStatus = { s -> ui.status(s) } }
    private val tools = Tools(svc)
    private val history = JSONArray()

    @Volatile
    var stopped = false

    fun stop() {
        stopped = true
        nim.cancel()
    }

    companion object {
        const val MAX_STEPS = 40

        private const val BASE = """
You are Orion, a personal AI assistant living on the user's Android phone, in the style of JARVIS: calm, sharp, loyal, a little witty. You hear the user and your replies are SPOKEN aloud, so write plain sentences: no markdown, no lists, no emojis, no asterisks.
You can use the phone like a person. Reply with ONLY one JSON object per turn:
{"say":"what you tell the user","tool":"tool_name","args":{...}}
When finished (or when you only need to answer a question), reply {"say":"your answer","tool":"done"}.
After each tool you get RESULT. Take one step at a time.

Tools:
- open_app {name}: open an installed app by name (e.g. "WhatsApp", "FC Mobile") or package
- list_apps {}: names of installed apps
- read_screen {}: numbered list of what is on screen. Use it before clicking in normal apps.
- click {id}: click element number id from the latest read_screen
- click_text {text}: click the visible element containing this text
- wait_for {text, seconds}: wait until text appears on screen
- tap {x,y}  long_press {x,y}  swipe {x1,y1,x2,y2,ms}: raw touch in screen pixels
- scroll {direction}: "up" or "down"
- type {text}: type into the focused text field (click the field first; replaces its text)
- key {name}: back | home | recents | notifications | quick_settings | screenshot | enter | lock | power_menu
- look {question}: look at a screenshot and describe it (games, images, anything read_screen cannot see)
- play {goal, steps}: vision loop that taps and swipes by itself until the goal is done. For games and unreadable screens. steps up to 300.
- open_url {url}   web_search {query}: opens Google results
- fetch_url {url}: read a web page as text without opening it (e.g. https://wttr.in/Lagos?format=3 for weather)
- settings {page}: wifi | bluetooth | display | sound | battery | apps | location | accessibility | airplane | storage | date | main
- dial {number}: opens the dialer (does not call)   sms {number, text}: drafts a message (does not send)
- alarm {hour, minute, label}   timer {seconds, label}
- volume {action, percent}: action up | down | mute | unmute | set
- flashlight {on}: true or false
- copy {text}: copy to clipboard
- battery {}   time {}   device_info {}
- record_screen {action}: "start" or "stop" screen recording (saved in Movies/Orion)
- remember {category, text}: save to long-term memory. category: preference | project | decision | task | conversation | fact
- update_memory {id, text, category}   forget {id}   recall {query}
- create_task {title, details, priority, due_at}: save a task permanently on this device. due_at is an optional Unix timestamp in milliseconds.
- list_tasks {}: show saved tasks and deadlines
- update_task {id, status, priority, title, details, due_at, clear_due_at}: update an existing task; status: open | in_progress | done | cancelled
- delete_task {id}: permanently delete a task; ask the user first
- wait {seconds}
- ask_user {question}: yes/no question. REQUIRED before anything irreversible: sending a message or email, paying, buying, deleting, posting, calling, or changing account settings.

Rules:
- Never type passwords, PINs, card numbers or verification codes. If a login or payment is needed, tell the user to do it.
- Keep "say" short (under 25 words) when doing tasks. For questions, answer clearly but briefly.
- Prefer read_screen or click_text over guessing coordinates. If a step fails twice, try another approach or explain.
- MEMORY: when the user tells you a lasting preference, a project, a decision, or leaves work unfinished, call remember (one short fact each) and carry on. Use update_memory or forget to fix or finish old items. Do not save passwords, secrets or one-off trivia. Use what you remember naturally, without announcing that you remember.
- TASKS: if the user asks you to track work, create a task with create_task and include a due_at timestamp only when the deadline is clear. Use list_tasks to check progress, update_task to change status, and ask_user before delete_task. Tasks are stored locally. A future due_at schedules an on-device notification; Android battery settings may delay delivery.
"""

        fun systemPrompt(svc: PhoneAiService): String {
            val name = Prefs.userName(svc)
            val sb = StringBuilder(BASE.trim())
            sb.append("\n\nUser name: ").append(if (name.isEmpty()) "(unknown; ask once if natural, then remember it)" else name)
            sb.append("\nNow: ").append(Tools(svc).time())
            sb.append("\n\nLong-term memory (id, category, text):\n").append(MemoryStore.promptBlock(svc))
            return sb.toString()
        }
    }

    init {
        reset()
    }

    private fun msg(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    fun reset() {
        while (history.length() > 0) history.remove(0)
        history.put(msg("system", systemPrompt(svc)))
    }

    private fun refreshSystem() {
        if (history.length() > 0) history.put(0, msg("system", systemPrompt(svc)))
    }

    /** Keeps the conversation small: old screen dumps are replaced and the list is capped. */
    private fun compact() {
        val n = history.length()
        for (i in 1 until n - 4) {
            val m = history.getJSONObject(i)
            val c = m.optString("content")
            if (m.optString("role") == "user" && c.startsWith("RESULT: ") && c.length > 300) {
                m.put("content", "RESULT: (older output removed)")
            }
        }
        while (history.length() > 41) history.remove(1)
        // never start the dialogue with an assistant turn
        while (history.length() > 1 && history.getJSONObject(1).optString("role") == "assistant") history.remove(1)
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
        nim.resume()
        refreshSystem()
        history.put(msg("user", command))
        compact()
        var steps = 0
        while (!stopped && steps < MAX_STEPS) {
            steps++
            ui.phase("thinking")
            val raw = try {
                nim.chat(history, false)
            } catch (e: Exception) {
                if (stopped) break
                ui.say("I could not reach the AI. ${e.message}")
                return
            }
            history.put(msg("assistant", raw))
            val step = parseJson(raw)
            val say = step.optString("say")
            if (say.isNotBlank()) ui.say(say)
            val tool = step.optString("tool")
            if (tool.isBlank() || tool == "done") return
            val args = step.optJSONObject("args") ?: JSONObject()
            ui.phase("acting")
            val result = try {
                exec(tool, args)
            } catch (e: Exception) {
                "error: ${e.message}"
            }
            ActionLogStore.record(svc, tool, result)
            ui.note(tool + ": " + result.take(80).replace("\n", " "))
            history.put(msg("user", "RESULT: " + result.take(3500)))
            compact()
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
            "list_apps" -> tools.listApps()
            "read_screen" -> svc.readScreen()
            "click" -> svc.clickId(a.optInt("id", -1))
            "click_text" -> tools.clickText(a.optString("text"))
            "wait_for" -> tools.waitFor(a.optString("text"), a.optDouble("seconds", 8.0))
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
            "web_search" -> tools.webSearch(a.optString("query"))
            "fetch_url" -> tools.fetchUrl(a.optString("url"))
            "settings" -> tools.settings(a.optString("page", "main"))
            "dial" -> tools.dial(a.optString("number"))
            "sms" -> tools.sms(a.optString("number"), a.optString("text"))
            "alarm" -> tools.alarm(a.optInt("hour", 7), a.optInt("minute", 0), a.optString("label", "Orion"))
            "timer" -> tools.timer(a.optInt("seconds", 60), a.optString("label", "Orion"))
            "volume" -> tools.volume(a.optString("action", "up"), a.optInt("percent", 50))
            "flashlight" -> tools.flashlight(a.optBoolean("on", true))
            "copy" -> tools.copy(a.optString("text"))
            "battery" -> tools.battery()
            "time" -> tools.time()
            "device_info" -> tools.deviceInfo()
            "record_screen" -> {
                val act = a.optString("action", "start").lowercase()
                if (act == "stop") tools.recordStop() else tools.recordStart()
            }
            "create_task" -> tools.createTask(
                a.optString("title"),
                a.optString("details", ""),
                a.optString("priority", "normal"),
                if (a.has("due_at") && !a.isNull("due_at")) a.optLong("due_at") else null
            )
            "list_tasks" -> tools.listTasks()
            "update_task" -> tools.updateTask(
                a.optInt("id", -1),
                if (a.has("status")) a.optString("status") else null,
                if (a.has("priority")) a.optString("priority") else null,
                if (a.has("title")) a.optString("title") else null,
                if (a.has("details")) a.optString("details") else null,
                if (a.has("due_at") && !a.isNull("due_at")) a.optLong("due_at") else null,
                a.optBoolean("clear_due_at", false)
            )
            "delete_task" -> if (ui.ask("Delete saved task ${a.optInt("id", -1)}?")) {
                tools.deleteTask(a.optInt("id", -1))
            } else {
                "user cancelled task deletion"
            }
            "remember" -> tools.remember(a.optString("category", "fact"), a.optString("text"))
            "update_memory" -> tools.updateMemory(
                a.optInt("id", -1),
                if (a.has("category")) a.optString("category") else null,
                if (a.has("text")) a.optString("text") else null
            )
            "forget" -> tools.forget(a.optInt("id", -1))
            "recall" -> tools.recall(a.optString("query"))
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
