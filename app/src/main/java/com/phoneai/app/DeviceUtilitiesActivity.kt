package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** User-facing screen for local Network Radar, Game Sentinel, and scoped file operations. */
class DeviceUtilitiesActivity : Activity() {
    private lateinit var content: LinearLayout
    private var selectedUri: Uri? = null
    private var copySource: Uri? = null
    private var filePreview: String = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun add(parent: LinearLayout, view: android.view.View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        parent.addView(view, lp)
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun section(title: String, note: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            setBackgroundColor(Theme.PANEL)
        }
        add(card, TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Theme.CYAN)
        }, 0)
        add(card, TextView(this).apply {
            text = note
            textSize = 13f
            setTextColor(Theme.MUTED)
        }, 5)
        add(content, card, 14)
        return card
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)

        add(content, TextView(this).apply {
            text = "DEVICE UTILITIES"
            textSize = 24f
            setTextColor(Theme.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, 0)
        add(content, button("Back to Orion") { finish() }, 4)

        val network = section("Network Radar", "Local connection diagnostics. No LAN scanning or Wi-Fi password collection.")
        val networkResult = TextView(this).apply { textSize = 14f; setTextColor(Theme.TEXT) }
        add(network, networkResult)
        add(network, button("Refresh network status") {
            Thread {
                val snapshot = NetworkRadar.inspect(this)
                val report = buildString {
                    appendLine(snapshot.summary)
                    appendLine("Transport: ${snapshot.transport}")
                    appendLine("Connected: ${snapshot.connected}")
                    appendLine("Internet capability: ${snapshot.hasInternetCapability}")
                    appendLine("Validated by Android: ${snapshot.validatedInternet}")
                    appendLine("Captive portal: ${snapshot.captivePortal}")
                    appendLine("Metered network: ${snapshot.metered}")
                    appendLine("VPN active: ${snapshot.vpnActive}")
                    appendLine("DNS: ${snapshot.dnsServers.joinToString().ifBlank { "not exposed" }}")
                    append("MTU: ${snapshot.mtu ?: "not exposed"}")
                }
                runOnUiThread { networkResult.text = report }
            }.start()
        })

        val game = section("Game Sentinel", "Track sessions you start manually and inspect Android-exposed memory, battery, and thermal indicators.")
        val gameResult = TextView(this).apply { textSize = 14f; setTextColor(Theme.TEXT) }
        add(game, gameResult)
        add(game, button("Check device health") {
            Thread {
                val s = GameSentinel.deviceSnapshot(this)
                val report = buildString {
                    appendLine("Battery: ${s.batteryPercent?.let { "$it%" } ?: "not exposed"}")
                    appendLine("Battery temperature: ${s.batteryTemperatureC?.let { "$it°C" } ?: "not exposed"}")
                    appendLine("Memory available: ${s.availableMemoryMb} MB / ${s.totalMemoryMb} MB")
                    appendLine("Low-memory warning: ${s.lowMemory}")
                    appendLine("Thermal status: ${s.thermalStatus}")
                    appendLine()
                    append(s.recommendation)
                }
                runOnUiThread { gameResult.text = report }
            }.start()
        })
        add(game, button("Start a game session") { startSessionDialog(gameResult) })
        add(game, button("End current session") {
            val ended = GameSentinel.endSession(this)
            gameResult.text = if (ended == null) "No active session." else
                "Saved ${ended.appLabel}: ${ended.durationSeconds / 60} min ${ended.durationSeconds % 60} sec"
        })
        add(game, button("View session history") {
            val rows = GameSentinel.history(this).take(30).map {
                val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.startedAt))
                "${it.appLabel} — ${it.durationSeconds / 60} min — $date"
            }
            AlertDialog.Builder(this).setTitle("Game session history")
                .setMessage(rows.joinToString("\n").ifBlank { "No sessions recorded yet." })
                .setPositiveButton("OK", null).show()
        })

        val files = section("File Handling", "Only files you choose through Android's document picker are accessed. Text reads/writes are limited to 1 MB; copies to 25 MB.")
        add(files, button("Open and read a text file") { launchOpenFile(REQUEST_READ) })
        add(files, button("Create or save a text file") { saveTextDialog() })
        add(files, button("Choose source for file copy") { launchOpenFile(REQUEST_COPY_SOURCE) })
        add(files, button("Choose destination and copy") {
            if (copySource == null) {
                toast("Choose the copy source first.")
            } else {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, "PhoneAI-copy")
                }
                startActivityForResult(intent, REQUEST_COPY_DESTINATION)
            }
        })
        add(files, button("Delete selected file…") {
            val uri = selectedUri
            if (uri == null) toast("Choose a file first.") else {
                val info = PhoneFileHandler.inspect(this, uri).value
                AlertDialog.Builder(this)
                    .setTitle("Delete ${info?.displayName ?: "selected file"}?")
                    .setMessage("This asks the document provider to delete the selected item. This cannot always be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        val result = PhoneFileHandler.deleteSelected(this, uri)
                        toast(if (result.isSuccess && result.value == true) "File deleted." else result.error ?: "Delete failed.")
                        if (result.isSuccess) selectedUri = null
                    }.show()
            }
        })
        add(files, button("Show last text preview") {
            AlertDialog.Builder(this).setTitle("Text preview")
                .setMessage(filePreview.ifBlank { "No text file read yet." })
                .setPositiveButton("Close", null).show()
        })
    }

    private fun startSessionDialog(result: TextView) {
        val label = EditText(this).apply { hint = "Game name (e.g. FC Mobile)" }
        val pkg = EditText(this).apply { hint = "Package name (optional label, e.g. com.example.game)" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        add(box, label, 0)
        add(box, pkg, 8)
        AlertDialog.Builder(this).setTitle("Start session")
            .setMessage("This records a session timer only; it does not monitor the game automatically.")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                val packageName = pkg.text.toString().trim().ifBlank { "manual.session" }
                val session = GameSentinel.startSession(this, label.text.toString(), packageName)
                result.text = if (session == null) "Could not start session. Check the name/package or end the current session first."
                    else "Session started: ${session.appLabel}"
            }.show()
    }

    private fun saveTextDialog() {
        val editor = EditText(this).apply {
            hint = "Text to save"
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("Write text file")
            .setMessage("The text is passed to Android's save picker only after you tap Save.")
            .setView(editor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose save location") { _, _ ->
                filePreview = editor.text.toString()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "phoneai-note.txt")
                }
                startActivityForResult(intent, REQUEST_WRITE)
            }.show()
    }

    private fun launchOpenFile(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (request == REQUEST_READ) "text/*" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Activity Result API migration can be a separate cleanup; this is compatible with the current Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_READ -> {
                selectedUri = uri
                Thread {
                    val info = PhoneFileHandler.inspect(this, uri).value
                    val read = PhoneFileHandler.readText(this, uri)
                    runOnUiThread {
                        if (read.isSuccess) {
                            filePreview = read.value.orEmpty()
                            AlertDialog.Builder(this).setTitle(info?.displayName ?: "Text file")
                                .setMessage(filePreview.take(12_000)).setPositiveButton("Close", null).show()
                        } else toast(read.error ?: "Read failed.")
                    }
                }.start()
            }
            REQUEST_WRITE -> {
                selectedUri = uri
                Thread {
                    val written = PhoneFileHandler.writeText(this, uri, filePreview)
                    runOnUiThread { toast(if (written.isSuccess) "Saved ${written.value} bytes." else written.error ?: "Save failed.") }
                }.start()
            }
            REQUEST_COPY_SOURCE -> {
                copySource = uri
                selectedUri = uri
                toast("Copy source selected.")
            }
            REQUEST_COPY_DESTINATION -> {
                val source = copySource ?: return
                Thread {
                    val copied = PhoneFileHandler.copy(this, source, uri)
                    runOnUiThread {
                        toast(if (copied.isSuccess) "Copied ${copied.value} bytes." else copied.error ?: "Copy failed.")
                    }
                }.start()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_READ = 501
        private const val REQUEST_WRITE = 502
        private const val REQUEST_COPY_SOURCE = 503
        private const val REQUEST_COPY_DESTINATION = 504
    }
}
