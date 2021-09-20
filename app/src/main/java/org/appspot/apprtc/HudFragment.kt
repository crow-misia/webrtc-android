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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.appspot.apprtc.databinding.FragmentHudBinding
import org.webrtc.RTCStatsReport

/**
 * Fragment for HUD statistics display.
 */
class HudFragment : Fragment() {
    private lateinit var binding: FragmentHudBinding

    private var videoCallEnabled = false
    private var displayHud = false

    @Volatile
    private var isRunning = false

    private val cpuMonitor by lazy {
        if (CpuMonitor.isSupported()) {
            CpuMonitor(requireContext())
        } else null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FragmentHudBinding.inflate(inflater, container, false).also {
            binding = it
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.buttonToggleDebug.setOnClickListener {
            if (displayHud) {
                val visibility =
                    if (binding.hudStatCall.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                binding.hudStatCall.visibility = visibility
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
        binding.hudStatCall.visibility = visibility
        binding.buttonToggleDebug.visibility = visibility
        isRunning = true
        cpuMonitor?.resume()
    }

    override fun onStop() {
        isRunning = false
        cpuMonitor?.pause()
        super.onStop()
    }

    fun updateEncoderStatistics(report: RTCStatsReport) {
        if (!isRunning || !displayHud) {
            return
        }
        val stat = buildString {
            cpuMonitor?.also {
                append("CPU%: ")
                append(it.getCpuUsageCurrent())
                append("/")
                append(it.getCpuUsageAverage())
                append(". Freq: ")
                append(it.getFrequencyScaleAverage())
                append('\n')
            }
            report.statsMap.values.filter { it.type == "codec" }.joinTo(this, "\n") {
                it.toString()
            }
        }
        binding.hudStatCall.text = stat
    }
}