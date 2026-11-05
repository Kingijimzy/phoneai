#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/java/com/phoneai/app/AgentChatActivity.kt")
if not p.exists():
    raise SystemExit("AgentChatActivity.kt missing")
t = p.read_text()
if "OrionChatTruthUi.tryBuildCard" in t or "OrionChatTruthUi.parseNote" in t:
    print("already wired")
    raise SystemExit(0)

old = '''    private fun addBubble(user: Boolean, text: String): TextView {
        val bubble = TextView(this).apply {
            this.text = if (user) text else Markdown.render(MarkdownTables.flatten(text)); textSize = 14.5f
            if (!user) movementMethod = LinkMovementMethod.getInstance()
            setTextColor(if (user) Theme.ON_ACCENT else Theme.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(if (user) Theme.CYAN else Theme.PANEL); cornerRadius = dp(18).toFloat() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8); gravity = if (user) Gravity.END else Gravity.START
            if (user) marginStart = dp(48) else marginEnd = dp(48)
        }
        messages.addView(bubble, lp)
        Motion.enter(bubble)
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        return bubble
    }'''

new = '''    private fun addBubble(user: Boolean, text: String): TextView {
        // Chat truth UI: structured backend notes become cards; never invent progress.
        if (!user) {
            val truth = OrionChatTruthUi.tryBuildCard(this, "note", text)
            if (truth != null) {
                messages.addView(truth, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                Motion.enter(truth)
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                // Return a disposable TextView for callers that update "waiting" bubbles.
                return TextView(this).apply { visibility = android.view.View.GONE }
            }
        }
        val bubble = TextView(this).apply {
            this.text = if (user) text else Markdown.render(MarkdownTables.flatten(text)); textSize = 14.5f
            if (!user) movementMethod = LinkMovementMethod.getInstance()
            setTextColor(if (user) Theme.ON_ACCENT else Theme.TEXT)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(if (user) Theme.CYAN else Theme.PANEL); cornerRadius = dp(18).toFloat() }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8); gravity = if (user) Gravity.END else Gravity.START
            if (user) marginStart = dp(48) else marginEnd = dp(48)
        }
        messages.addView(bubble, lp)
        Motion.enter(bubble)
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        return bubble
    }'''

if old not in t:
    raise SystemExit("addBubble block not found exactly")
p.write_text(t.replace(old, new, 1))
print("wired addBubble truth cards")
