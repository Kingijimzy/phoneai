package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Owner-facing notification centre.
 *
 * Reads only the bounded in-memory list that [OrionNotificationListener] already admitted under
 * [OrionNotificationContentPolicy]. Credential, financial and security notifications are never
 * captured by the listener, so they can never reach this screen. Nothing here is persisted.
 */
class NotificationCenterActivity : Activity() {

    private lateinit var page: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var banner: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var empty: TextView

    private var query = ""
    private var appFilter: String? = null
    private val expanded = LinkedHashSet<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun txt(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun fill(color: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun pill(caption: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = caption
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Theme.ON_ACCENT else Theme.TEXT_SECONDARY)
            background = GradientDrawable().apply {
                setColor(if (selected) Theme.SILVER else Theme.PANEL)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), if (selected) Theme.SILVER else Theme.LINE)
            }
            setPadding(dp(13), dp(7), dp(13), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun smallAction(caption: String, tint: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = caption
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tint)
            background = fill(Theme.PANEL2, Theme.LINE_STRONG, 11)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        titles.addView(txt("ORION  /  NOTIFICATION CENTRE", 10f, Theme.MUTED, true))
        titles.addView(txt("Notifications", 27f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        subtitle = txt("Memory-only list, held on this device", 12f, Theme.MUTED)
        titles.addView(subtitle)
        top.addView(titles)
        top.addView(smallAction("Refresh", Theme.TEXT) { render() })
        root.addView(top)

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(banner)

        val search = EditText(this).apply {
            hint = "Search app, title or message"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = fill(Theme.PANEL, Theme.LINE, 12)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ ->
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                render()
            }
        })
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(11), 0, dp(2))
        }
        chipScroll.addView(chips)
        root.addView(chipScroll)

        empty = txt("", 13f, Theme.MUTED).apply {
            setPadding(dp(4), dp(26), dp(4), dp(4))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        root.addView(empty)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun allItems(): List<OrionNotificationListener.SafeNotification> {
        return try {
            OrionNotificationListener.recentNotifications()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun visibleItems(all: List<OrionNotificationListener.SafeNotification>): List<OrionNotificationListener.SafeNotification> {
        val filter = appFilter
        return all.filter { item ->
            val matchesApp = filter == null || item.app == filter
            val matchesQuery = query.isEmpty() ||
                item.app.contains(query, true) ||
                item.title.contains(query, true) ||
                item.text.contains(query, true)
            matchesApp && matchesQuery
        }
    }

    private fun render() {
        val all = allItems()
        renderBanner()
        renderChips(all)

        page.removeAllViews()

        val items = visibleItems(all)
        subtitle.text = when {
            all.isEmpty() -> "Memory-only list, held on this device"
            else -> "${all.size} recent notification${if (all.size == 1) "" else "s"} held in memory"
        }

        if (all.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "Nothing here yet.\n\nNotifications appear once Orion's notification access is enabled and something arrives."
            return
        }

        if (items.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = "No notification matches that filter."
            return
        }
        empty.visibility = View.GONE

        var lastDay = ""
        for (item in items) {
            val day = dayLabel(item.whenMillis)
            if (day != lastDay) {
                lastDay = day
                page.addView(sectionHeader(day, items.count { dayLabel(it.whenMillis) == day }))
            }
            page.addView(card(item))
        }

        page.addView(txt(
            "Credential, banking and security notifications are excluded before they reach this list. Nothing on this screen is saved to storage.",
            11f,
            Theme.MUTED
        ).apply { setPadding(dp(4), dp(14), dp(4), dp(4)) })
    }

    private fun sectionHeader(label: String, count: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(7))
        }
        row.addView(txt(label, 10f, Theme.SILVER, true))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(txt(count.toString(), 10f, Theme.MUTED, true))
        return row
    }

    private fun renderBanner() {
        banner.removeAllViews()
        val connected = try {
            OrionNotificationListener.isConnected()
        } catch (e: Throwable) {
            false
        }
        if (connected) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        banner.background = fill(Theme.PANEL, Theme.WARN, 14)
        banner.setPadding(dp(14), dp(12), dp(14), dp(12))
        banner.addView(txt("NOTIFICATION ACCESS IS OFF", 10f, Theme.WARN, true))
        banner.addView(txt(
            "Orion cannot see notifications until Android grants notification access. This is an Android permission, not an Orion setting.",
            12f,
            Theme.TEXT_SECONDARY
        ).apply { setPadding(0, dp(6), 0, dp(10)) })
        banner.addView(smallAction("Open Android notification access", Theme.TEXT) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open notification access settings", Toast.LENGTH_SHORT).show()
            }
        })
        banner.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun renderChips(all: List<OrionNotificationListener.SafeNotification>) {
        chips.removeAllViews()
        if (all.isEmpty()) return
        chips.addView(pill("All", appFilter == null) {
            appFilter = null
            render()
        })
        val counts = LinkedHashMap<String, Int>()
        for (item in all) {
            counts[item.app] = (counts[item.app] ?: 0) + 1
        }
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(8)
            .forEach { entry ->
                val name = entry.key
                chips.addView(pill(
                    if (name.length > 14) name.take(13) + "..." else name,
                    appFilter == name
                ) {
                    appFilter = if (appFilter == name) null else name
                    render()
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7) })
            }
    }

    private fun card(item: OrionNotificationListener.SafeNotification): View {
        val isOpen = expanded.contains(item.key)
        val active = try {
            OrionNotificationListener.isActive(item.key)
        } catch (e: Throwable) {
            false
        }

        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fill(Theme.PANEL, if (isOpen) Theme.LINE_STRONG else Theme.LINE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (active) Theme.SILVER else Theme.LINE_STRONG)
            }
        }
        head.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)))
        head.addView(txt(item.app, 11f, Theme.SILVER, true).apply {
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(txt(clock(item.whenMillis), 11f, Theme.MUTED, false))
        holder.addView(head)

        if (item.title.isNotBlank()) {
            holder.addView(txt(item.title, 15f, Theme.TEXT, true).apply {
                setPadding(0, dp(8), 0, dp(3))
                maxLines = if (isOpen) 8 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (item.text.isNotBlank()) {
            holder.addView(txt(item.text, 13f, Theme.TEXT_SECONDARY).apply {
                maxLines = if (isOpen) 40 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }

        holder.isClickable = true
        holder.setOnClickListener {
            if (isOpen) expanded.remove(item.key) else expanded.add(item.key)
            render()
        }

        if (!isOpen) return holder

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
        }

        val replyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val replyField = EditText(this).apply {
            hint = "Write a reply"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = fill(Theme.PANEL2, Theme.LINE, 11)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        replyRow.addView(replyField)
        replyRow.addView(smallAction("Send", Theme.SILVER) {
            val body = replyField.text.toString().trim()
            if (body.isEmpty()) {
                Toast.makeText(this, "Reply is empty", Toast.LENGTH_SHORT).show()
                return@smallAction
            }
            val result = try {
                OrionNotificationListener.notificationAction(item.key, "reply", body)
            } catch (e: Throwable) {
                "error: reply failed (" + e.javaClass.simpleName + ")"
            }
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            if (result.startsWith("reply action sent")) {
                replyField.setText("")
                replyRow.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })

        val canReply = try {
            active && OrionNotificationListener.canReply(item.key)
        } catch (e: Throwable) {
            false
        }

        if (canReply) {
            actions.addView(smallAction("Reply", Theme.TEXT) {
                val show = replyRow.visibility != View.VISIBLE
                replyRow.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    replyField.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(replyField, InputMethodManager.SHOW_IMPLICIT)
                }
            })
        }
        if (active) {
            actions.addView(smallAction("Dismiss", Theme.TEXT) {
                val result = try {
                    OrionNotificationListener.notificationAction(item.key, "dismiss")
                } catch (e: Throwable) {
                    "error: dismiss failed (" + e.javaClass.simpleName + ")"
                }
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                expanded.remove(item.key)
                render()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        if (item.pkg.isNotBlank()) {
            actions.addView(smallAction("Open app", Theme.MUTED) {
                val launch = try {
                    packageManager.getLaunchIntentForPackage(item.pkg)
                } catch (e: Exception) {
                    null
                }
                if (launch == null) {
                    Toast.makeText(this, "No launchable screen for ${item.app}", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        startActivity(launch)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open ${item.app}", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }

        if (actions.childCount > 0) {
            holder.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            })
        }
        holder.addView(replyRow)

        if (!active) {
            holder.addView(txt("No longer in the status bar - shown from Orion's memory only.", 11f, Theme.MUTED)
                .apply { setPadding(0, dp(9), 0, 0) })
        }
        return holder
    }

    private fun clock(ms: Long): String {
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (e: Exception) {
            "--:--"
        }
    }

    private fun dayLabel(ms: Long): String {
        return try {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = ms }
            val todayKey = now.get(Calendar.YEAR).toString() + ":" + now.get(Calendar.DAY_OF_YEAR)
            val thenKey = then.get(Calendar.YEAR).toString() + ":" + then.get(Calendar.DAY_OF_YEAR)
            if (todayKey == thenKey) {
                "TODAY"
            } else {
                val yesterday = now.clone() as Calendar
                yesterday.add(Calendar.DAY_OF_YEAR, -1)
                val yKey = yesterday.get(Calendar.YEAR).toString() + ":" + yesterday.get(Calendar.DAY_OF_YEAR)
                if (yKey == thenKey) {
                    "YESTERDAY"
                } else {
                    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms)).uppercase(Locale.getDefault())
                }
            }
        } catch (e: Exception) {
            "EARLIER"
        }
    }
}
