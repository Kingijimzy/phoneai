package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Specialist directory: compact, consistent with Orion's graphite/silver design system. */
class AgentsActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun surface(color: Int, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Theme.LINE)
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        scroll.addView(host, ScrollView.LayoutParams(-1, -2))
        setContentView(scroll)

        val back = label("‹  ORION", 11f, Theme.SILVER, true).apply {
            letterSpacing = .08f
            setPadding(0, dp(2), 0, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        host.addView(back)

        host.addView(label("Agent workspace", 27f, Theme.TEXT, true))
        host.addView(label(
            "Specialist agents share Orion's core capabilities while keeping their own focus, lessons and conversations.",
            13f, Theme.MUTED
        ).apply {
            setPadding(0, dp(6), 0, dp(14))
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        val agents = AgentProfiles.all
        val learnedTotal = agents.sumOf { AgentLessons.list(this, it.id).size }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface(Theme.PANEL, 12)
        }
        val summaryText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        summaryText.addView(label("${agents.size} SPECIALISTS", 11f, Theme.SILVER, true))
        summaryText.addView(label("$learnedTotal saved learning notes across your agents", 11f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        summary.addView(summaryText, LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(label("OPEN AGENT  ›", 9f, Theme.SILVER, true))
        host.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(15) })

        agents.forEachIndexed { index, agent ->
            val learned = AgentLessons.list(this, agent.id).size
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = surface(Theme.PANEL, 14)
                isClickable = true
                isFocusable = true
                contentDescription = "Open ${agent.name}"
                setOnClickListener {
                    startActivity(Intent(this@AgentsActivity, AgentChatActivity::class.java)
                        .putExtra(AgentChatActivity.EXTRA_AGENT, agent.id))
                }
            }

            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val indexLabel = label("%02d".format(index + 1), 10f, Theme.MUTED, true).apply {
                gravity = Gravity.CENTER
                background = surface(Theme.PANEL2, 8)
            }
            heading.addView(indexLabel, LinearLayout.LayoutParams(dp(32), dp(30)).apply { rightMargin = dp(11) })

            val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            identity.addView(label(agent.name, 15f, Theme.TEXT, true))
            identity.addView(label(agent.tagline, 11f, Theme.MUTED).apply {
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
            })
            heading.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("›", 23f, Theme.SILVER))
            card.addView(heading)

            val status = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(43), dp(9), 0, 0)
            }
            val details = buildList {
                if (agent.acceptsImages) add("IMAGE READY")
                add(if (learned == 0) "NO SAVED LESSONS" else "$learned LEARNED")
            }.joinToString("   ·   ")
            status.addView(label(details, 9f, Theme.SILVER, true))
            card.addView(status)

            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
            Motion.enter(card, index)
            Motion.pressFeedback(card)
        }
    }
}
