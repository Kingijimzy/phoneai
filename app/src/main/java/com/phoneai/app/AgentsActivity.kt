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

/** The team: each specialist has its own personality, notes it has learned, and chat history. */
class AgentsActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true }
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(28)) }
        scroll.addView(host, -1, -2)
        setContentView(scroll)
        host.addView(TextView(this).apply { text = "Agents"; textSize = 28f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
        host.addView(TextView(this).apply {
            text = "Specialists with their own personality. They learn how you like to work from your messages, and Orion can hand tasks to them."
            textSize = 13f; setTextColor(Theme.MUTED); setPadding(0, dp(6), 0, dp(14))
        })
        AgentProfiles.all.forEachIndexed { index, agent ->
            val learned = AgentLessons.list(this, agent.id).size
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Theme.LINE) }
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@AgentsActivity, AgentChatActivity::class.java).putExtra(AgentChatActivity.EXTRA_AGENT, agent.id))
                }
            }
            card.addView(TextView(this).apply { text = agent.name; textSize = 17f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply { text = agent.tagline; textSize = 13f; setTextColor(Theme.MUTED); setPadding(0, dp(3), 0, 0) })
            card.addView(TextView(this).apply {
                text = (if (agent.acceptsImages) "Accepts chart images · " else "") + (if (learned == 0) "nothing learned yet" else "$learned things learned about you")
                textSize = 12f; setTextColor(Theme.CYAN); setPadding(0, dp(8), 0, 0)
            })
            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            Motion.enter(card, index)
            Motion.pressFeedback(card)
        }
    }
}
