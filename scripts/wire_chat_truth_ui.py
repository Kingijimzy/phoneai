#!/usr/bin/env python3
"""Fix AgentChatActivity: remove bad injection; wire OrionChatTruthUi into addBubble."""
from pathlib import Path

p = Path("app/src/main/java/com/phoneai/app/AgentChatActivity.kt")
t = p.read_text()

# 1) Remove broken helper accidentally injected into AgentChatStore object
bad = '''
    /** Structured truth cards for backend notes; never invent progress. */
    private fun appendTruthOrText(container: android.widget.LinearLayout, kind: String, text: String) {
        val card = OrionChatTruthUi.tryBuildCard(this, kind, text)
        if (card != null) {
            container.addView(card)
            return
        }
        val tv = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(Theme.TEXT)
            textSize = 14.5f
            setTextIsSelectable(true)
        }
        container.addView(tv)
    }
'''
if bad in t:
    t = t.replace(bad, "\n")
    print("removed broken AgentChatStore helper")
elif "appendTruthOrText" in t:
    # broader strip
    import re
    t2, n = re.subn(
        r"\n\s*/\*\* Structured truth cards.*?private fun appendTruthOrText\([\s\S]*?container\.addView\(tv\)\s*\}\n",
        "\n",
        t,
        count=1,
    )
    if n:
        t = t2
        print("removed broken helper via regex")
    else:
        print("appendTruthOrText present but pattern missed")

# 2) Wire addBubble
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

if "OrionChatTruthUi.tryBuildCard(this, \"note\"" in t or "OrionChatTruthUi.tryBuildCard(this, \"note\"" in t:
    print("addBubble already truth-wired")
elif old in t:
    t = t.replace(old, new, 1)
    print("wired addBubble")
else:
    print("WARNING: addBubble block not found")

p.write_text(t)
print("done", p.stat().st_size)
