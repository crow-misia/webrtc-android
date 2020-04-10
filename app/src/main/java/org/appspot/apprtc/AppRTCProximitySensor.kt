/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import org.appspot.apprtc.util.AppRTCUtils.getThreadInfo
import org.webrtc.ThreadUtils
import timber.log.Timber

/**
 * AppRTCProximitySensor manages functions related to the proximity sensor in
 * the AppRTC demo.
 * On most device, the proximity sensor is implemented as a boolean-sensor.
 * It returns just two values "NEAR" or "FAR". Thresholding is done on the LUX
 * value i.e. the LUX value of the light sensor is compared with a threshold.
 * A LUX-value more than the threshold means the proximity sensor returns "FAR".
 * Anything less than the threshold value and the sensor  returns "NEAR".
 */
class AppRTCProximitySensor private constructor(
    context: Context,
    private val onSensorStateListener: Runnable
) : SensorEventListener {
    // This class should be created, started and stopped on one thread
    // (e.g. the main thread). We use |nonThreadSafe| to ensure that this is
    // the case. Only active when |DEBUG| is set to true.
    private val threadChecker = ThreadUtils.ThreadChecker()
    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var proximitySensor: Sensor? = null
    private var lastStateReportIsNear = false

    /**
     * Activate the proximity sensor. Also do initialization if called for the
     * first time.
     */
    fun start(): Boolean {
        threadChecker.checkIsOnValidThread()
        Timber.d("start%s", getThreadInfo())
        if (!initDefaultSensor()) {
            // Proximity sensor is not supported on this device.
            return false
        }
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        return true
    }

    /** Deactivate the proximity sensor.  */
    fun stop() {
        threadChecker.checkIsOnValidThread()
        Timber.d("stop%s", getThreadInfo())
        proximitySensor?.also { sensorManager.unregisterListener(this, it) }
    }

    /** Getter for last reported state. Set to true if "near" is reported.  */
    fun sensorReportsNearState(): Boolean {
        threadChecker.checkIsOnValidThread()
        return lastStateReportIsNear
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        threadChecker.checkIsOnValidThread()
        assert(sensor.type == Sensor.TYPE_PROXIMITY)
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Timber.e("The values returned by this sensor cannot be trusted")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        threadChecker.checkIsOnValidThread()
        val proximitySensor = proximitySensor ?: return
        assert(event.sensor.type == Sensor.TYPE_PROXIMITY)
        // As a best practice; do as little as possible within this method and
        // avoid blocking.
        val distanceInCentimeters = event.values[0]
        lastStateReportIsNear = if (distanceInCentimeters < proximitySensor.maximumRange) {
            Timber.d("Proximity sensor => NEAR state")
            true
        } else {
            Timber.d("Proximity sensor => FAR state")
            false
        }

        // Report about new state to listening client. Client can then call
        // sensorReportsNearState() to query the current state (NEAR or FAR).
        onSensorStateListener.run()
        Timber.d("onSensorChanged%s: accuracy=%d, timestamp=%d, distance=%f",
            getThreadInfo(), event.accuracy, event.timestamp, event.values[0])
    }

    /**
     * Get default proximity sensor if it exists. Tablet devices (e.g. Nexus 7)
     * does not support this type of sensor and false will be returned in such
     * cases.
     */
    private fun initDefaultSensor(): Boolean {
        if (proximitySensor != null) {
            return true
        }
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor == null) {
            return false
        }
        logProximitySensorInfo()
        return true
    }

    /** Helper method for logging information about the proximity sensor.  */
    private fun logProximitySensorInfo() {
        val proximitySensor = proximitySensor ?: return
        val info = StringBuilder("Proximity sensor: ")
        info.append("name=").append(proximitySensor.name)
        info.append(", vendor: ").append(proximitySensor.vendor)
        info.append(", power: ").append(proximitySensor.power)
        info.append(", resolution: ").append(proximitySensor.resolution)
        info.append(", max range: ").append(proximitySensor.maximumRange)
        info.append(", min delay: ").append(proximitySensor.minDelay)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            // Added in API level 20.
            info.append(", type: ").append(proximitySensor.stringType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Added in API level 21.
            info.append(", max delay: ").append(proximitySensor.maxDelay)
            info.append(", reporting mode: ").append(proximitySensor.reportingMode)
            info.append(", isWakeUpSensor: ").append(proximitySensor.isWakeUpSensor)
        }
        Timber.d(info.toString())
    }

    companion object {
        /** Construction  */
        fun create(context: Context, sensorStateListener: Runnable): AppRTCProximitySensor {
            return AppRTCProximitySensor(context, sensorStateListener)
        }
    }

    init {
        Timber.d("AppRTCProximitySensor%s", getThreadInfo())
    }
}