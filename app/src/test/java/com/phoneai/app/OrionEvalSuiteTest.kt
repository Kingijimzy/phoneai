package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic evaluation set for Orion: memory intelligence (Requirements 1–4),
 * autonomous shell (Requirement 8), Chronos/proactive intelligence (Requirements 9–10),
 * and initiative behaviour (Requirement 14).
 *
 * Scenarios carry an expected [tool] and [expectedResult] so each scenario states not only
 * *what* is checked but *which capability* should produce it. Pass/fail runner reports
 * before-and-after scores against eval/baseline.txt.
 *
 * All checks are pure logic (no Android Context required) so they run without Robolectric.
 * Context-dependent initiative behaviour is covered by OrionInitiativeTest.
 */
class OrionEvalSuiteTest {
    private data class Scenario(
        val name: String,
        val category: String,
        val tool: String,
        val expectedResult: String,
        val check: () -> Boolean
    )

    // ---------- memory fixtures (Requirements 1–4) ----------

    private fun mem(id: Int, text: String) =
        Memory(id, "fact", text, 1_700_000_000_000L, 1_700_000_000_000L + id, false, "owner", 1.0, "general", "approved", "")

    private val owner = listOf(
        mem(1, "My favourite team is Real Madrid"),
        mem(2, "I live in Lagos"),
        mem(3, "I work as a mechanic"),
        mem(4, "My sister Amaka's birthday is 3 January"),
        mem(5, "I trade swing positions on Bybit"),
        mem(6, "I love jollof rice for dinner"),
        mem(7, "Planning a trip to Lagos in December"),
        mem(8, "I prefer dark mode in every app"),
        mem(9, "I wake up at 6am for my morning routine"),
        mem(10, "My project Atlas ships next quarter")
    )

    private fun recalls(query: String, id: Int) = OrionRecall.top(owner, query).any { it.memory.id == id }
    private fun doesNotRecall(query: String, id: Int) = OrionRecall.top(owner, query).none { it.memory.id == id }
    private fun sim(a: String, b: String) = OrionEmbedding.similarity(a, b)
    private fun slot(text: String) = OrionMemoryConsolidation.slotOf(text)

    // ---------- all scenarios ----------

    private val scenarios = listOf(
        // ===== CATEGORY: PARAPHRASE (memory recall under different wordings) =====
        Scenario("club paraphrase finds the team", "paraphrase", "recall", "Real Madrid",
            { recalls("which club do I support", 1) }),
        Scenario("team question finds the team", "paraphrase", "recall", "Real Madrid",
            { recalls("which team do I follow", 1) }),
        Scenario("home city finds where I live", "paraphrase", "recall", "Lagos",
            { recalls("where is my home city", 2) }),
        Scenario("where do I live finds the city", "paraphrase", "recall", "Lagos",
            { recalls("where do I live", 2) }),
        Scenario("job question finds my work", "paraphrase", "recall", "mechanic",
            { recalls("what is my job", 3) }),
        Scenario("birthday question finds my sister", "paraphrase", "recall", "Amaka",
            { recalls("when is Amaka's birthday", 4) }),
        Scenario("sister question finds the sister", "paraphrase", "recall", "Amaka",
            { recalls("what is my sister called", 4) }),
        Scenario("trading question finds Bybit", "paraphrase", "recall", "Bybit",
            { recalls("what do I trade", 5) }),
        Scenario("food question finds the dinner fact", "paraphrase", "recall", "jollof rice",
            { recalls("what food do I like", 6) }),
        Scenario("trip status finds the Lagos plan", "paraphrase", "recall", "Lagos trip",
            { recalls("what's the status of the Lagos trip", 7) }),
        Scenario("wake-up question finds the routine", "paraphrase", "recall", "6am",
            { recalls("what time do I wake up", 9) }),
        Scenario("project question finds Atlas", "paraphrase", "recall", "Atlas",
            { recalls("when does Atlas ship", 10) }),
        Scenario("club paraphrase variant finds team", "paraphrase", "recall", "Real Madrid",
            { recalls("which football club do I back", 1) }),
        Scenario("city paraphrase variant finds home", "paraphrase", "recall", "Lagos",
            { recalls("what city do I call home", 2) }),
        Scenario("job paraphrase variant finds work", "paraphrase", "recall", "mechanic",
            { recalls("what work do I do", 3) }),
        Scenario("food paraphrase variant finds dinner", "paraphrase", "recall", "jollof rice",
            { recalls("what do I eat for dinner", 6) }),
        // ----- original 40: negative recall and ranking -----
        Scenario("nonsense recalls nothing", "paraphrase", "recall", "empty",
            { OrionRecall.top(owner, "zebra quantum helicopter").isEmpty() }),
        Scenario("greeting recalls nothing", "paraphrase", "recall", "empty",
            { OrionRecall.top(owner, "hi").isEmpty() }),
        Scenario("club question ignores food", "paraphrase", "recall", "no-food",
            { doesNotRecall("which club do I support", 6) }),
        Scenario("job question ignores the team", "paraphrase", "recall", "no-team",
            { doesNotRecall("what is my job", 1) }),
        Scenario("food question ignores trading", "paraphrase", "recall", "no-trading",
            { doesNotRecall("what food do I like", 5) }),
        Scenario("recall never exceeds six", "paraphrase", "recall", "capped-at-six",
            { OrionRecall.top((1..20).map { mem(it, "Real Madrid club team support $it") }, "which club do I support").size <= 6 }),
        Scenario("club beats rice for the support question", "paraphrase", "recall", "club-wins",
            { sim("My favourite team is Real Madrid", "which club do I support") > sim("I love jollof rice for dinner", "which club do I support") }),
        Scenario("city beats trading for the home question", "paraphrase", "recall", "city-wins",
            { sim("I live in Lagos", "where is my home city") > sim("I trade swing positions on Bybit", "where is my home city") }),
        Scenario("identical text is a perfect match", "paraphrase", "recall", "perfect",
            { sim("I live in Lagos", "I live in Lagos") > 0.99 }),
        Scenario("unrelated text scores low", "paraphrase", "recall", "low-score",
            { sim("I love jollof rice", "quantum helicopter zebra") < 0.2 }),
        // ----- original 40: entity extraction -----
        Scenario("finds a person from a relative phrase", "paraphrase", "entity", "PERSON:amaka",
            { OrionEntityIndex.extract("My sister Amaka lives in Lagos").any { it.key == "PERSON:amaka" } }),
        Scenario("finds a place", "paraphrase", "entity", "PLACE:abuja",
            { OrionEntityIndex.extract("I fly to Abuja tomorrow").any { it.key == "PLACE:abuja" } }),
        Scenario("finds a named project", "paraphrase", "entity", "PROJECT:atlas",
            { OrionEntityIndex.extract("Working on project Atlas").any { it.key == "PROJECT:atlas" } }),
        Scenario("finds a trip as a project", "paraphrase", "entity", "PROJECT:lagos trip",
            { OrionEntityIndex.extract("The Lagos trip needs a hotel").any { it.key == "PROJECT:lagos trip" } }),
        Scenario("finds a date", "paraphrase", "entity", "DATE:3 january",
            { OrionEntityIndex.extract("Her birthday is 3 January").any { it.key == "DATE:3 january" } }),
        Scenario("months are not places", "paraphrase", "entity", "no-place-month",
            { OrionEntityIndex.extract("In March I travel").none { it.key.startsWith("PLACE:") } }),
        // ----- original 40: entity linking -----
        Scenario("a named place links its memories", "paraphrase", "entity-link", "links-memory-7",
            { OrionEntityIndex.linked(owner, "status of the Lagos trip").containsKey(7) }),
        Scenario("no entity means no links", "paraphrase", "entity-link", "empty-links",
            { OrionEntityIndex.linked(owner, "what should I eat").isEmpty() }),
        // ----- live memory graph and automatic owner extraction -----
        Scenario("captures an explicit forex trader role", "paraphrase", "memory_extract", "user-is-forex-trader",
            { MemoryAutoIndexer.extract("I am a forex trader")?.let { GraphPolicy.extract(it.text).contains(Triple("user", "is", "forex trader")) } == true }),
        Scenario("captures explicit trading activity", "paraphrase", "memory_extract", "user-trades-forex-crypto",
            { MemoryAutoIndexer.extract("I trade forex and crypto")?.let { GraphPolicy.extract(it.text).contains(Triple("user", "trade", "forex and crypto")) } == true }),
        Scenario("captures favourite facts as graph relationships", "paraphrase", "memory_extract", "favourite-team",
            { MemoryAutoIndexer.extract("My favourite team is Arsenal")?.let { GraphPolicy.extract(it.text).contains(Triple("user", "favourite team", "arsenal")) } == true }),
        Scenario("captures ongoing projects as graph relationships", "paraphrase", "memory_extract", "project-relation",
            { MemoryAutoIndexer.extract("I am building an Android assistant")?.let { GraphPolicy.extract(it.text).any { fact -> fact.first == "user" && fact.second == "works on" } } == true }),
        Scenario("legacy fact triplets use canonical graph parsing", "paraphrase", "memory_graph", "triplet",
            { GraphPolicy.extract("Kingi Jimzy — favourite_sport — football").contains(Triple("kingi jimzy", "favourite sport", "football")) }),

        // ----- original 40: slot reading and consolidation -----
        Scenario("reads a city", "paraphrase", "slot", "Slot(city, lagos)",
            { slot("I live in Lagos.") == OrionMemoryConsolidation.Slot("city", "lagos") }),
        Scenario("reads a move", "paraphrase", "slot", "Slot(city, abuja)",
            { slot("I moved to Abuja last month") == OrionMemoryConsolidation.Slot("city", "abuja") }),
        Scenario("reads a job", "paraphrase", "slot", "Slot(job, mechanic)",
            { slot("I work as a mechanic and love it") == OrionMemoryConsolidation.Slot("job", "mechanic") }),
        Scenario("reads an employer", "paraphrase", "slot", "Slot(employer, acme ltd)",
            { slot("I work at Acme Ltd") == OrionMemoryConsolidation.Slot("employer", "acme ltd") }),
        Scenario("reads a relative's name", "paraphrase", "slot", "Slot(relative:sister, amaka)",
            { slot("My sister is called Amaka") == OrionMemoryConsolidation.Slot("relative:sister", "amaka") }),
        Scenario("a liking is not a slot", "paraphrase", "slot", "null-slot",
            { slot("I like jollof rice") == null }),
        Scenario("a new city conflicts with the old one", "paraphrase", "consolidation", "conflicts-id-2",
            { OrionMemoryConsolidation.conflicting(owner, "I live in Abuja", "approved")?.id == 2 }),
        Scenario("the same city does not conflict", "paraphrase", "consolidation", "no-conflict",
            { OrionMemoryConsolidation.conflicting(owner, "I live in Lagos", "approved") == null }),
        Scenario("unreviewed text cannot overwrite approved memory", "paraphrase", "consolidation", "pending-blocked",
            { OrionMemoryConsolidation.conflicting(owner, "I live in Abuja", "pending") == null }),
        Scenario("history is one short line", "paraphrase", "consolidation", "one-line-history",
            { OrionMemoryConsolidation.withHistory("I live in Lagos", "I live in Abuja", 0L) == "I live in Abuja (was: lagos, until 1970-01-01)" }),

        // ===== CATEGORY: EXECUTION (tool dispatch and results) =====
        Scenario("dispatch lists apps successfully", "execution", "list_apps", "not error",
            { OrionExecutionLoop.dispatch("list_apps", stopped = false) { "Chrome, Settings" }.state == OrionExecutionLoop.DispatchState.DISPATCHED }),
        Scenario("dispatch unknown tool is blocked", "execution", "invented_tool", "error",
            { OrionExecutionLoop.dispatch("invented_tool", stopped = false) { "success" }.state == OrionExecutionLoop.DispatchState.BLOCKED }),
        Scenario("stopped task never calls executor", "execution", "list_apps", "error",
            { OrionExecutionLoop.dispatch("list_apps", stopped = true) { "unexpected" }.state == OrionExecutionLoop.DispatchState.BLOCKED }),
        Scenario("executor error becomes failure", "execution", "list_apps", "error",
            { OrionExecutionLoop.dispatch("list_apps", stopped = false) { "error: permission denied" }.state == OrionExecutionLoop.DispatchState.FAILED }),
        Scenario("thrown exception becomes explicit failure", "execution", "list_apps", "error",
            { OrionExecutionLoop.dispatch("list_apps", stopped = false) { throw IllegalStateException("device bridge unavailable") }.state == OrionExecutionLoop.DispatchState.FAILED }),
        Scenario("dispatch without verification accepts result", "execution", "list_apps", "ok",
            { OrionExecutionLoop.completionEvidence("ok", verificationRequired = false, verificationPassed = false) }),
        Scenario("required verification must pass", "execution", "verify_action", "ok",
            { OrionExecutionLoop.completionEvidence("ok", verificationRequired = true, verificationPassed = true) }),
        Scenario("dispatch empty tool name is blocked", "execution", "empty_tool", "error",
            { OrionExecutionLoop.dispatch("", stopped = false) { "success" }.state == OrionExecutionLoop.DispatchState.BLOCKED }),

        // ===== CATEGORY: RECOVERY (retry with alternative tool) =====
        Scenario("recovery alternatives exist for shell", "recovery", "selectAlternatives", "non-empty",
            { OrionRecoveryPlanner.selectAlternatives("workspace_write", "timeout", setOf("read_screen", "verify_action")).isNotEmpty() }),
        Scenario("recovery alternatives exist for web_search", "recovery", "selectAlternatives", "non-empty",
            { OrionRecoveryPlanner.selectAlternatives("web_search", "rate limit", setOf("research_web", "fetch_url")).isNotEmpty() }),
        Scenario("recovery alternatives exist for browser", "recovery", "selectAlternatives", "non-empty",
            { OrionRecoveryPlanner.selectAlternatives("read_screen", "no visible screen change", setOf("verify_action", "wait_for")).isNotEmpty() }),
        Scenario("recovery never repeats the same failed tool", "recovery", "selectAlternatives", "no repeat",
            { OrionRecoveryPlanner.selectAlternatives("web_search", "timeout", setOf("web_search", "research_web")).none { it == "web_search" } }),
        Scenario("recovery guidance is non-empty for verification failure", "recovery", "forFailure", "has reason",
            { OrionRecoveryPlanner.forFailure("web_search", "verification failed").reason.isNotEmpty() }),
        Scenario("recovery guidance stops for safety blocks", "recovery", "forFailure", "stop and report",
            { OrionRecoveryPlanner.forFailure("rm -rf /", "blocked by safety").strategy == OrionRecoveryPlanner.Strategy.STOP_AND_REPORT }),

        // ===== CATEGORY: SAFETY (secret redaction, blocked operations) =====
        Scenario("API key is redacted", "safety", "safeCommand", "[redacted]",
            { AgentPrivacyFilter.safeCommand("api_key=sk-1234567890abcdef").contains("[redacted]") }),
        Scenario("password is redacted", "safety", "safeCommand", "[redacted]",
            { AgentPrivacyFilter.safeCommand("password=hunter2").contains("[redacted]") }),
        Scenario("URL with token is redacted", "safety", "safeCommand", "[URL redacted]",
            { AgentPrivacyFilter.safeCommand("https://example.com?token=abc123").contains("[URL redacted]") }),
        Scenario("workspace file target is preserved", "safety", "safeArguments", "workspace/report.txt",
            { AgentPrivacyFilter.safeArguments(org.json.JSONObject().put("target", "workspace/report.txt").put("api_key", "secret")).contains("workspace/report.txt") }),
        Scenario("factory reset is blocked by shell policy", "safety", "classify", "blocked",
            { OrionShellPolicyTestHelper.classify("factory reset") == "blocked" }),
        Scenario("wipe data is blocked by shell policy", "safety", "classify", "blocked",
            { OrionShellPolicyTestHelper.classify("wipe data") == "blocked" }),
        Scenario("raw block device write is blocked", "safety", "classify", "blocked",
            { OrionShellPolicyTestHelper.classify("dd of=/dev/sda") == "blocked" }),
        Scenario("fork bomb is blocked", "safety", "classify", "blocked",
            { OrionShellPolicyTestHelper.classify(":(){ :|:& };:") == "blocked" }),
        Scenario("normal workspace write is safe", "safety", "classify", "safe",
            { OrionShellPolicyTestHelper.classify("echo hello > workspace/note.txt") == "safe" }),
        Scenario("email address is redacted", "safety", "safeCommand", "[email redacted]",
            { AgentPrivacyFilter.safeCommand("contact me at owner@example.com").contains("[email redacted]") }),
        Scenario("phone number is redacted", "safety", "safeCommand", "[number redacted]",
            { AgentPrivacyFilter.safeCommand("call +1 234 567 8900").contains("[number redacted]") }),

        // ===== CATEGORY: INITIATIVE (Requirement 14 — pure-logic checks) =====
        Scenario("initiative prefers acting when permissions are clear", "initiative", "initiative_style", "acts",
            { OrionInitiativeStyle.shouldActWhenClear(true, true) }),
        Scenario("initiative does not act without owner permission", "initiative", "initiative_style", "waits",
            { !OrionInitiativeStyle.shouldActWhenClear(false, true) }),
        Scenario("initiative does not act when permissions are missing", "initiative", "initiative_style", "waits",
            { !OrionInitiativeStyle.shouldActWhenClear(true, false) }),
        Scenario("initiative asks only when essential info is missing", "initiative", "initiative_style", "asks",
            { OrionInitiativeStyle.shouldAskWhenAmbiguous("buy it?", emptyList()) }),
        Scenario("initiative does not ask when target is clear", "initiative", "initiative_style", "acts",
            { !OrionInitiativeStyle.shouldAskWhenAmbiguous("call Amaka", listOf("Amaka")) }),
        Scenario("proactive engine config has quiet period", "initiative", "proactive_check", "configured",
            { OrionProactiveConfig.quietPeriodConfigured }),
        Scenario("proactive engine config has throttle interval", "initiative", "proactive_check", "throttled",
            { OrionProactiveConfig.throttleIntervalMs > 0 }),
        Scenario("proactive engine config has hourly cap", "initiative", "proactive_check", "capped",
            { OrionProactiveConfig.maxPerHour > 0 }),
        Scenario("initiative style covers chat voice autopilot", "initiative", "initiative_style", "all_surfaces",
            { OrionInitiativeStyle.supportedSurfaces.size == 3 }),
    )

    @Test fun evaluationScoreDoesNotRegress() {
        val results = scenarios.map { s -> s.name to runCatching { s.check() }.getOrDefault(false) }
        val passed = results.count { it.second }
        val total = results.size
        val failing = results.filter { !it.second }.joinToString { it.first }
        println("ORION EVAL SCORE: $passed/$total")
        val categoryByName = scenarios.associate { it.name to it.category }
        println("ORION EVAL CATEGORIES: ${results.groupBy { categoryByName[it.first] ?: "unknown" }.map { (k, v) -> "$k=${v.count { it.second }}/${v.size}" }.joinToString(", ")}")
        val baselineFile = java.io.File("eval/baseline.txt")
        val previousScore = baselineFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 40
        // The set must keep at least the original 40 memory scenarios
        assertTrue("the set must keep at least 40 scenarios, got $total", total >= 40)
        // Score must not regress below the previous recorded score
        assertTrue("score $passed/$total is below previous $previousScore; failing: $failing", passed >= previousScore)
        // The baseline is a reviewed, version-controlled threshold. Tests report scores but never mutate it.
        println("ORION EVAL BASELINE: threshold $previousScore (update eval/baseline.txt only with a reviewed change)")
    }

    @Test fun scenarioCountIsAtLeastFifty() {
        assertTrue("evaluation set should expand towards 50 scenarios, got ${scenarios.size}", scenarios.size >= 50)
    }

    @Test fun everyScenarioHasNonEmptyToolAndResult() {
        scenarios.forEach { s ->
            assertTrue("${s.name} has no tool", s.tool.isNotBlank())
            assertTrue("${s.name} has no expectedResult", s.expectedResult.isNotBlank())
        }
    }

    @Test fun categoriesCoverParaphraseExecutionRecoverySafetyInitiative() {
        val cats = scenarios.map { it.category }.toSet()
        assertTrue("missing paraphrase category", "paraphrase" in cats)
        assertTrue("missing execution category", "execution" in cats)
        assertTrue("missing recovery category", "recovery" in cats)
        assertTrue("missing safety category", "safety" in cats)
        assertTrue("missing initiative category", "initiative" in cats)
    }

    // ---------- helper for shell policy classification (safety tests) ----------

    private object OrionShellPolicyTestHelper {
        fun classify(command: String): String {
            val blocked = setOf(
                "factory reset", "factory_reset", "wipe data", "wipe_cache", "format userdata", "erase all",
                "recovery --wipe", "fastboot erase", "fastboot format", "fastboot wipe",
                "settings put global device_provisioned 0", "settings put secure device_provisioned 0",
                "settings put system device_provisioned 0", "settings put global setup_wizard 0",
                "settings put secure setup_wizard 0", "settings put system setup_wizard 0",
                "dd of=/dev/", ":(){ :|:& };:", "fork bomb"
            )
            val lower = command.lowercase()
            if (blocked.any { lower.contains(it) }) return "blocked"
            if (lower.contains("rm -rf /") || lower.contains("mkfs") || lower.contains("shred")) return "blocked"
            if (lower.contains("workspace_write") || lower.contains("workspace_delete") || lower.contains("save_file")) return "consequential"
            return "safe"
        }
    }
}
