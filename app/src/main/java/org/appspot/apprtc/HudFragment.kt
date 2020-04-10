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

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_hud.*
import org.webrtc.StatsReport

/**
 * Fragment for HUD statistics display.
 */
class HudFragment : Fragment(R.layout.fragment_hud) {
    private var videoCallEnabled = false
    private var displayHud = false

    @Volatile
    private var isRunning = false

    private val cpuMonitor by lazy {
        if (CpuMonitor.isSupported()) {
            CpuMonitor(requireContext())
        } else null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        button_toggle_debug.setOnClickListener {
            if (displayHud) {
                val visibility = if (hud_stat_bwe.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                hudViewsSetProperties(visibility)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        arguments?.also {
            videoCallEnabled = it.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true)
            displayHud = it.getBoolean(CallActivity.EXTRA_DISPLAY_HUD, false)
        }
        val visibility = if (displayHud) View.VISIBLE else View.INVISIBLE
        encoder_stat_call.visibility = visibility
        button_toggle_debug.visibility = visibility
        hudViewsSetProperties(View.INVISIBLE)
        isRunning = true
        cpuMonitor?.resume()
    }

    override fun onStop() {
        isRunning = false
        cpuMonitor?.pause()
        super.onStop()
    }

    private fun hudViewsSetProperties(visibility: Int) {
        hud_stat_bwe.visibility = visibility
        hud_stat_connection.visibility = visibility
        hud_stat_video_send.visibility = visibility
        hud_stat_video_recv.visibility = visibility
        hud_stat_bwe.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5f)
        hud_stat_connection.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5f)
        hud_stat_video_send.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5f)
        hud_stat_video_recv.setTextSize(TypedValue.COMPLEX_UNIT_PT, 5f)
    }

    private fun getReportMap(report: StatsReport): Map<String, String> {
        return report.values.associateBy({ it.name }, { it.value })
    }

    fun updateEncoderStatistics(reports: List<StatsReport>) {
        if (!isRunning || !displayHud) {
            return
        }
        val encoderStat = StringBuilder(128)
        val bweStat = StringBuilder()
        val connectionStat = StringBuilder()
        val videoSendStat = StringBuilder()
        val videoRecvStat = StringBuilder()
        var fps: String? = null
        var targetBitrate: String? = null
        var actualBitrate: String? = null
        for (report in reports) {
            if (report.type == "ssrc" && report.id.contains("ssrc") && report.id.contains("send")) {
                // Send video statistics.
                val reportMap = getReportMap(report)
                if (reportMap["googTrackId"]?.contains(PeerConnectionClient.VIDEO_TRACK_ID) == true) {
                    fps = reportMap["googFrameRateSent"]
                    videoSendStat.appendln(report.id)
                    for (value in report.values) {
                        val name = value.name.replace("goog", "")
                        videoSendStat.append(name).append("=").appendln(value.value)
                    }
                }
            } else if (report.type == "ssrc" && report.id.contains("ssrc") && report.id.contains("recv")) {
                // Receive video statistics.
                val reportMap = getReportMap(report)
                // Check if this stat is for video track.
                reportMap["googFrameWidthReceived"]?.also {
                    videoRecvStat.appendln(report.id)
                    for (value in report.values) {
                        val name = value.name.replace("goog", "")
                        videoRecvStat.append(name).append("=").appendln(value.value)
                    }
                }
            } else if (report.id == "bweforvideo") {
                // BWE statistics.
                val reportMap = getReportMap(report)
                targetBitrate = reportMap["googTargetEncBitrate"]
                actualBitrate = reportMap["googActualEncBitrate"]
                bweStat.appendln(report.id)
                for (value in report.values) {
                    val name = value.name.replace("goog", "").replace("Available", "")
                    bweStat.append(name).append("=").appendln(value.value)
                }
            } else if (report.type == "googCandidatePair") {
                // Connection statistics.
                val reportMap = getReportMap(report)
                if (reportMap["googActiveConnection"] == "true") {
                    connectionStat.appendln(report.id)
                    for (value in report.values) {
                        val name = value.name.replace("goog", "")
                        connectionStat.append(name).append("=").appendln(value.value)
                    }
                }
            }
        }
        hud_stat_bwe.text = bweStat.toString()
        hud_stat_connection.text = connectionStat.toString()
        hud_stat_video_send.text = videoSendStat.toString()
        hud_stat_video_recv.text = videoRecvStat.toString()
        if (videoCallEnabled) {
            fps?.also { encoderStat.append("Fps:  ").appendln(it) }
            targetBitrate?.also { encoderStat.append("Target BR: ").appendln(it) }
            actualBitrate?.also { encoderStat.append("Actual BR: ").appendln(it) }
        }
        cpuMonitor?.also {
            encoderStat.append("CPU%: ")
                .append(it.getCpuUsageCurrent())
                .append("/")
                .append(it.getCpuUsageAverage())
                .append(". Freq: ")
                .append(it.getFrequencyScaleAverage())
        }
        encoder_stat_call.text = encoderStat.toString()
    }
}