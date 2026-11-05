#!/usr/bin/env python3
"""Wire OrionChatTruthUi into AgentChatActivity message rendering if markers exist."""
from pathlib import Path

candidates = [
    Path("app/src/main/java/com/phoneai/app/AgentChatActivity.kt"),
    Path("app/src/main/java/com/phoneai/app/MainChatActivity.kt"),
]

for p in candidates:
    if not p.exists():
        print("skip missing", p)
        continue
    t = p.read_text()
    if "OrionChatTruthUi.tryBuildCard" in t:
        print("already wired", p)
        continue

    # Common pattern: adding a note/ai line as TextView — inject card attempt before fallback
    markers = [
        'when (kind) {',
        'kind == "note"',
        '"note" ->',
        "fun appendLine",
        "fun addMessage",
    ]
    # Soft insert: helper method at end of class if we find class closing is hard;
    # instead patch a simple recognizable block used in AgentChatActivity
    needle = None
    for line in t.splitlines():
        if "kind" in line and "note" in line and "->" in line:
            needle = line
            break

    # Add import-free fully-qualified call via a local helper function injection near first "private fun"
    helper = '''
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
    if "appendTruthOrText" not in t:
        # Insert helper before last closing brace of class — fragile; use first private fun slot
        idx = t.find("    private fun ")
        if idx < 0:
            print("no private fun in", p)
            continue
        t = t[:idx] + helper + "\n" + t[idx:]
        p.write_text(t)
        print("injected helper into", p)
    else:
        print("helper exists", p)

print("done")
