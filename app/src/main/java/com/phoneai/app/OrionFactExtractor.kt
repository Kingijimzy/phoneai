package com.phoneai.app

/** Local, instant extraction of plain statements ("my name is…", "my favourite sport is…", "I live in…") into triplets. */
internal object OrionFactExtractor {
    private val favourite = Regex("(?i)\\bmy (?:favou?rite|fav) ([a-z ]{2,30}?) (?:is|are|was) ([^.!?\\n]{2,80})")
    private val myThing = Regex("(?i)\\bmy ([a-z ]{2,25}?) (?:is|are) (?:called |named )?([^.!?\\n]{2,60})")
    private val live = Regex("(?i)\\bi (?:live|stay|am based) in ([^.!?\\n]{2,60})")
    private val work = Regex("(?i)\\bi (?:work|am working) (?:at|for|as) ([^.!?\\n]{2,60})")
    private val like = Regex("(?i)\\bi (love|like|hate|prefer|enjoy) ([^.!?\\n]{2,60})")
    private val callMe = Regex("(?i)\\b(?:call me|my name is) ([A-Za-z][A-Za-z ]{1,30})")

    fun extract(text: String, owner: String): List<Triple<String, String, String>> {
        val out = linkedSetOf<Triple<String, String, String>>()
        val t = text.take(600)
        if (t.trimEnd().endsWith("?")) return emptyList()
        val who = owner.ifBlank { "Owner" }
        favourite.findAll(t).forEach { out += Triple(who, "favourite_" + it.groupValues[1].trim().replace(' ', '_'), clean(it.groupValues[2])) }
        callMe.find(t)?.let { out += Triple(who, "goes_by", clean(it.groupValues[1])) }
        live.find(t)?.let { out += Triple(who, "lives_in", clean(it.groupValues[1])) }
        work.find(t)?.let { out += Triple(who, "works", clean(it.groupValues[1])) }
        like.findAll(t).forEach { out += Triple(who, it.groupValues[1].lowercase(), clean(it.groupValues[2])) }
        if (out.isEmpty()) myThing.find(t)?.let { out += Triple(who, it.groupValues[1].trim().replace(' ', '_'), clean(it.groupValues[2])) }
        return out.filter { it.third.isNotBlank() }.take(6)
    }

    private fun clean(v: String) = v.trim().trimEnd(',', ';', ':').take(80)
}
