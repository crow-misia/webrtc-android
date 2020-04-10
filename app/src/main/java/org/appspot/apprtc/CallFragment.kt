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
import android.view.View
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_call.*
import org.webrtc.RendererCommon.ScalingType

/**
 * Fragment for call control.
 */
class CallFragment : Fragment(R.layout.fragment_call) {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Add buttons click events.
        button_call_disconnect.setOnClickListener { callEvents?.onCallHangUp() }
        button_call_switch_camera.setOnClickListener { callEvents?.onCameraSwitch() }
        button_call_scaling_mode.setOnClickListener {
            scalingType = if (scalingType == ScalingType.SCALE_ASPECT_FILL) {
                button_call_scaling_mode.setBackgroundResource(R.drawable.ic_action_full_screen)
                ScalingType.SCALE_ASPECT_FIT
            } else {
                button_call_scaling_mode.setBackgroundResource(R.drawable.ic_action_return_from_full_screen)
                ScalingType.SCALE_ASPECT_FILL
            }
            callEvents?.onVideoScalingSwitch(scalingType)
        }
        scalingType = ScalingType.SCALE_ASPECT_FILL
        button_call_toggle_mic.setOnClickListener {
            val enabled = callEvents?.onToggleMic() ?: false
            button_call_toggle_mic.alpha = if (enabled) 1.0f else 0.3f
        }
    }

    override fun onStart() {
        super.onStart()
        var captureSliderEnabled = false
        arguments?.also {
            val contactName = it.getString(CallActivity.EXTRA_ROOMID)
            contact_name_call.text = contactName
            videoCallEnabled = it.getBoolean(CallActivity.EXTRA_VIDEO_CALL, true)
            captureSliderEnabled = videoCallEnabled && it.getBoolean(CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED, false)
        }
        if (!videoCallEnabled) {
            button_call_switch_camera.visibility = View.INVISIBLE
        }
        if (captureSliderEnabled) {
            capture_format_slider_call.setOnSeekBarChangeListener(
                CaptureQualityController(capture_format_text_call, callEvents)
            )
        } else {
            capture_format_text_call.visibility = View.GONE
            capture_format_slider_call.visibility = View.GONE
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callEvents = requireActivity() as OnCallEvents
    }
}