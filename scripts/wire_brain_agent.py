#!/usr/bin/env python3
"""Wire OrionAgentBrainBridge into Agent.kt (central brain on all command paths)."""
from pathlib import Path

p = Path("app/src/main/java/com/phoneai/app/Agent.kt")
t = p.read_text()
changed = False

old_pre = """        // Ground every run in live capability/graph context before acting.
        if (!resumeRequest) {
            val pre = OrionPreActionContext.assess(svc, command)
            ui.note("[CONTEXT]\n" + pre.summary)
        }"""

new_pre = """        // CENTRAL BRAIN (all command paths): context, references, memory, path choice.
        if (!resumeRequest) {
            val recent = ConversationContextPolicy.recent(
                (0 until chatHist.length()).mapNotNull { i ->
                    val o = chatHist.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("role") to o.optString("content")
                }
            )
            val activeObj = runCatching { OrionObjectiveGraph.load(svc)?.objective.orEmpty() }.getOrDefault("")
            val turn = OrionAgentBrainBridge.planTurn(svc, command, recent, activeObj)
            lastBrainSystemExtra = turn.systemExtra
            ui.note("[CONTEXT]\n" + turn.frame.preAction.summary)
            if (turn.blockTools) {
                ui.note("[TASK:CLARIFY]\n" + (turn.clarification ?: "Need a clearer goal"))
                ui.say(turn.clarification ?: "Which task should I continue?")
                return
            }
            if (turn.frame.preferDirectAction) {
                ui.note("[BRAIN]\nDirect-action path preferred; multi-objective graph is not mandatory.")
            } else if (turn.frame.requiresMultiStep) {
                ui.note("[BRAIN]\nMulti-step path; keep accurate task state and verify each step.")
            }
        }"""

if "OrionAgentBrainBridge.planTurn" not in t:
    if old_pre in t:
        t = t.replace(old_pre, new_pre, 1)
        changed = True
        print("replaced pre-action with planTurn")
    else:
        marker = "        // One-intent commands (play music, Google it in Chrome) skip the model entirely."
        if marker not in t:
            raise SystemExit("cannot find insertion point for planTurn")
        t = t.replace(marker, new_pre + "\n" + marker, 1)
        changed = True
        print("inserted planTurn before FastIntents")
else:
    print("planTurn already present")

if "lastBrainSystemExtra" not in t:
    anchor = '    @Volatile private var currentTask = ""'
    if anchor not in t:
        raise SystemExit("currentTask field missing")
    t = t.replace(anchor, anchor + '\n    @Volatile private var lastBrainSystemExtra: String = ""', 1)
    changed = True
    print("added lastBrainSystemExtra field")

if "BRAIN_PIPELINE_EXTRA" not in t:
    needle = "OrionCentralBrain.contextFor(svc, command)"
    if needle in t:
        t = t.replace(
            needle,
            'OrionCentralBrain.contextFor(svc, command) + (if (lastBrainSystemExtra.isNotBlank()) "\n\n" + lastBrainSystemExtra else "") /* BRAIN_PIPELINE_EXTRA */',
            1,
        )
        changed = True
        print("appended brain extra to contextFor")

if "OrionAgentBrainBridge.afterTool" not in t:
    old_an = (
        "    private fun activityNote(tool: String, args: JSONObject, result: String): String {\n"
        "        val failed = result.trimStart().startsWith(\"error\", ignoreCase = true)"
    )
    new_an = (
        "    private fun activityNote(tool: String, args: JSONObject, result: String): String {\n"
        "        runCatching {\n"
        "            val goal = currentTask.ifBlank { \"\" }\n"
        "            if (goal.isNotBlank()) {\n"
        "                val verdict = OrionAgentBrainBridge.afterTool(svc, goal, tool, result)\n"
        "                if (!verdict.accept) {\n"
        "                    ui.note(\"[VERIFY:REJECT]\\n\" + tool + \": \" + verdict.reason +\n"
        "                        (verdict.suggestAlternate?.let { \" → try \" + it } ?: \"\"))\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "        val failed = result.trimStart().startsWith(\"error\", ignoreCase = true)"
    )
    if old_an not in t:
        raise SystemExit("activityNote signature not found")
    t = t.replace(old_an, new_an, 1)
    changed = True
    print("wired afterTool into activityNote")
else:
    print("afterTool already present")

p.write_text(t)
print("changed", changed, "size", p.stat().st_size)
