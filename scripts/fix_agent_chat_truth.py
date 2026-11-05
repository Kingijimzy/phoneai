#!/usr/bin/env python3
from pathlib import Path
p = Path("app/src/main/java/com/phoneai/app/AgentChatActivity.kt")
t = p.read_text()
while "appendTruthOrText" in t:
    start = t.find("/** Structured truth cards")
    if start < 0:
        start = t.find("private fun appendTruthOrText")
    if start < 0:
        break
    line_start = t.rfind("\n", 0, start) + 1
    idx = t.find("container.addView(tv)", start)
    if idx < 0:
        break
    end = t.find("\n    }\n", idx)
    if end < 0:
        break
    t = t[:line_start] + t[end + 6 :]
    print("removed helper")
old = (
    "    private fun addBubble(user: Boolean, text: String): TextView {\n"
    "        val bubble = TextView(this).apply {\n"
    "            this.text = if (user) text else Markdown.render(MarkdownTables.flatten(text)); textSize = 14.5f\n"
    "            if (!user) movementMethod = LinkMovementMethod.getInstance()\n"
    "            setTextColor(if (user) Theme.ON_ACCENT else Theme.TEXT)\n"
    "            setTextIsSelectable(true)\n"
    "            setPadding(dp(14), dp(10), dp(14), dp(10))\n"
    "            background = GradientDrawable().apply { setColor(if (user) Theme.CYAN else Theme.PANEL); cornerRadius = dp(18).toFloat() }\n"
    "        }\n"
    "        val lp = LinearLayout.LayoutParams(-2, -2).apply {\n"
    "            topMargin = dp(8); gravity = if (user) Gravity.END else Gravity.START\n"
    "            if (user) marginStart = dp(48) else marginEnd = dp(48)\n"
    "        }\n"
    "        messages.addView(bubble, lp)\n"
    "        Motion.enter(bubble)\n"
    "        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }\n"
    "        return bubble\n"
    "    }"
)
new = (
    "    private fun addBubble(user: Boolean, text: String): TextView {\n"
    "        // Chat truth UI: structured backend notes become cards; never invent progress.\n"
    "        if (!user) {\n"
    "            val truth = OrionChatTruthUi.tryBuildCard(this, \"note\", text)\n"
    "            if (truth != null) {\n"
    "                messages.addView(truth, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })\n"
    "                Motion.enter(truth)\n"
    "                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }\n"
    "                return TextView(this).apply { visibility = android.view.View.GONE }\n"
    "            }\n"
    "        }\n"
    "        val bubble = TextView(this).apply {\n"
    "            this.text = if (user) text else Markdown.render(MarkdownTables.flatten(text)); textSize = 14.5f\n"
    "            if (!user) movementMethod = LinkMovementMethod.getInstance()\n"
    "            setTextColor(if (user) Theme.ON_ACCENT else Theme.TEXT)\n"
    "            setTextIsSelectable(true)\n"
    "            setPadding(dp(14), dp(10), dp(14), dp(10))\n"
    "            background = GradientDrawable().apply { setColor(if (user) Theme.CYAN else Theme.PANEL); cornerRadius = dp(18).toFloat() }\n"
    "        }\n"
    "        val lp = LinearLayout.LayoutParams(-2, -2).apply {\n"
    "            topMargin = dp(8); gravity = if (user) Gravity.END else Gravity.START\n"
    "            if (user) marginStart = dp(48) else marginEnd = dp(48)\n"
    "        }\n"
    "        messages.addView(bubble, lp)\n"
    "        Motion.enter(bubble)\n"
    "        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }\n"
    "        return bubble\n"
    "    }"
)
if 'OrionChatTruthUi.tryBuildCard(this, "note"' not in t and 'OrionChatTruthUi.tryBuildCard(this, \"note\"' not in t:
    if old not in t:
        raise SystemExit("addBubble not found")
    t = t.replace(old, new, 1)
    print("wired")
else:
    print("already")
if "appendTruthOrText" in t:
    raise SystemExit("helper remains")
p.write_text(t)
print("ok", p.stat().st_size)
