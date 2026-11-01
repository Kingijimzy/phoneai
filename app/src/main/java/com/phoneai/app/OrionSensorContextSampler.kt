package com.phoneai.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Foreground-only motion context sampler. It emits coarse activity hints, never raw sensor
 * streams, and has no background service of its own. The owning Activity/service must stop it
 * when leaving the foreground or when the owner revokes the MOTION scope.
 */
internal class OrionSensorContextSampler(
    context: Context,
    private val sensorManager: SensorManager,
    private val onObservation: (String) -> Unit
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val active = AtomicBoolean(false)
    @Volatile private var lastEmission = 0L
    @Volatile private var accelerationMagnitude = 0.0
    @Volatile private var rotationMagnitude = 0.0

    fun start(): Boolean {
        if (!OrionSensorConsent.read(appContext, OrionSensorScope.MOTION).active()) return false
        if (!active.compareAndSet(false, true)) return true
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val registered = listOfNotNull(accelerometer, gyroscope).map {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }.any { it }
        if (!registered) active.set(false)
        return registered
    }

    fun stop() {
        if (active.compareAndSet(true, false)) sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active.get() || event == null) return
        if (!OrionSensorConsent.read(appContext, OrionSensorScope.MOTION).active()) {
            stop()
            return
        }
        val values = event.values
        val magnitude = sqrt(values.sumOf { it.toDouble() * it.toDouble() })
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerationMagnitude = magnitude
            Sensor.TYPE_GYROSCOPE -> rotationMagnitude = magnitude
            else -> return
        }
        val now = System.currentTimeMillis()
        if (now - lastEmission < 2_000L) return
        lastEmission = now
        val movement = when {
            accelerationMagnitude > 14.0 || rotationMagnitude > 1.2 -> "active movement"
            accelerationMagnitude in 8.0..11.5 && rotationMagnitude < 0.25 -> "relatively still"
            else -> "movement uncertain"
        }
        val summary = "Motion context: $movement (coarse on-device estimate; not a location or activity diagnosis)."
        OrionSensorContextStore.recordMotion(appContext, summary, now)
        onObservation(summary)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
