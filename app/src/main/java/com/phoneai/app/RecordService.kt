package com.phoneai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Records the screen to a video in Movies/Orion. Runs as a foreground service (Android requires it). */
class RecordService : Service() {

    companion object {
        /** idle, waiting (for the permission popup), recording, denied, error */
        @Volatile
        var state = "idle"

        @Volatile
        var lastName = ""

        @Volatile
        var lastError = ""

        @Volatile
        var instance: RecordService? = null

        const val ACTION_STOP = "com.phoneai.app.STOP_RECORD"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "orion_record"
        private const val NOTIF_ID = 77
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var tmp: File? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop recording", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Orion is recording your screen")
            .setContentText("Tap Stop recording when done")
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        instance = this
        try {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            fail("Could not start recording: ${e.message}")
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            fail("No permission data.")
            return START_NOT_STICKY
        }
        try {
            begin(code, data)
        } catch (e: Exception) {
            fail("Could not start recording: ${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopRecording() }
            }
        }, main)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = wm.maximumWindowMetrics.bounds
        var w = b.width()
        var h = b.height()
        val longest = max(w, h)
        if (longest > 1920) {
            val f = 1920f / longest
            w = (w * f).toInt()
            h = (h * f).toInt()
        }
        w -= w % 2
        h -= h % 2

        val file = File(cacheDir, "orion_rec.mp4")
        if (file.exists()) file.delete()
        tmp = file
        @Suppress("DEPRECATION")
        val mr = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(8_000_000)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
        recorder = mr
        display = mp.createVirtualDisplay(
            "orion", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mr.surface, null, null
        )
        mr.start()
        state = "recording"
    }

    private fun fail(msg: String) {
        lastError = msg
        state = "error"
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            display?.release()
        } catch (e: Exception) {
        }
        display = null
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        projection = null
    }

    /** Stops, then saves the video where the Gallery can see it. */
    fun stopRecording() {
        if (stopping || state != "recording") return
        stopping = true
        var good = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            good = false
        }
        cleanup()
        val file = tmp
        Thread {
            try {
                if (good && file != null && file.exists() && file.length() > 0) {
                    val name = "Orion_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
                    val values = ContentValues()
                    values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Orion")
                    values.put(MediaStore.Video.Media.IS_PENDING, 1)
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        val done = ContentValues()
                        done.put(MediaStore.Video.Media.IS_PENDING, 0)
                        contentResolver.update(uri, done, null, null)
                        lastName = name
                        state = "idle"
                    } else {
                        lastError = "Could not save the video."
                        state = "error"
                    }
                } else {
                    lastError = "Recording was too short to save."
                    state = "error"
                }
                file?.delete()
            } catch (e: Exception) {
                lastError = "Save failed: ${e.message}"
                state = "error"
            }
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        instance = null
        if (state == "recording" || state == "waiting") state = "idle"
        super.onDestroy()
    }
}
