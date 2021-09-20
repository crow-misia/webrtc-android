/*
 *  Copyright 2018 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.os.ParcelFileDescriptor
import org.webrtc.PeerConnection
import timber.log.Timber
import java.io.File
import java.io.IOException

class RtcEventLog(private val peerConnection: PeerConnection) {
    private var state = RtcEventLogState.INACTIVE

    internal enum class RtcEventLogState {
        INACTIVE, STARTED, STOPPED
    }

    fun start(outputFile: File?) {
        if (state == RtcEventLogState.STARTED) {
            Timber.e("RtcEventLog has already started.")
            return
        }
        val fileDescriptor = try {
            ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                        or ParcelFileDescriptor.MODE_TRUNCATE
            )
        } catch (e: IOException) {
            Timber.e("Failed to create a new file", e)
            return
        }

        // Passes ownership of the file to WebRTC.
        val success =
            peerConnection.startRtcEventLog(fileDescriptor.detachFd(), OUTPUT_FILE_MAX_BYTES)
        if (success) {
            state = RtcEventLogState.STARTED
            Timber.d("RtcEventLog started.")
        } else {
            Timber.e("Failed to start RTC event log.")
        }
    }

    fun stop() {
        if (state != RtcEventLogState.STARTED) {
            Timber.e("RtcEventLog was not started.")
            return
        }
        peerConnection.stopRtcEventLog()
        state = RtcEventLogState.STOPPED
        Timber.d("RtcEventLog stopped.")
    }

    companion object {
        private const val OUTPUT_FILE_MAX_BYTES = 10000000
    }
}