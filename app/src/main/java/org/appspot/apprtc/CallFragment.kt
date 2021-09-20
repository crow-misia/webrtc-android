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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.appspot.apprtc.databinding.FragmentCallBinding
import org.webrtc.RendererCommon.ScalingType

/**
 * Fragment for call control.
 */
class CallFragment : Fragment() {
    private lateinit var binding: FragmentCallBinding

    private var callEvents: OnCallEvents? = null
    private var scalingType: ScalingType? = null
    private var videoCallEnabled = true

    /**
     * Call control interface for container activity.
     */
    interface OnCallEvents {
        fun onCallHangUp()
        fun onCameraSwitch()
        fun onVideoScalingSwitch(scalingType: ScalingType?)
        fun onCaptureFormatChange(width: Int, height: Int, framerate: Int)
        fun onToggleMic(): Boolean
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return FragmentCallBinding.inflate(inflater, container, false).also {
            binding = it
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Add buttons click events.
        binding.buttonCallDisconnect.setOnClickListener { callEvents?.onCallHangUp() }
        binding.buttonCallSwitchCamera.setOnClickListener { callEvents?.onCameraSwitch() }
        binding.buttonCallScalingMode.setOnClickListener {
            scalingType = if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
                binding.buttonCallScalingMode.setBackgroundResource(R.drawable.ic_action_full_screen)
                ScalingType.SCALE_ASPECT_FIT
            } else {
                binding.buttonCallScalingMode.setBackgroundResource(R.drawable.ic_action_return_from_full_screen)
                ScalingType.SCALE_ASPECT_FILL
            }
            callEvents?.onVideoScalingSwitch(scalingType)
        }
        scalingType = ScalingType.SCALE_ASPECT_FILL
        binding.buttonCallToggleMic.setOnClickListener {
            val enabled = callEvents?.onToggleMic() ?: false
            binding.buttonCallToggleMic.alpha = if (enabled) 1.0f else 0.3f
        }
    }

    override fun onStart() {
        super.onStart()
        var captureSliderEnabled = false
        arguments?.also {
            val contactName = it.getString(CallActivity.EXTRA_ROOMID)
            binding.contactNameCall.text = contactName
            videoCallEnabled = it.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true)
            captureSliderEnabled = videoCallEnabled && it.getBoolean(
                CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED,
                false
            )
        }
        if (!videoCallEnabled) {
            binding.buttonCallSwitchCamera.visibility = View.INVISIBLE
        }
        if (captureSliderEnabled) {
            binding.captureFormatSliderCall.setOnSeekBarChangeListener(
                CaptureQualityController(binding.captureFormatTextCall, callEvents)
            )
        } else {
            binding.captureFormatTextCall.visibility = View.GONE
            binding.captureFormatSliderCall.visibility = View.GONE
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callEvents = requireActivity() as OnCallEvents
    }
}