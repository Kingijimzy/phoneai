package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen conversation. The floating orb keeps its separate, compact command panel. */
class MainChatActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private lateinit var stop: TextView
    private var selectedImageUri: Uri? = null
    private var imagePreviewPanel: LinearLayout? = null
    private var attachedService: PhoneAiService? = null
    private var lastMode = OrbView.Mode.IDLE
    private var welcomePanel: View? = null
    private var promptRail: View? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun animateIn(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF02080A.toInt(), 0xFF071311.toInt(), 0xFF0A1715.toInt()))
            setPadding(dp(14), dp(10), dp(14), dp(9))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF0C3028.toInt(), 0xFF0A1916.toInt(), 0xFF0B241E.toInt())).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF016856.toInt())
            }
            elevation = dp(4).toFloat()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val mark = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF0B241E.toInt(), 18, 0xFF167C66.toInt())
            contentDescription = "Orion AI"
        }
        mark.addView(OrionIconView(this).apply {
            icon = "spark"; tint = Theme.CYAN; contentDescription = null
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(label("ORION", 18f, Theme.TEXT, true))
        titleCol.addView(label("PERSONAL AI COMPANION", 9f, Theme.MUTED, true).apply {
            letterSpacing = .08f
            setPadding(0, dp(1), 0, dp(3))
        })
        status = label("●  READY WHEN YOU ARE", 11f, Theme.CYAN, true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(0xFF082D26.toInt(), 12, 0xFF018D6D.toInt())
            contentDescription = "Orion state: ready"
        }
        titleCol.addView(status)
        titleCol.addView(label("Think clearly. Act with permission.", 11f, Theme.MUTED))
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val live = label("●  LIVE VOICE", 10f, 0xFFE7FFFA.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(13), dp(12), dp(13))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF087A61.toInt(), 0xFF064638.toInt())).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF20EAC2.toInt())
            }
            elevation = dp(3).toFloat()
            minHeight = dp(46)
            contentDescription = "Start live voice conversation with Orion"
            isFocusable = true
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                false
            }
            setOnClickListener {
                startActivity(Intent(this@MainChatActivity, LiveConversationActivity::class.java))
            }
        }
        header.addView(live, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
        stop = label("■", 13f, 0xFF15EAD3.toInt(), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Stop current Orion task"
            background = rounded(0xFF0B241E.toInt(), 14, 0xFF018D6D.toInt())
            setPadding(dp(11), dp(10), dp(11), dp(10))
            visibility = View.GONE
            setOnClickListener {
                PhoneAiService.instance?.stopCurrentTask()
                text = "…"
                isEnabled = false
                Toast.makeText(this@MainChatActivity, "Asked Orion to stop.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        val new = label("＋", 24f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Start a new conversation"
            setPadding(dp(10), dp(6), dp(8), dp(6))
            setOnClickListener {
                if (lastMode == OrbView.Mode.THINKING || lastMode == OrbView.Mode.ACTING) {
                    Toast.makeText(this@MainChatActivity, "Let Orion finish or stop the current task first.", Toast.LENGTH_SHORT).show()
                } else {
                    messages.removeAllViews()
                    restoreWelcome()
                    val service = PhoneAiService.instance
                    if (service != null) service.resetMainChat()
                    else addMessage("ai", "New conversation. What are we working on?")
                }
            }
        }
        header.addView(new)
        root.addView(header)
        animateIn(header, 30)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0C3028.toInt(), 0xFF0A1916.toInt(), 0xFF0B241E.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF2C504E.toInt())
            }
            elevation = dp(2).toFloat()
        }
        intro.addView(label("YOUR AI. YOUR FLOW.", 10f, Theme.CYAN, true).apply {
            letterSpacing = .13f
            gravity = Gravity.CENTER
        })
        intro.addView(label("What’s on your mind?", 24f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(2))
        })
        intro.addView(label("Think, create, research, or get things done — all with Orion.", 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val introFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, 0)
        }
        introFooter.addView(OrionIconView(this).apply {
            icon = "shield"; tint = Theme.OK; contentDescription = null
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) })
        introFooter.addView(label("Private by design · actions need your approval", 10f, Theme.MUTED))
        intro.addView(introFooter)
        root.addView(intro)
        welcomePanel = intro
        animateIn(intro, 90)

        scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(4), 0, dp(8))
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chipsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("Research", "browser", "Help me research a topic. Ask what topic I want to investigate."),
            Triple("Plan", "tasks", "Help me create a practical step-by-step plan. Ask what I want to achieve."),
            Triple("Explain", "spark", "Explain a difficult idea with a practical example."),
            Triple("Phone command", "shield", "Help me with my phone. Ask what action I want to perform."),
            Triple("Teach", "record", "teach ")
        ).forEach { (name, iconName, prompt) ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(13), dp(8))
                background = rounded(Theme.PANEL2, 18, Theme.LINE)
                isClickable = true
                isFocusable = true
                contentDescription = "$name prompt"
            }
            chip.addView(OrionIconView(this).apply {
                icon = iconName
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(6) })
            chip.addView(label(name, 12f, Theme.CYAN, true))
            chip.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                false
            }
            chip.setOnClickListener { input.setText(prompt); input.setSelection(input.text.length); input.requestFocus() }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        chipsScroll.addView(chips)
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
        promptRail = chipsScroll

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = rounded(0xFF0A1916.toInt(), 16, 0xFF2C504E.toInt())
        }
        imagePreviewPanel = previewPanel
        root.addView(previewPanel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF10211E.toInt(), 0xFF0A1413.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0xFF2C504E.toInt())
            }
            elevation = dp(5).toFloat()
            setPadding(dp(8), dp(6), dp(7), dp(6))
        }
        input = EditText(this).apply {
            hint = "Message Orion…"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 15f
            background = null
            minHeight = dp(44)
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val attach = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xFF0A1916.toInt(), 21, 0xFF2C504E.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Attach an image for Orion to analyse"
            addView(OrionIconView(this@MainChatActivity).apply {
                icon = "image"
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.92f).scaleY(.92f).setDuration(75).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                false
            }
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(picker, "Choose an image"), 44)
            }
        }
        composer.addView(attach, LinearLayout.LayoutParams(dp(42), dp(44)).apply { rightMargin = dp(6) })
        val mic = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(0xFF0A1916.toInt(), 22, 0xFF2C504E.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isClickable = true
            isFocusable = true
            contentDescription = "Speak to Orion"
            addView(OrionIconView(this@MainChatActivity).apply {
                icon = "mic"; tint = Theme.CYAN; contentDescription = null
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.92f).scaleY(.92f).setDuration(75).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                false
            }
            setOnClickListener {
                startActivity(Intent(this@MainChatActivity, LiveConversationActivity::class.java))
            }
        }
        composer.addView(mic, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(7) })
        send = label("↑", 23f, 0xFF02110D.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB9FFF0.toInt(), 0xFF00FCB6.toInt(), 0xFF0BB0A1.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFF72FFE7.toInt())
            }
            elevation = dp(5).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            contentDescription = "Send message"
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.90f).scaleY(.90f).translationY(dp(1).toFloat()).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(145).start()
                false
            }
            setOnClickListener { sendMessage() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(composer)
        root.addView(label("Orion only performs sensitive actions after asking you.", 10f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(1))
        })
        setContentView(root)
        addMessage("ai", "Hi. I’m Orion. We can talk naturally here; phone-control commands are handled with a separate permission-aware workflow.")
    }

    override fun onResume() {
        super.onResume()
        val service = PhoneAiService.instance
        if (service == null) {
            status.text = "ACCESSIBILITY SETUP NEEDED"
            status.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (attachedService !== service) {
            attachedService?.detachMainChat(this)
            attachedService = service
            messages.removeAllViews()
            service.attachMainChat(this)
        }
    }

    override fun onStop() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onStop()
    }

    override fun onDestroy() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            PhoneAiService.instance?.listenFromMainChat()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 44 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    val granted = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (granted != 0) contentResolver.takePersistableUriPermission(uri, granted)
                } catch (_: Exception) {
                    // Some photo providers grant only temporary access; the current selection can still be used.
                }
                selectedImageUri = uri
                refreshImagePreview(uri)
            }
        }
    }

    private fun decodeThumbnail(uri: Uri, maxPixels: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPixels || bounds.outHeight / sample > maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshImagePreview(uri: Uri?) {
        val panel = imagePreviewPanel ?: return
        panel.removeAllViews()
        if (uri == null) {
            panel.visibility = View.GONE
            return
        }
        val thumbnail = ImageView(this).apply {
            setImageBitmap(decodeThumbnail(uri, dp(72)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF10211E.toInt(), 10, 0xFF2C504E.toInt())
            clipToOutline = true
            contentDescription = "Selected image preview"
        }
        panel.addView(thumbnail, LinearLayout.LayoutParams(dp(54), dp(54)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(label("IMAGE ATTACHED", 10f, Theme.CYAN, true))
            addView(label("Orion will inspect this image", 12f, Theme.MUTED))
        }
        panel.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val remove = label("×", 23f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Remove attached image"
            setPadding(dp(10), dp(2), dp(5), dp(2))
            setOnClickListener {
                selectedImageUri = null
                refreshImagePreview(null)
            }
        }
        panel.addView(remove, LinearLayout.LayoutParams(dp(38), dp(48)))
        panel.visibility = View.VISIBLE
    }

    private fun addImageBubble(uri: Uri) {
        val image = ImageView(this).apply {
            setImageBitmap(decodeThumbnail(uri, dp(280)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            maxHeight = dp(230)
            background = rounded(0xFF0A1916.toInt(), 16, 0xFF016856.toInt())
            clipToOutline = true
            contentDescription = "Image sent to Orion"
        }
        val params = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.END
            setMargins(dp(38), dp(0), 0, dp(7))
        }
        messages.addView(image, params)
        image.alpha = 0f
        image.animate().alpha(1f).setDuration(180).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val value = input.text.toString().trim()
        val imageUri = selectedImageUri
        if (value.isEmpty() && imageUri == null) return
        val service = PhoneAiService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Orion in Accessibility settings first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        input.setText("")
        if (imageUri != null) {
            val question = value.ifBlank { "Describe this image and explain anything important I should notice." }
            addMessage("me", question)
            addImageBubble(imageUri)
            selectedImageUri = null
            refreshImagePreview(null)
            service.analyzeImageFromMainChat(imageUri, question)
        } else {
            service.sendFromMainChat(value)
        }
    }

    private fun restoreWelcome() {
        listOf(welcomePanel, promptRail).forEach { view ->
            view?.animate()?.cancel()
            view?.visibility = View.VISIBLE
            view?.alpha = 1f
            view?.translationY = 0f
        }
    }

    private fun addMessage(kind: String, text: String) {
        if (kind == "me") {
            listOf(welcomePanel, promptRail).forEach { view ->
                view?.let { panel ->
                    if (panel.visibility == View.VISIBLE) {
                        panel.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(160)
                            .withEndAction { panel.visibility = View.GONE }.start()
                    }
                }
            }
        }
        val isNote = kind == "note"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12), dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12))
            background = when {
                isNote -> rounded(0xFF082D26.toInt(), 15, 0xFF018D6D.toInt())
                kind == "me" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF0E4035.toInt(), 0xFF0C3028.toInt())).apply {
                    cornerRadius = dp(21).toFloat()
                    setStroke(dp(1), 0xFF016856.toInt())
                }
                else -> rounded(0xFF0A1916.toInt(), 19, 0xFF2C504E.toInt())
            }
            elevation = if (isNote) 0f else dp(1).toFloat()
        }
        if (isNote) {
            row.addView(label("LIVE ACTIVITY", 9f, Theme.CYAN, true).apply {
                letterSpacing = .09f
                setPadding(0, 0, 0, dp(3))
            })
        } else if (kind == "ai") {
            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4))
            }
            identity.addView(OrionIconView(this).apply {
                icon = "spark"
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(13), dp(13)).apply { rightMargin = dp(6) })
            identity.addView(label("ORION", 10f, Theme.CYAN, true).apply { letterSpacing = .12f })
            row.addView(identity)
        }
        val body = label(text, if (isNote) 12f else 15f, if (isNote) Theme.MUTED else Theme.TEXT).apply {
            setLineSpacing(dp(3).toFloat(), 1.035f)
            setTextIsSelectable(true)
            if (isNote) maxLines = 4
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        row.addView(body)
        val params = LinearLayout.LayoutParams(
            if (isNote) -1 else if (kind == "me") (resources.displayMetrics.widthPixels * .84f).toInt() else -1, -2
        )
        params.gravity = if (kind == "me") Gravity.END else Gravity.START
        params.setMargins(if (kind == "me") dp(36) else 0, dp(if (isNote) 2 else 5), if (kind == "me") 0 else dp(if (isNote) 0 else 18), dp(if (isNote) 3 else 5))
        messages.addView(row, params)
        row.alpha = 0f
        row.translationY = dp(8).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(180).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onMessage(kind: String, text: String) {
        runOnUiThread { addMessage(kind, text) }
    }

    override fun onStatus(text: String, mode: OrbView.Mode) {
        runOnUiThread {
            val changed = lastMode != mode
            lastMode = mode
            val state: Triple<String, Int, Pair<Int, Int>> = when (mode) {
                OrbView.Mode.IDLE -> Triple("READY", Theme.CYAN, Pair(0xFF063B30.toInt(), 0xFF018D6D.toInt()))
                OrbView.Mode.LISTENING -> Triple("LISTENING · SPEAK NOW", Theme.OK, Pair(0xFF063B30.toInt(), 0xFF018D6D.toInt()))
                OrbView.Mode.THINKING -> Triple("THINKING · WORKING IT OUT", Theme.VIOLET, Pair(0xFF0C3028.toInt(), 0xFF016856.toInt()))
                OrbView.Mode.SPEAKING -> Triple("SPEAKING · ORION IS REPLYING", Theme.CYAN, Pair(0xFF063B30.toInt(), 0xFF018D6D.toInt()))
                OrbView.Mode.ACTING -> Triple("ACTING · CARRYING OUT TASK", Theme.WARN, Pair(0xFF29251A.toInt(), 0xFF74642F.toInt()))
                OrbView.Mode.WAITING_APPROVAL -> Triple("APPROVAL NEEDED · YOU DECIDE", Theme.WARN, Pair(0xFF29251A.toInt(), 0xFF9B812F.toInt()))
            }
            val short = state.first
            val accent = state.second
            val fill = state.third.first
            val border = state.third.second
            status.text = "●  " + short
            status.setTextColor(accent)
            status.background = rounded(fill, 12, border)
            status.contentDescription = "Orion state: " + short
            if (changed) {
                status.animate().cancel()
                status.alpha = .72f
                status.scaleX = .98f
                status.scaleY = .98f
                status.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            val busy = mode != OrbView.Mode.IDLE || PhoneAiService.instance?.isTaskRunning() == true
            stop.visibility = if (busy) View.VISIBLE else View.GONE
            stop.isEnabled = busy
            stop.text = "■"
            if (busy) {
                stop.animate().cancel()
                stop.scaleX = .94f
                stop.scaleY = .94f
                stop.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            send.alpha = if (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING) .72f else 1f
        }
    }
}
