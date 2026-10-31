package com.phoneai.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Orion's market watcher. Runs as a foreground service so it keeps working with the screen off.
 * Every few minutes it checks your price alerts and scans your watchlist for setups.
 */
class WatchService : Service() {

    companion object {
        private const val CH_FG = "orion_watch_fg"
        private const val CH_ALERT = "orion_alerts"
        private const val ID_FG = 4107

        fun start(c: Context) {
            Prefs.setWatchOn(c, true)
            try {
                c.startForegroundService(Intent(c, WatchService::class.java))
            } catch (e: Exception) {
                Prefs.setWatchOn(c, false)
            }
        }

        fun stop(c: Context) {
            Prefs.setWatchOn(c, false)
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pending(c))
            } catch (e: Exception) {
            }
            c.stopService(Intent(c, WatchService::class.java))
        }

        private fun pending(c: Context): PendingIntent =
            PendingIntent.getService(
                c, 77, Intent(c, WatchService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        makeChannels()
        val n = Notification.Builder(this, CH_FG)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion is watching the market")
            .setContentText("Checking your watchlist and alerts")
            .setOngoing(true)
            .build()
        try {
            startForeground(ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Prefs.setWatchOn(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.watchOn(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!busy) {
            busy = true
            worker.execute {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orion:watch")
                wl.acquire(3 * 60 * 1000L)
                try {
                    cycle()
                } catch (e: Exception) {
                } finally {
                    try {
                        if (wl.isHeld) wl.release()
                    } catch (e: Exception) {
                    }
                    busy = false
                    scheduleNext()
                }
            }
        }
        return START_STICKY
    }

    private fun scheduleNext() {
        if (!Prefs.watchOn(this)) return
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + Prefs.watchMinutes(this) * 60000L
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(this))
        } catch (e: Exception) {
        }
    }

    private fun makeChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_FG, "Orion market watch", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Orion trade alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun alert(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun cycle() {
        // ---- price alerts
        val arr = try {
            JSONArray(Prefs.alerts(this))
        } catch (e: Exception) {
            JSONArray()
        }
        if (arr.length() > 0) {
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sym = o.optString("symbol")
                val level = o.optDouble("price")
                var hit = false
                try {
                    val p = Bybit.price(this, sym)
                    hit = if (o.optString("dir") == "below") p <= level else p >= level
                    if (hit) alert(2000 + i, "Orion price alert", sym.removeSuffix("USDT") + " is " + Ta.price(p) + ", " + o.optString("dir") + " your level " + level)
                } catch (e: Exception) {
                }
                if (!hit) keep.put(o)
            }
            Prefs.setAlerts(this, keep.toString())
        }
        // ---- setups
        val found = Scanner.scan(this)
        var sent = 0
        for (s in found) {
            if (sent >= 3) break
            if (Journal.add(this, s)) {
                alert(3000 + sent, "Orion: " + s.side.uppercase() + " " + s.symbol.removeSuffix("USDT") + " (" + s.tf + ")", Ta.describe(s))
                sent++
            }
        }
        // ---- trading desk automation: playbook matches, forex and news heads-ups, practice trades, morning brief
        try { TradingDesk.watchCycle(this) { id, title, text -> alert(id, title, text) } } catch (e: Exception) { }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
