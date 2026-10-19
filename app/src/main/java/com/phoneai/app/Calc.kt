package com.phoneai.app

import java.math.BigDecimal
import java.math.MathContext

/** Instant maths and unit conversion. No AI, no internet, no limits. */
object Calc {

    private class P(val s: String) {
        var i = 0
        fun ws() {
            while (i < s.length && s[i] == ' ') i++
        }

        fun peek(): Char {
            ws()
            return if (i < s.length) s[i] else '\u0000'
        }

        fun expr(): Double {
            var v = term()
            while (true) {
                val c = peek()
                if (c == '+') {
                    i++
                    v += term()
                } else if (c == '-') {
                    i++
                    v -= term()
                } else return v
            }
        }

        fun term(): Double {
            var v = pow()
            while (true) {
                val c = peek()
                if (c == '*') {
                    i++
                    v *= pow()
                } else if (c == '/') {
                    i++
                    val d = pow()
                    if (d == 0.0) throw ArithmeticException("divide by zero")
                    v /= d
                } else if (c == '%') {
                    i++
                    val d = pow()
                    if (d == 0.0) throw ArithmeticException("divide by zero")
                    v %= d
                } else return v
            }
        }

        fun pow(): Double {
            val b = unary()
            return if (peek() == '^') {
                i++
                Math.pow(b, pow())
            } else b
        }

        fun unary(): Double {
            val c = peek()
            if (c == '-') {
                i++
                return -unary()
            }
            if (c == '+') {
                i++
                return unary()
            }
            return primary()
        }

        fun primary(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            if (s.startsWith("sqrt", i)) {
                i += 4
                if (peek() != '(') throw IllegalArgumentException("sqrt(")
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("missing )")
                i++
                return Math.sqrt(v)
            }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException("number expected")
            return s.substring(st, i).toDouble()
        }
    }

    private fun fmt(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "not a number"
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(8)).stripTrailingZeros().toPlainString()
    }

    private fun spoken(s: String) = s

    /** Returns a spoken answer if the whole sentence is a sum, otherwise null. */
    fun tryMath(t0: String): String? {
        var t = t0.lowercase().trim()
        t = t.replace(Regex("^(what('s| is)|whats|calculate|compute|work out|how much is|solve|tell me)\\s+"), "").trim()
        t = t.replace(Regex("\\s*(=|equals)\\s*\\??$"), "").trim()
        t = t.replace(",", "")
        // "15 percent of 200"
        val pc = Regex("^(\\d+(\\.\\d+)?)\\s*(percent|%)\\s*of\\s*(\\d+(\\.\\d+)?)$").find(t)
        if (pc != null) {
            val r = pc.groupValues[1].toDouble() / 100.0 * pc.groupValues[4].toDouble()
            return "${pc.groupValues[1]} percent of ${pc.groupValues[4]} is ${fmt(r)}."
        }
        val sq = Regex("^(the )?square root of (\\d+(\\.\\d+)?)$").find(t)
        if (sq != null) return "The square root of ${sq.groupValues[2]} is ${fmt(Math.sqrt(sq.groupValues[2].toDouble()))}."
        var e = " $t "
        val words = listOf(
            " plus " to " + ", " minus " to " - ", " times " to " * ", " multiplied by " to " * ",
            " divided by " to " / ", " over " to " / ", " to the power of " to " ^ ", " squared " to " ^2 ",
            " cubed " to " ^3 ", " mod " to " % ", " x " to " * "
        )
        for ((a, b) in words) e = e.replace(a, b)
        e = e.trim()
        if (!Regex("^[0-9+\\-*/^().%\\s]+$").matches(e.replace("sqrt", ""))) return null
        if (!Regex("\\d").containsMatchIn(e) || !Regex("[+\\-*/^%]").containsMatchIn(e.trimStart('-'))) return null
        return try {
            val p = P(e)
            val v = p.expr()
            if (p.peek() != '\u0000') null else "$t equals ${fmt(v)}."
        } catch (ex: ArithmeticException) {
            "You can't divide by zero."
        } catch (ex: Exception) {
            null
        }
    }

    private val length = mapOf(
        "m" to 1.0, "meter" to 1.0, "meters" to 1.0, "metre" to 1.0, "metres" to 1.0,
        "km" to 1000.0, "kilometer" to 1000.0, "kilometers" to 1000.0, "kilometre" to 1000.0, "kilometres" to 1000.0,
        "cm" to 0.01, "mm" to 0.001, "mile" to 1609.344, "miles" to 1609.344, "mi" to 1609.344,
        "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048, "inch" to 0.0254, "inches" to 0.0254, "in" to 0.0254,
        "yard" to 0.9144, "yards" to 0.9144
    )
    private val mass = mapOf(
        "kg" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0, "kilo" to 1.0, "kilos" to 1.0,
        "g" to 0.001, "gram" to 0.001, "grams" to 0.001, "lb" to 0.45359237, "lbs" to 0.45359237,
        "pound" to 0.45359237, "pounds" to 0.45359237, "oz" to 0.0283495, "ounce" to 0.0283495, "ounces" to 0.0283495
    )
    private val volume = mapOf(
        "l" to 1.0, "liter" to 1.0, "liters" to 1.0, "litre" to 1.0, "litres" to 1.0, "ml" to 0.001,
        "gal" to 3.78541, "gallon" to 3.78541, "gallons" to 3.78541
    )

    fun tryConvert(t0: String): String? {
        val t = t0.lowercase().trim().replace("degrees ", "").replace("°", "")
        val m = Regex("^(convert |how much is )?(-?\\d+(\\.\\d+)?)\\s*([a-z]+)\\s+(to|in|into)\\s+([a-z]+)$").find(t) ?: return null
        val v = m.groupValues[2].toDouble()
        val a = m.groupValues[4]
        val b = m.groupValues[6]
        val temp = setOf("c", "celsius", "f", "fahrenheit")
        if (a in temp && b in temp) {
            val aC = a.startsWith("c")
            val bC = b.startsWith("c")
            if (aC == bC) return "${fmt(v)} degrees is the same."
            val r = if (aC) v * 9 / 5 + 32 else (v - 32) * 5 / 9
            return "${fmt(v)} degrees ${if (aC) "Celsius" else "Fahrenheit"} is ${fmt(r)} degrees ${if (bC) "Celsius" else "Fahrenheit"}."
        }
        for (table in listOf(length, mass, volume)) {
            val fa = table[a]
            val fb = table[b]
            if (fa != null && fb != null) return "${fmt(v)} $a is ${fmt(v * fa / fb)} $b."
        }
        return null
    }
}
