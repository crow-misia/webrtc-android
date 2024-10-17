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

import android.content.Context
import android.media.AudioFormat
import android.os.Environment
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback
import timber.log.Timber
import java.io.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantLock

/**
 * Implements the AudioRecordSamplesReadyCallback interface and writes
 * recorded raw audio samples to an output file.
 */
class RecordedAudioToFileController(
    appContext: Context,
    private val executor: ExecutorService,
) : SamplesReadyCallback {
    private val lock = ReentrantLock()
    private var rawAudioFileOutputStream: OutputStream? = null
    private var isRunning = false
    private var fileSizeInBytes: Long = 0
    private val externalStorageDirectory = appContext.getExternalFilesDir(null)

    /**
     * Should be called on the same executor thread as the one provided at
     * construction.
     */
    fun start(): Boolean {
        Timber.d("start")
        if (!isExternalStorageWritable) {
            Timber.e("Writing to external media is not possible")
            return false
        }
        try {
            lock.lock()
            isRunning = true
        } finally {
            lock.unlock()
        }
        return true
    }

    /**
     * Should be called on the same executor thread as the one provided at
     * construction.
     */
    fun stop() {
        Timber.d("stop")
        try {
            lock.lock()
            isRunning = false
            try {
                rawAudioFileOutputStream?.close()
            } catch (e: IOException) {
                Timber.e(e, "Failed to close file with saved input audio:")
            }
            rawAudioFileOutputStream = null
            fileSizeInBytes = 0
        } finally {
            lock.unlock()
        }
    }

    // Checks if external storage is available for read and write.
    private val isExternalStorageWritable: Boolean
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state
        }

    // Utilizes audio parameters to create a file name which contains sufficient
    // information so that the file can be played using an external file player.
    // Example: /sdcard/recorded_audio_16bits_48000Hz_mono.pcm.
    private fun openRawAudioOutputFile(sampleRate: Int, channelCount: Int) {
        val outputFile = File(
            externalStorageDirectory,
            "recorded_audio_16bits_" + sampleRate.toString() + "Hz" + (if (channelCount == 1) "_mono" else "_stereo") + ".pcm"
        )
        try {
            rawAudioFileOutputStream = FileOutputStream(outputFile)
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Failed to open audio output file: ")
        }
        Timber.d("Opened file for recording: %s", outputFile)
    }

    // Called when new audio samples are ready.
    override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
        // The native audio layer on Android should use 16-bit PCM format.
        if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) {
            Timber.e("Invalid audio format")
            return
        }
        try {
            lock.lock()
            // Abort early if stop() has been called.
            if (!isRunning) {
                return
            }
            // Open a new file for the first callback only since it allows us to add audio parameters to
            // the file name.
            if (rawAudioFileOutputStream == null) {
                openRawAudioOutputFile(samples.sampleRate, samples.channelCount)
                fileSizeInBytes = 0
            }
        } finally {
            lock.unlock()
        }
        // Append the recorded 16-bit audio samples to the open output file.
        executor.execute {
            val rawAudioFileOutputStream = rawAudioFileOutputStream ?: return@execute
            try {
                // Set a limit on max file size. 58348800 bytes corresponds to
                // approximately 10 minutes of recording in mono at 48kHz.
                if (fileSizeInBytes < MAX_FILE_SIZE_IN_BYTES) {
                    // Writes samples.getData().length bytes to output stream.
                    rawAudioFileOutputStream.write(samples.data)
                    fileSizeInBytes += samples.data.size.toLong()
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to write audio to file:")
            }
        }
    }

    companion object {
        private const val MAX_FILE_SIZE_IN_BYTES = 58348800L
    }

    init {
        Timber.d("ctor")
    }
}
