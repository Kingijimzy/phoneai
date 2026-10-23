package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Handles common commands WITHOUT any AI: instant, free, no limits.
 * Returns what Orion should say, or null when the AI is needed.
 */
class Local(
    private val svc: PhoneAiService,
    private val tools: Tools,
    private val exec: (String, JSONObject) -> String,
    private val lastSteps: () -> JSONArray,
    private val stopped: () -> Boolean,
    private val note: (String) -> Unit,
    private val confirm: (String) -> Boolean = { false },
    private val ai: (String, String) -> String = { _, _ -> "" }
) {

    private fun replay(name: String, steps: JSONArray): String {
        val targetSize = svc.replayScreenSize()
        for (i in 0 until steps.length()) {
            if (stopped()) return "Stopped."
            val s = steps.getJSONObject(i)
            val tool = s.optString("tool")
            val gap = s.optLong("gap", -1L)
            val sourceW = s.optInt("screenW", targetSize.first).coerceAtLeast(1)
            val sourceH = s.optInt("screenH", targetSize.second).coerceAtLeast(1)
            fun scaleX(value: Double) = (value * targetSize.first / sourceW).toInt().coerceIn(0, targetSize.first)
            fun scaleY(value: Double) = (value * targetSize.second / sourceH).toInt().coerceIn(0, targetSize.second)
            val sigA = s.optJSONArray("sig")
            if (sigA != null && s.has("sx") && s.has("sy")) {
                // Wait for the recorded pre-action screen cue, scaled to this device's current resolution.
                val sig = IntArray(sigA.length()) { sigA.getInt(it) }
                val limit = (gap * 3).coerceIn(6000L, 30000L)
                when (svc.waitForSig(scaleX(s.getDouble("sx")), scaleY(s.getDouble("sy")), sig, limit) { stopped() }) {
                    "timeout" -> return "I stopped at step ${i + 1} of $name because the screen did not look right after ${limit / 1000} seconds."
                    "stopped" -> return "Stopped."
                    "unavailable" -> Thread.sleep(gap.coerceIn(200L, 5000L))
                    else -> Thread.sleep(40L)
                }
            } else {
                Thread.sleep(if (gap >= 0) gap.coerceIn(100L, 5000L) else 600L)
            }

            // Scale all stored coordinates to the current display so routines survive resolution changes.
            val args = JSONObject((s.optJSONObject("args") ?: JSONObject()).toString())
            when (tool) {
                "tap", "long_press" -> {
                    if (args.has("x")) args.put("x", scaleX(args.getDouble("x")))
                    if (args.has("y")) args.put("y", scaleY(args.getDouble("y")))
                }
                "swipe" -> {
                    listOf("x1", "x2").forEach { key -> if (args.has(key)) args.put(key, scaleX(args.getDouble(key))) }
                    listOf("y1", "y2").forEach { key -> if (args.has(key)) args.put(key, scaleY(args.getDouble(key))) }
                }
                "swipe_path" -> {
                    val oldPoints = args.optJSONArray("points")
                    if (oldPoints != null) {
                        val scaledPoints = JSONArray()
                        for (j in 0 until oldPoints.length()) {
                            val point = oldPoints.optJSONObject(j) ?: continue
                            scaledPoints.put(JSONObject().put("x", scaleX(point.optDouble("x")))
                                .put("y", scaleY(point.optDouble("y"))))
                        }
                        args.put("points", scaledPoints)
                    }
                }
            }
            val result = exec(tool, args)
            note("step ${i + 1}/${steps.length()}: $tool · ${result.take(44)}")
            if (result.startsWith("error")) return "I stopped at step ${i + 1} of $name. " + result.removePrefix("error: ").take(100)
        }
        return "Finished $name."
    }

    private fun loopRoutine(name: String, steps: JSONArray, times: Int): String {
        var done = 0
        for (k in 1..times) {
            if (stopped()) break
            note("run $k of $times")
            val r = replay(name, steps)
            if (r.startsWith("I stopped") || r == "Stopped.") {
                return "$r Completed $done of $times runs."
            }
            done++
            Thread.sleep(1200L)
        }
        return "Finished $done runs of $name."
    }

    private val fillers = listOf(
        "hey orion", "hi orion", "ok orion", "okay orion", "orion", "please", "can you", "could you",
        "would you", "will you", "i want you to", "i need you to", "kindly", "for me", "now", "quickly"
    )

    private fun clean(raw: String): String {
        var t = raw.lowercase().trim()
        t = t.replace(Regex("[?!.,]+$"), "").trim()
        var changed = true
        while (changed) {
            changed = false
            for (f in fillers) {
                if (t.startsWith("$f ")) {
                    t = t.removePrefix("$f ").trim()
                    changed = true
                }
                if (t.endsWith(" $f")) {
                    t = t.removeSuffix(" $f").trim()
                    changed = true
                }
            }
        }
        return t.replace(Regex("\\s+"), " ")
    }

    private fun name(): String {
        val n = Prefs.userName(svc)
        return if (n.isEmpty()) "" else ", $n"
    }

    private fun failed(r: String): Boolean = r.startsWith("error")

    private fun category(t: String): String = when {
        Regex("prefer|always|never|i like|i love|i hate|i don't like").containsMatchIn(t) -> "preference"
        Regex("project|working on|building|developing").containsMatchIn(t) -> "project"
        Regex("decided|decision|going with|i chose|we chose").containsMatchIn(t) -> "decision"
        Regex("todo|to do|need to|remind|unfinished|later|tomorrow|deadline").containsMatchIn(t) -> "task"
        else -> "fact"
    }

    fun tryRun(raw: String): String? {
        val t = clean(raw)
        if (t.isEmpty()) return null
        if (Regex("^(open )?(trading|bybit) (settings|setup|keys|screen)$").matches(t)) {
            svc.startActivity(android.content.Intent(svc, TradingActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opening trading settings."
        }
        Trading.handle(svc, t, confirm, ai)?.let { return it }

        // ---- chat basics
        if (Regex("^(hi|hello|hey|yo|good morning|good afternoon|good evening)( there)?$").matches(t)) {
            return "Hello${name()}. What would you like me to do?"
        }
        if (Regex("^(thanks|thank you|thanks a lot|thank you very much|cheers)$").matches(t)) return "Anytime${name()}."
        if (Regex("^(who are you|what are you|what is your name|what's your name)$").matches(t)) {
            return "I'm Orion, your assistant on this phone. I can open apps, control the screen and answer questions."
        }
        val myName = Regex("^(my name is|call me|i am|i'm) ([a-z]{2,20})$").find(t)
        if (myName != null && !t.startsWith("i am ") && !t.startsWith("i'm ")) {
            val n = myName.groupValues[2].replaceFirstChar { it.uppercase() }
            Prefs.setUserName(svc, n)
            tools.remember("fact", "The user's name is $n")
            return "Nice to meet you, $n."
        }


        // ---- routines (replayed with no AI)
        val saveM = Regex("^(save|store) (this|that|it|these steps|the last task)( as)? ?(an? )?(routine|macro|shortcut|workflow)? ?(called |named |for )?(.+)$").find(t)
        if (saveM != null) {
            val steps = lastSteps()
            if (steps.length() == 0) return "I have nothing to save yet. Give me a task first, then say save this as a routine."
            val rn = saveM.groupValues[7].trim()
            return if (MacroStore.save(svc, rn, steps)) "Saved. Say run $rn any time and I will do it without using AI."
            else "I couldn't save that."
        }
        if (Regex("^(list|show|what are)( all)?( my)? (routines|macros|shortcuts|workflows)$").matches(t)) {
            val n = MacroStore.names(svc)
            return if (n.isEmpty()) "You have no saved routines yet." else "Your routines: " + n.joinToString(", ") + "."
        }
        val delM = Regex("^(delete|remove|forget) (the )?(routine|macro|shortcut|workflow) (.+)$").find(t)
        if (delM != null) {
            return if (MacroStore.delete(svc, delM.groupValues[4])) "Deleted." else "I don't have a routine with that name."
        }
        val teachM = Regex("^(teach|learn) (this )?(routine |macro )?(called |named |as )?(.+)$").find(t)
            ?: Regex("^(start )?record(ing)? (a |the )?(routine|macro) (called |named )?(.+)$").find(t)
        if (teachM != null) {
            val nm = teachM.groupValues.last().trim()
            if (nm.isNotEmpty() && nm != "mode") {
                val r = svc.startTeach(nm)
                return if (failed(r)) "I am already recording." else "Teaching mode is on for $nm. Do the task on your phone like normal. When you are finished, tap the red button at the top."
            }
        }
        if (Regex("^(teach|learn)( mode)?$").matches(t)) return "Say teach, then a name. For example: teach fc rewards."
        val loopM = Regex("^(run|repeat|loop|do|start)( my| the)?( routine| macro)? (.+?) (\\d{1,3}) times?$").find(t)
        if (loopM != null) {
            val f = MacroStore.find(svc, loopM.groupValues[4])
            if (f != null) return loopRoutine(f.first, f.second, loopM.groupValues[5].toInt().coerceIn(1, 200))
        }
        val keepM = Regex("^(keep (running|repeating|doing)|loop|repeat) (my |the )?(routine |macro )?(.+)$").find(t)
        if (keepM != null) {
            val f = MacroStore.find(svc, keepM.groupValues[5])
            if (f != null) return loopRoutine(f.first, f.second, 500)
        }
        val runM = Regex("^(run|do|play|start|perform|execute|launch|use)( my| the)?( routine| macro| shortcut| workflow)? (.+)$").find(t)
        val exactName = MacroStore.names(svc).firstOrNull { it == MacroStore.norm(t) }
        val found = (if (runM != null) MacroStore.find(svc, runM.groupValues[4]) else null)
            ?: (if (exactName != null) MacroStore.find(svc, exactName) else null)
        if (found != null) return replay(found.first, found.second)

        // ---- help
        if (Regex("^(what can you do|help|help me|what are your commands|commands|what do you do)$").matches(t)) {
            return "I can open apps, tap and scroll for you, set alarms and timers, call and message, record your screen, do maths and conversions, take notes, navigate, play YouTube, read your screen aloud, and run routines you teach me. Say teach, then a name, to show me a task once."
        }

        // ---- maths and conversions (instant)
        Calc.tryConvert(t)?.let { return it }
        Calc.tryMath(t)?.let { return it }

        // ---- coin, dice, random
        if (Regex("^(flip|toss) (a )?coin$").matches(t)) return if (Math.random() < 0.5) "Heads." else "Tails."
        if (Regex("^roll (a |the )?(dice|die)$").matches(t)) return "You rolled a ${(1..6).random()}."
        val rnd = Regex("^(random number|pick a number)( between| from)? (\\d+) (and|to) (\\d+)$").find(t)
        if (rnd != null) {
            val a = rnd.groupValues[3].toInt()
            val b = rnd.groupValues[5].toInt()
            return "${(minOf(a, b)..maxOf(a, b)).random()}."
        }

        // ---- notes
        val noteM = Regex("^(take a note|add a note|note down|make a note|note)( that| to self)?:? (.+)$").find(t)
        if (noteM != null) {
            tools.remember("task", noteM.groupValues[3].trim())
            return "Noted."
        }
        if (Regex("^(read|show|what are)( me)?( all)?( my)? notes$").matches(t) || t == "my notes") {
            val n = MemoryStore.all(svc).filter { it.category == "task" }.sortedByDescending { it.updated }.take(5)
            return if (n.isEmpty()) "You have no notes yet." else "Your latest notes: " + n.joinToString(". ") { it.text }
        }

        // ---- AI usage
        if (Regex("^(ai status|ai usage|my limits|check limits|api status|how are my keys|are my keys working)$").matches(t)) return Nim.status(svc)

        // ---- read the screen aloud (no AI)
        if (Regex("^(read|read out)( me)?( my| the| this)? (screen|page)( aloud| out loud| to me)?$").matches(t)) {
            val r = svc.readScreen()
            if (failed(r)) return "I can't read this screen. It may be protected."
            val texts = Regex("\"([^\"]{2,})\"").findAll(r).map { it.groupValues[1] }.filter { !it.startsWith("(") }.toList()
            return if (texts.isEmpty()) "I can't find any text here." else "Here is what I see: " + texts.take(14).joinToString(". ")
        }

        // ---- camera, navigation, whatsapp, youtube
        if (Regex("^(open (the )?camera|take a (photo|picture|selfie)|camera)$").matches(t)) {
            val r = tools.camera()
            return if (failed(r)) "I couldn't open the camera." else "Camera is open."
        }
        val navM = Regex("^(navigate|take me|directions|drive|route)( me)? to (.+)$").find(t)
        if (navM != null) {
            val place = navM.groupValues[3].trim()
            val r = tools.navigate(place)
            return if (failed(r)) "I couldn't start navigation." else "Starting directions to $place."
        }
        val waM = Regex("^(whatsapp|send whatsapp to|message on whatsapp) (\\+?\\d[\\d ]{6,}) (.+)$").find(t)
        if (waM != null) {
            val r = tools.whatsapp(waM.groupValues[2], waM.groupValues[3])
            return if (failed(r)) r.removePrefix("error: ") else "WhatsApp draft is ready. Press send when you are happy."
        }
        val ytM = Regex("^play (.+) on youtube$").find(t) ?: Regex("^(youtube|play (music|a song|the song|song)) (.+)$").find(t)
        if (ytM != null) {
            val q = ytM.groupValues.last().trim()
            val r = tools.youtube(q)
            return if (failed(r)) "I couldn't open YouTube." else "Searching YouTube for $q."
        }

        // ---- time and battery
        if (Regex("^(what('s| is) )?(the )?(time|date|day)( is it)?( now| today| right now)?$").matches(t) ||
            t == "what time is it" || t == "what day is it" || t == "what's today's date"
        ) return tools.time()
        if (t.contains("battery") && Regex("level|percent|how|what|check|status|left").containsMatchIn(t)) {
            return "Your " + tools.battery() + "."
        }

        // ---- flashlight
        if (Regex("flash ?light|torch").containsMatchIn(t) && Regex("on|off|toggle|turn|switch").containsMatchIn(t)) {
            val off = Regex("\\boff\\b").containsMatchIn(t)
            val r = tools.flashlight(!off)
            return if (failed(r)) "I couldn't reach the flashlight." else if (off) "Flashlight off." else "Flashlight on."
        }

        // ---- volume
        val setVol = Regex("^(set )?(the )?(media )?volume (to |at )?(\\d{1,3})( percent| %)?$").find(t)
        if (setVol != null) {
            tools.volume("set", setVol.groupValues[5].toInt())
            return "Volume set to ${setVol.groupValues[5].toInt().coerceIn(0, 100)} percent."
        }
        if (Regex("^(turn |make it |volume )?(the )?(volume )?(up|louder|higher|increase)( the volume)?$").matches(t) ||
            t == "volume up" || t == "turn it up" || t == "louder"
        ) {
            tools.volume("up", 0)
            return "Volume up."
        }
        if (t == "volume down" || t == "turn it down" || t == "quieter" || t == "turn the volume down" || t == "lower the volume") {
            tools.volume("down", 0)
            return "Volume down."
        }
        if (t == "mute" || t == "mute the volume" || t == "mute the phone") {
            tools.volume("mute", 0)
            return "Muted."
        }
        if (t == "unmute") {
            tools.volume("unmute", 0)
            return "Unmuted."
        }

        // ---- screen recording
        if (t.contains("record") && (t.contains("screen") || t.contains("recording"))) {
            return if (t.contains("stop") || t.contains("end") || t.contains("finish")) {
                val r = tools.recordStop()
                if (failed(r)) "I couldn't stop the recording. $r" else if (r == "not recording") "I'm not recording right now." else "Recording saved in your Movies folder."
            } else {
                val r = tools.recordStart()
                if (failed(r)) r.removePrefix("error: ") else "Recording your screen. Say stop recording when you're done."
            }
        }
        if (t == "stop recording" || t == "stop the recording") {
            val r = tools.recordStop()
            return if (failed(r)) r else if (r == "not recording") "I'm not recording right now." else "Recording saved in your Movies folder."
        }

        // ---- phone keys
        val keyMap = linkedMapOf(
            "go home" to "home", "home" to "home", "home screen" to "home", "go to home screen" to "home",
            "go to the home screen" to "home", "go back" to "back", "back" to "back",
            "recent apps" to "recents", "recents" to "recents", "show recent apps" to "recents",
            "open recent apps" to "recents", "notifications" to "notifications",
            "open notifications" to "notifications", "show notifications" to "notifications",
            "quick settings" to "quick_settings", "open quick settings" to "quick_settings",
            "take a screenshot" to "screenshot", "screenshot" to "screenshot", "take screenshot" to "screenshot",
            "lock the screen" to "lock", "lock screen" to "lock", "lock the phone" to "lock", "lock phone" to "lock",
            "power menu" to "power_menu", "open power menu" to "power_menu"
        )
        val k = keyMap[t]
        if (k != null) {
            val r = svc.key(k)
            return if (failed(r)) "That didn't work on this phone." else "Done."
        }
        if (t == "scroll down" || t == "scroll up") {
            svc.scroll(if (t.endsWith("up")) "up" else "down")
            return "Scrolled."
        }

        // ---- alarm and timer
        val alarm = Regex("(set |make )?(an? )?alarm (for |at )(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a m|p m)?").find(t)
        if (alarm != null) {
            var hour = alarm.groupValues[4].toInt()
            val minute = alarm.groupValues[5].ifEmpty { "0" }.toInt()
            val ap = alarm.groupValues[6].replace(" ", "")
            if (ap == "pm" && hour < 12) hour += 12
            if (ap == "am" && hour == 12) hour = 0
            val r = tools.alarm(hour, minute, "Orion")
            return if (failed(r)) "I couldn't set the alarm." else "Alarm set for %02d:%02d.".format(hour, minute)
        }
        val timer = Regex("(set |start )?(an? )?timer (for )?(\\d+) ?(second|sec|minute|min|hour|hr)s?").find(t)
        if (timer != null) {
            val n = timer.groupValues[4].toInt()
            val unit = timer.groupValues[5]
            val secs = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            val r = tools.timer(secs, "Orion")
            return if (failed(r)) "I couldn't set the timer." else "Timer set for $n ${if (unit.startsWith("h")) "hours" else if (unit.startsWith("m")) "minutes" else "seconds"}."
        }

        // ---- settings pages
        val st = Regex("^(open |show |go to )?(wi-?fi|bluetooth|display|sound|battery|location|storage|airplane|date)( mode)? settings$").find(t)
        if (st != null) {
            tools.settings(st.groupValues[2].replace("-", ""))
            return "Opening ${st.groupValues[2]} settings."
        }
        if (t == "open settings" || t == "settings") {
            tools.settings("main")
            return "Opening settings."
        }

        // ---- calls and search
        val call = Regex("^(call|dial|phone) ([0-9 +\\-()]{5,})$").find(t)
        if (call != null) {
            val r = tools.dial(call.groupValues[2])
            return if (failed(r)) "I couldn't open the dialer." else "The dialer is ready. Press call."
        }
        val search = Regex("^(search( the web)?( for)?|google|look up|search google for) (.+)$").find(t)
        if (search != null) {
            val q = search.groupValues[4]
            tools.webSearch(q)
            return "Searching for $q."
        }
        val weather = Regex("^(what('s| is) )?(the )?weather( like)?( in| for| at)?( .+)?( today| now| right now)?$").find(t)
        if (weather != null) {
            val city = weather.groupValues[6].trim().replace(Regex(" (today|now|right now)$"), "")
            val url = "https://wttr.in/" + URLEncoder.encode(city, "UTF-8").replace("+", "%20") + "?format=3"
            val r = tools.fetchUrl(url)
            return if (failed(r) || r.length > 200) "I couldn't get the weather right now." else r
        }

        // ---- memory
        val rem = Regex("^(remember|please remember|note|save this|don't forget)( that)? (.+)$").find(t)
        if (rem != null) {
            val text = raw.trim().replace(Regex("^(?i)(hey orion,? |orion,? )?(please )?(remember|note|save this|don't forget)( that)? "), "")
            tools.remember(category(t), text.replaceFirstChar { it.uppercase() })
            return "Got it. I'll remember that."
        }
        if (Regex("^(what do you (remember|know) about me|what do you remember|show (my )?memory)$").matches(t)) {
            val all = MemoryStore.all(svc)
            if (all.isEmpty()) return "I haven't saved anything yet."
            return "I remember ${all.size} things. For example: " +
                all.sortedByDescending { it.updated }.take(3).joinToString(". ") { it.text } + ". You can see everything in the Orion app under Memory."
        }

        // ---- click something on screen
        val click = Regex("^(click|tap|press|select|hit) (on )?(the )?(.+?)( button)?$").find(t)
        if (click != null && !Regex("\\d").containsMatchIn(click.groupValues[4]) &&
            !Regex(" and | then ").containsMatchIn(t)
        ) {
            val target = click.groupValues[4]
            val r = tools.clickText(target)
            return if (failed(r)) "I can't see \"$target\" on the screen." else "Done."
        }

        // ---- open an app or a website (single, simple request only)
        val open = Regex("^(open|launch|start|run|go to) (the )?(.+?)( app| application)?$").find(t)
        if (open != null && !Regex(" and | then | play | send | search | message | call ").containsMatchIn(" " + t + " ")) {
            val what = open.groupValues[3].trim()
            if (what.startsWith("http") || Regex("\\.(com|org|net|io|ng|co|app)(/|$)").containsMatchIn(what)) {
                val url = if (what.startsWith("http")) what else "https://" + what.replace(" ", "")
                svc.openUrl(url)
                return "Opening $what."
            }
            val r = svc.openApp(what)
            return if (failed(r)) "I can't find an app called $what." else "Opening ${r.removePrefix("opened ")}."
        }
        return null
    }
}
