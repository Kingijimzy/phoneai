package com.phoneai.app

/** A plain checklist of what is connected and what is not, so nothing is silently missing. Pure. */
internal object DeskHealth {
    enum class Level { OK, TODO, OFF, WARN }
    class Item(val name: String, val level: Level, val detail: String)

    class Inputs(
        val aiConfigured: Boolean, val bybitKeys: Boolean, val bybitTestnet: Boolean, val readOnly: Boolean,
        val derivToken: Boolean, val derivRealAllowed: Boolean, val calendarEvents: Int, val playbook: Int, val journal: Int,
        val automation: Boolean, val micPermission: Boolean, val voiceOn: Boolean, val wakeModel: Boolean
    )

    fun check(i: Inputs): List<Item> = listOf(
        if (i.aiConfigured) Item("AI provider", Level.OK, "A provider key is saved, so the chat can answer.")
        else Item("AI provider", Level.TODO, "Add an API key in Settings. Without it the chat cannot answer."),
        if (i.bybitKeys) Item("Bybit", Level.OK, "Keys saved, " + (if (i.bybitTestnet) "practice (testnet) mode." else "REAL money mode."))
        else Item("Bybit", Level.OFF, "Optional. Add a read-only key on the Desk screen to see live positions and sync trades."),
        when {
            i.readOnly -> Item("Order safety", Level.OK, "Read-only. Orion cannot place orders.")
            i.bybitTestnet -> Item("Order safety", Level.OK, "Practice orders allowed. Each one still needs your tap and passes the risk gate.")
            else -> Item("Order safety", Level.WARN, "Real orders are allowed. Each still needs your tap and passes the risk gate.")
        },
        when {
            i.derivToken && i.derivRealAllowed -> Item("Deriv", Level.WARN, "Token saved and real Deriv accounts are allowed.")
            i.derivToken -> Item("Deriv", Level.OK, "Token saved. Demo accounts only.")
            else -> Item("Deriv", Level.OFF, "Optional. Add a Deriv token on the Desk screen for forex practice trading.")
        },
        if (i.calendarEvents > 0) Item("News calendar", Level.OK, "${i.calendarEvents} upcoming events loaded.")
        else Item("News calendar", Level.TODO, "No calendar data yet. It loads on its own when you are online."),
        if (i.playbook > 0) Item("Playbook", Level.OK, "${i.playbook} saved strateg${if (i.playbook == 1) "y" else "ies"}.")
        else Item("Playbook", Level.TODO, "Describe your strategy in chat or upload a chart screenshot to save one."),
        if (i.journal > 0) Item("Trade journal", Level.OK, "${i.journal} trades recorded.")
        else Item("Trade journal", Level.OFF, "Empty. Sync Bybit or import a CSV to find your habits."),
        if (i.automation) Item("Watcher", Level.OK, "Runs desk checks in the background and sends alerts.")
        else Item("Watcher", Level.OFF, "Automation is off. Turn it on in the Desk screen for alerts."),
        when {
            i.micPermission && i.wakeModel -> Item("Voice", Level.OK, "Mic allowed and the offline wake word is installed.")
            i.micPermission -> Item("Voice", Level.OK, "Mic allowed. Install the offline wake word in Settings for hands-free \"Orion\".")
            else -> Item("Voice", Level.OFF, "Tap Mic in the chat once to allow the microphone.")
        }
    )

    fun mark(l: Level) = when (l) { Level.OK -> "✓"; Level.TODO -> "○"; Level.OFF -> "–"; Level.WARN -> "!" }

    fun summary(items: List<Item>): String {
        val ok = items.count { it.level == Level.OK }
        val todo = items.count { it.level == Level.TODO }
        return "$ok of ${items.size} ready" + if (todo > 0) ", $todo need${if (todo == 1) "s" else ""} attention" else ""
    }

    fun text(items: List<Item>): String =
        "Setup check: " + summary(items) + "\n" + items.joinToString("\n") { "${mark(it.level)} ${it.name}: ${it.detail}" }
}

/** Short guide to the trading chat, shown on request. */
internal object DeskTour {
    val text = """How this screen works:
- The card at the top is your live status: PnL, the companion's mood, the next news and the desk mode. Tap the character to switch between the drawn characters, the dot-matrix orb and the hologram head. Tap the status line to open the Desk.
- The tabs under it hold quick actions: Start, Market, Positions, Strategy, Chain and More. Tap any chip to ask for it, or just type.
- Upload a TradingView screenshot with Chart and I will turn it into a strategy for your playbook.
- Voice: the Voice chip speaks alerts and replies, Mic takes one spoken request, Talk keeps a hands-free conversation going, and Wake listens for the word Orion.
- Desk shows the playbook, simulations, positions, chain watch, what the watcher did, backup, and a setup check.
- I only read data and make proposals. Any real order needs your tap and passes the risk gate."""
}
