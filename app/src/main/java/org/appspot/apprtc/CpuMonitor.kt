/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Simple CPU monitor.  The caller creates a CpuMonitor object which can then
 * be used via sampleCpuUtilization() to collect the percentual use of the
 * cumulative CPU capacity for all CPUs running at their nominal frequency.  3
 * values are generated: (1) getCpuCurrent() returns the use since the last
 * sampleCpuUtilization(), (2) getCpuAvg3() returns the use since 3 prior
 * calls, and (3) getCpuAvgAll() returns the use over all SAMPLE_SAVE_NUMBER
 * calls.
 *
 *
 * CPUs in Android are often "offline", and while this of course means 0 Hz
 * as current frequency, in this state we cannot even get their nominal
 * frequency.  We therefore tread carefully, and allow any CPU to be missing.
 * Missing CPUs are assumed to have the same nominal frequency as any close
 * lower-numbered CPU, but as soon as it is online, we'll get their proper
 * frequency and remember it.  (Since CPU 0 in practice always seem to be
 * online, this unidirectional frequency inheritance should be no problem in
 * practice.)
 *
 *
 * Caveats:
 * o No provision made for zany "turbo" mode, common in the x86 world.
 * o No provision made for ARM big.LITTLE; if CPU n can switch behind our
 * back, we might get incorrect estimates.
 * o This is not thread-safe.  To call asynchronously, create different
 * CpuMonitor objects.
 *
 *
 * If we can gather enough info to generate a sensible result,
 * sampleCpuUtilization returns true.  It is designed to never throw an
 * exception.
 *
 *
 * sampleCpuUtilization should not be called too often in its present form,
 * since then deltas would be small and the percent values would fluctuate and
 * be unreadable. If it is desirable to call it more often than say once per
 * second, one would need to increase SAMPLE_SAVE_NUMBER and probably use
 * Queue<Integer> to avoid copying overhead.
 *
</Integer> *
 * Known problems:
 * 1. Nexus 7 devices running Kitkat have a kernel which often output an
 * incorrect 'idle' field in /proc/stat.  The value is close to twice the
 * correct value, and then returns to back to correct reading.  Both when
 * jumping up and back down we might create faulty CPU load readings.
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
internal class CpuMonitor(private val context: Context) {
    // User CPU usage at current frequency.
    private val userCpuUsage: MovingAverage

    // System CPU usage at current frequency.
    private val systemCpuUsage: MovingAverage

    // Total CPU usage relative to maximum frequency.
    private val totalCpuUsage: MovingAverage

    // CPU frequency in percentage from maximum.
    private val frequencyScale: MovingAverage
    private var executor: ScheduledExecutorService? = null
    private var lastStatLogTimeMs: Long
    private var cpuFreqMax: LongArray = longArrayOf()
    private var cpusPresent = 0
    private var actualCpusPresent = 0
    private var initialized = false
    private var cpuOveruse = false
    private var maxPath: Array<String?> = emptyArray()
    private var curPath: Array<String?> = emptyArray()
    private var curFreqScales: DoubleArray = doubleArrayOf()
    private var lastProcStat: ProcStat = ProcStat(0, 0, 0)

    private class ProcStat(
        val userTime: Long,
        val systemTime: Long,
        val idleTime: Long
    )

    private class MovingAverage(size: Int) {
        private val size: Int
        private var sum = 0.0
        var current = 0.0
            private set
        private val circBuffer: DoubleArray
        private var circBufferIndex = 0
        fun reset() {
            Arrays.fill(circBuffer, 0.0)
            circBufferIndex = 0
            sum = 0.0
            current = 0.0
        }

        fun addValue(value: Double) {
            sum -= circBuffer[circBufferIndex]
            circBuffer[circBufferIndex++] = value
            current = value
            sum += value
            if (circBufferIndex >= size) {
                circBufferIndex = 0
            }
        }

        val average: Double
            get() = sum / size.toDouble()

        init {
            if (size <= 0) {
                throw AssertionError("Size value in MovingAverage ctor should be positive.")
            }
            this.size = size
            circBuffer = DoubleArray(size)
        }
    }

    fun pause() {
        executor?.also {
            Timber.d("pause")
            it.shutdownNow()
            executor = null
        }
    }

    fun resume() {
        Timber.d("resume")
        resetStat()
        scheduleCpuUtilizationTask()
    }

    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @Synchronized
    fun reset() {
        executor?.also {
            Timber.d("reset")
            resetStat()
            cpuOveruse = false
        }
    }

    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @Synchronized
    fun getCpuUsageCurrent(): Int {
        return doubleToPercent(userCpuUsage.current + systemCpuUsage.current)
    }

    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @Synchronized
    fun getCpuUsageAverage(): Int {
        return doubleToPercent(userCpuUsage.average + systemCpuUsage.average)
    }

    // TODO(bugs.webrtc.org/8491): Remove NoSynchronizedMethodCheck suppression.
    @Synchronized
    fun getFrequencyScaleAverage(): Int {
        return doubleToPercent(frequencyScale.average)
    }

    private fun scheduleCpuUtilizationTask() {
        executor?.shutdownNow()
        executor = null
        val executor = Executors.newSingleThreadScheduledExecutor()
        // Prevent downstream linter warnings.
        val possiblyIgnoredError = executor.scheduleAtFixedRate(
            { cpuUtilizationTask() },
            0,
            CPU_STAT_SAMPLE_PERIOD_MS.toLong(),
            TimeUnit.MILLISECONDS
        )
        this.executor = executor
    }

    private fun cpuUtilizationTask() {
        val cpuMonitorAvailable = sampleCpuUtilization()
        if (cpuMonitorAvailable
            && SystemClock.elapsedRealtime() - lastStatLogTimeMs >= CPU_STAT_LOG_PERIOD_MS
        ) {
            lastStatLogTimeMs = SystemClock.elapsedRealtime()
            Timber.d(getStatString())
        }
    }

    private fun init() {
        try {
            FileInputStream("/sys/devices/system/cpu/present").bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    Scanner(reader).useDelimiter("[-\n]").use { scanner ->
                        scanner.nextInt() // Skip leading number 0.
                        cpusPresent = 1 + scanner.nextInt()
                        scanner.close()
                    }
                }
        } catch (e: FileNotFoundException) {
            Timber.e("Cannot do CPU stats since /sys/devices/system/cpu/present is missing")
        } catch (e: IOException) {
            Timber.e("Error closing file")
        } catch (e: Exception) {
            Timber.e("Cannot do CPU stats due to /sys/devices/system/cpu/present parsing problem")
        }
        cpuFreqMax = LongArray(cpusPresent)
        maxPath = arrayOfNulls(cpusPresent)
        curPath = arrayOfNulls(cpusPresent)
        curFreqScales = DoubleArray(cpusPresent)
        for (i in 0 until cpusPresent) {
            // Frequency "not yet determined".
            cpuFreqMax[i] = 0
            curFreqScales[i] = 0.0
            maxPath[i] = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
            curPath[i] = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
        }
        lastProcStat = ProcStat(0, 0, 0)
        resetStat()
        initialized = true
    }

    @Synchronized
    private fun resetStat() {
        userCpuUsage.reset()
        systemCpuUsage.reset()
        totalCpuUsage.reset()
        frequencyScale.reset()
        lastStatLogTimeMs = SystemClock.elapsedRealtime()
    }

    // Use sticky broadcast with null receiver to read battery level once only.
    private fun getBatteryLevel(): Int {
        // Use sticky broadcast with null receiver to read battery level once only.
        var batteryLevel = 0
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.also {
            val batteryScale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (batteryScale > 0) {
                batteryLevel =
                    (100f * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / batteryScale).toInt()
            }
        }
        return batteryLevel
    }

    /**
     * Re-measure CPU use.  Call this method at an interval of around 1/s.
     * This method returns true on success.  The fields
     * cpuCurrent, cpuAvg3, and cpuAvgAll are updated on success, and represents:
     * cpuCurrent: The CPU use since the last sampleCpuUtilization call.
     * cpuAvg3: The average CPU over the last 3 calls.
     * cpuAvgAll: The average CPU over the last SAMPLE_SAVE_NUMBER calls.
     */
    @Synchronized
    private fun sampleCpuUtilization(): Boolean {
        var lastSeenMaxFreq: Long = 0
        var cpuFreqCurSum: Long = 0
        var cpuFreqMaxSum: Long = 0
        if (!initialized) {
            init()
        }
        if (cpusPresent == 0) {
            return false
        }
        actualCpusPresent = 0
        for (i in 0 until cpusPresent) {
            /*
             * For each CPU, attempt to first read its max frequency, then its
             * current frequency.  Once as the max frequency for a CPU is found,
             * save it in cpuFreqMax[].
             */
            curFreqScales[i] = 0.0
            if (cpuFreqMax[i] == 0L) {
                // We have never found this CPU's max frequency.  Attempt to read it.
                val cpufreqMax = readFreqFromFile(maxPath[i])
                if (cpufreqMax > 0) {
                    Timber.d("Core %d. Max frequency: %d", i, cpufreqMax)
                    lastSeenMaxFreq = cpufreqMax
                    cpuFreqMax[i] = cpufreqMax
                    maxPath[i] = null // Kill path to free its memory.
                }
            } else {
                lastSeenMaxFreq = cpuFreqMax[i] // A valid, previously read value.
            }
            val cpuFreqCur = readFreqFromFile(curPath[i])
            if (cpuFreqCur == 0L && lastSeenMaxFreq == 0L) {
                // No current frequency information for this CPU core - ignore it.
                continue
            }
            if (cpuFreqCur > 0) {
                actualCpusPresent++
            }
            cpuFreqCurSum += cpuFreqCur

            /* Here, lastSeenMaxFreq might come from
             * 1. cpuFreq[i], or
             * 2. a previous iteration, or
             * 3. a newly read value, or
             * 4. hypothetically from the pre-loop dummy.
             */
            cpuFreqMaxSum += lastSeenMaxFreq
            if (lastSeenMaxFreq > 0) {
                curFreqScales[i] = cpuFreqCur.toDouble() / lastSeenMaxFreq
            }
        }
        if (cpuFreqCurSum == 0L || cpuFreqMaxSum == 0L) {
            Timber.e("Could not read max or current frequency for any CPU")
            return false
        }

        /*
         * Since the cycle counts are for the period between the last invocation
         * and this present one, we average the percentual CPU frequencies between
         * now and the beginning of the measurement period.  This is significantly
         * incorrect only if the frequencies have peeked or dropped in between the
         * invocations.
         */
        var currentFrequencyScale = cpuFreqCurSum / cpuFreqMaxSum.toDouble()
        if (frequencyScale.current > 0) {
            currentFrequencyScale = (frequencyScale.current + currentFrequencyScale) * 0.5
        }
        val procStat = readProcStat() ?: return false
        val diffUserTime = procStat.userTime - lastProcStat.userTime
        val diffSystemTime = procStat.systemTime - lastProcStat.systemTime
        val diffIdleTime = procStat.idleTime - lastProcStat.idleTime
        val allTime = diffUserTime + diffSystemTime + diffIdleTime
        if (currentFrequencyScale == 0.0 || allTime == 0L) {
            return false
        }

        // Update statistics.
        frequencyScale.addValue(currentFrequencyScale)
        val currentUserCpuUsage = diffUserTime / allTime.toDouble()
        userCpuUsage.addValue(currentUserCpuUsage)
        val currentSystemCpuUsage = diffSystemTime / allTime.toDouble()
        systemCpuUsage.addValue(currentSystemCpuUsage)
        val currentTotalCpuUsage =
            (currentUserCpuUsage + currentSystemCpuUsage) * currentFrequencyScale
        totalCpuUsage.addValue(currentTotalCpuUsage)

        // Save new measurements for next round's deltas.
        lastProcStat = procStat
        return true
    }

    private fun doubleToPercent(d: Double): Int {
        return (d * 100 + 0.5).toInt()
    }

    @Synchronized
    private fun getStatString(): String {
        val stat = StringBuilder()
        stat.append("CPU User: ")
            .append(doubleToPercent(userCpuUsage.current))
            .append("/")
            .append(doubleToPercent(userCpuUsage.average))
            .append(". System: ")
            .append(doubleToPercent(systemCpuUsage.current))
            .append("/")
            .append(doubleToPercent(systemCpuUsage.average))
            .append(". Freq: ")
            .append(doubleToPercent(frequencyScale.current))
            .append("/")
            .append(doubleToPercent(frequencyScale.average))
            .append(". Total usage: ")
            .append(doubleToPercent(totalCpuUsage.current))
            .append("/")
            .append(doubleToPercent(totalCpuUsage.average))
            .append(". Cores: ")
            .append(actualCpusPresent)
        stat.append("( ")
        for (i in 0 until cpusPresent) {
            stat.append(doubleToPercent(curFreqScales[i])).append(" ")
        }
        stat.append("). Battery: ").append(getBatteryLevel())
        if (cpuOveruse) {
            stat.append(". Overuse.")
        }
        return stat.toString()
    }

    /**
     * Read a single integer value from the named file.  Return the read value
     * or if an error occurs return 0.
     */
    private fun readFreqFromFile(fileName: String?): Long {
        fileName ?: return 0L
        var number = 0L
        try {
            FileInputStream(fileName).bufferedReader(Charsets.UTF_8).use {
                number = it.readLine().toLongOrNull() ?: 0L
            }
        } catch (e: FileNotFoundException) {
            // CPU core is off, so file with its scaling frequency .../cpufreq/scaling_cur_freq
            // is not present. This is not an error.
        } catch (e: IOException) {
            // CPU core is off, so file with its scaling frequency .../cpufreq/scaling_cur_freq
            // is empty. This is not an error.
        }
        return number
    }

    /*
   * Read the current utilization of all CPUs using the cumulative first line
   * of /proc/stat.
   */
    private fun readProcStat(): ProcStat? {
        var userTime: Long = 0
        var systemTime: Long = 0
        var idleTime: Long = 0
        try {
            FileInputStream("/proc/stat").bufferedReader(Charsets.UTF_8).use { reader ->
                // line should contain something like this:
                // cpu  5093818 271838 3512830 165934119 101374 447076 272086 0 0 0
                //       user    nice  system     idle   iowait  irq   softirq
                val line = reader.readLine()
                val lines = line.split("\\s+").toTypedArray()
                val length = lines.size
                if (length >= 5) {
                    userTime = lines[1].toLongOrNull() ?: 0L // user
                    userTime += lines[2].toLongOrNull() ?: 0L // nice
                    systemTime = lines[3].toLongOrNull() ?: 0L // system
                    idleTime = lines[4].toLongOrNull() ?: 0L // idle
                }
                if (length >= 8) {
                    userTime += lines[5].toLongOrNull() ?: 0L // iowait
                    systemTime += lines[6].toLongOrNull() ?: 0L // irq
                    systemTime += lines[7].toLongOrNull() ?: 0L // softirq
                }
            }
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Cannot open /proc/stat for reading")
            return null
        } catch (e: Exception) {
            Timber.e(e, "Problems parsing /proc/stat")
            return null
        }
        return ProcStat(userTime, systemTime, idleTime)
    }

    companion object {
        private const val MOVING_AVERAGE_SAMPLES = 5
        private const val CPU_STAT_SAMPLE_PERIOD_MS = 2000
        private const val CPU_STAT_LOG_PERIOD_MS = 6000
        fun isSupported(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        }
    }

    init {
        if (!isSupported()) {
            throw RuntimeException("CpuMonitor is not supported on this Android version.")
        }
        Timber.d("CpuMonitor ctor.")
        userCpuUsage = MovingAverage(MOVING_AVERAGE_SAMPLES)
        systemCpuUsage = MovingAverage(MOVING_AVERAGE_SAMPLES)
        totalCpuUsage = MovingAverage(MOVING_AVERAGE_SAMPLES)
        frequencyScale = MovingAverage(MOVING_AVERAGE_SAMPLES)
        lastStatLogTimeMs = SystemClock.elapsedRealtime()
        scheduleCpuUtilizationTask()
    }
}