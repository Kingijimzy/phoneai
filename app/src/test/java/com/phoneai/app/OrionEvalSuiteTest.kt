package com.phoneai.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic evaluation set for Orion memory intelligence.
 * These checks cover local recall, meaning similarity, entity links and consolidation;
 * they are not an end-to-end model or physical-device acceptance test.
 */
class OrionEvalSuiteTest {
    private class Scenario(val name: String, val check: () -> Boolean)

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

    private val scenarios = listOf(
        Scenario("club paraphrase finds the team") { recalls("which club do I support", 1) },
        Scenario("team question finds the team") { recalls("which team do I follow", 1) },
        Scenario("home city finds where I live") { recalls("where is my home city", 2) },
        Scenario("where do I live finds the city") { recalls("where do I live", 2) },
        Scenario("job question finds my work") { recalls("what is my job", 3) },
        Scenario("birthday question finds my sister") { recalls("when is Amaka's birthday", 4) },
        Scenario("sister question finds the sister") { recalls("what is my sister called", 4) },
        Scenario("trading question finds Bybit") { recalls("what do I trade", 5) },
        Scenario("food question finds the dinner fact") { recalls("what food do I like", 6) },
        Scenario("trip status finds the Lagos plan") { recalls("what's the status of the Lagos trip", 7) },
        Scenario("wake-up question finds the routine") { recalls("what time do I wake up", 9) },
        Scenario("project question finds Atlas") { recalls("when does Atlas ship", 10) },
        Scenario("nonsense recalls nothing") { OrionRecall.top(owner, "zebra quantum helicopter").isEmpty() },
        Scenario("greeting recalls nothing") { OrionRecall.top(owner, "hi").isEmpty() },
        Scenario("club question ignores food") { doesNotRecall("which club do I support", 6) },
        Scenario("job question ignores the team") { doesNotRecall("what is my job", 1) },
        Scenario("food question ignores trading") { doesNotRecall("what food do I like", 5) },
        Scenario("recall never exceeds six") { OrionRecall.top((1..20).map { mem(it, "Real Madrid club team support $it") }, "which club do I support").size <= 6 },
        Scenario("club beats rice for the support question") { sim("My favourite team is Real Madrid", "which club do I support") > sim("I love jollof rice for dinner", "which club do I support") },
        Scenario("city beats trading for the home question") { sim("I live in Lagos", "where is my home city") > sim("I trade swing positions on Bybit", "where is my home city") },
        Scenario("identical text is a perfect match") { sim("I live in Lagos", "I live in Lagos") > 0.99 },
        Scenario("unrelated text scores low") { sim("I love jollof rice", "quantum helicopter zebra") < 0.2 },
        Scenario("finds a person from a relative phrase") { OrionEntityIndex.extract("My sister Amaka lives in Lagos").any { it.key == "PERSON:amaka" } },
        Scenario("finds a place") { OrionEntityIndex.extract("I fly to Abuja tomorrow").any { it.key == "PLACE:abuja" } },
        Scenario("finds a named project") { OrionEntityIndex.extract("Working on project Atlas").any { it.key == "PROJECT:atlas" } },
        Scenario("finds a trip as a project") { OrionEntityIndex.extract("The Lagos trip needs a hotel").any { it.key == "PROJECT:lagos trip" } },
        Scenario("finds a date") { OrionEntityIndex.extract("Her birthday is 3 January").any { it.key == "DATE:3 january" } },
        Scenario("months are not places") { OrionEntityIndex.extract("In March I travel").none { it.key.startsWith("PLACE:") } },
        Scenario("a named place links its memories") { OrionEntityIndex.linked(owner, "status of the Lagos trip").containsKey(7) },
        Scenario("no entity means no links") { OrionEntityIndex.linked(owner, "what should I eat").isEmpty() },
        Scenario("reads a city") { slot("I live in Lagos.") == OrionMemoryConsolidation.Slot("city", "lagos") },
        Scenario("reads a move") { slot("I moved to Abuja last month") == OrionMemoryConsolidation.Slot("city", "abuja") },
        Scenario("reads a job") { slot("I work as a mechanic and love it") == OrionMemoryConsolidation.Slot("job", "mechanic") },
        Scenario("reads an employer") { slot("I work at Acme Ltd") == OrionMemoryConsolidation.Slot("employer", "acme ltd") },
        Scenario("reads a relative's name") { slot("My sister is called Amaka") == OrionMemoryConsolidation.Slot("relative:sister", "amaka") },
        Scenario("a liking is not a slot") { slot("I like jollof rice") == null },
        Scenario("a new city conflicts with the old one") { OrionMemoryConsolidation.conflicting(owner, "I live in Abuja", "approved")?.id == 2 },
        Scenario("the same city does not conflict") { OrionMemoryConsolidation.conflicting(owner, "I live in Lagos", "approved") == null },
        Scenario("unreviewed text cannot overwrite approved memory") { OrionMemoryConsolidation.conflicting(owner, "I live in Abuja", "pending") == null },
        Scenario("history is one short line") { OrionMemoryConsolidation.withHistory("I live in Lagos", "I live in Abuja", 0L) == "I live in Abuja (was: lagos, until 1970-01-01)" }
    )

    @Test fun evaluationScoreDoesNotRegress() {
        val results = scenarios.map { s -> s.name to runCatching { s.check() }.getOrDefault(false) }
        val passed = results.count { it.second }
        val failing = results.filter { !it.second }.joinToString { it.first }
        println("ORION EVAL SCORE: $passed/${results.size}")
        val baseline = java.io.File("../eval/baseline.txt").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: results.size
        assertTrue("the set must keep 40 scenarios", results.size == 40)
        assertTrue("score $passed/${results.size} is below baseline $baseline; failing: $failing", passed >= baseline)
    }
}
