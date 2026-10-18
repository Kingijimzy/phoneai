package com.phoneai.app

import java.net.URLEncoder

/**
 * Handles common commands WITHOUT any AI: instant, free, no limits.
 * Returns what Orion should say, or null when the AI is needed.
 */
class Local(private val svc: PhoneAiService, private val tools: Tools) {

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
