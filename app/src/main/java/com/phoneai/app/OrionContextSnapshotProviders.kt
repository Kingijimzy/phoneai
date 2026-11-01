package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.util.Locale

/** Consent-gated, on-demand device snapshot. No serial numbers, SSIDs or app contents are read. */
internal object OrionDeviceContextProvider {
    fun summary(context: Context): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.DEVICE_STATE).active()) return ""
        val app = context.applicationContext
        val batteryIntent = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val network = when {
            capabilities == null -> "offline_or_unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            when (power?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        }.getOrDefault("unknown") else "unavailable"
        val screen = if (power?.isInteractive == true) "interactive" else "not_interactive"
        return "OWNER-GRANTED DEVICE STATE (on-demand snapshot; not stored): battery=${if (percent >= 0) "$percent%" else "unknown"}; charging=$charging; power_saver=${power?.isPowerSaveMode ?: false}; screen=$screen; network=$network; thermal=$thermal"
    }
}

/** Uses only a recent last-known fix; never starts background location updates. */
internal object OrionLocationContextProvider {
    private const val MAX_AGE_MS = 10L * 60L * 1000L

    fun isFresh(fixTime: Long, now: Long = System.currentTimeMillis()): Boolean =
        fixTime > 0L && fixTime <= now && now - fixTime <= MAX_AGE_MS

    fun summary(context: Context, now: Long = System.currentTimeMillis()): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.LOCATION, now).active()) return ""
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return ""
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return ""
        val fixes = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider ->
                runCatching { if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null }.getOrNull()
            }
            .filter { isFresh(it.time, now) }
            .sortedByDescending { it.time }
        val fix: Location = fixes.firstOrNull() ?: return ""
        val precision = if (fine) "approximate_to_about_1km" else "coarse"
        return String.format(
            Locale.US,
            "OWNER-GRANTED LOCATION CONTEXT (last-known fix only; no tracking; age=%d min; accuracy=%.0f m; %s): approximately %.2f, %.2f",
            ((now - fix.time).coerceAtLeast(0L) / 60_000L),
            fix.accuracy,
            precision,
            fix.latitude,
            fix.longitude
        )
    }
}
