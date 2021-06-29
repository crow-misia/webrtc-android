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
import android.os.ParcelFileDescriptor
import io.github.crow_misia.sdp.SdpMediaDescription
import io.github.crow_misia.sdp.SdpSessionDescription
import io.github.crow_misia.sdp.attribute.FormatAttribute
import io.github.crow_misia.sdp.attribute.RTPMapAttribute
import io.github.crow_misia.sdp.getAttribute
import io.github.crow_misia.sdp.getAttributes
import org.appspot.apprtc.AppRTCClient.SignalingParameters
import org.webrtc.*
import org.webrtc.PeerConnection.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Peer connection client implementation.
 *
 *
 * All public methods are routed to local looper thread.
 * All PeerConnectionEvents callbacks are invoked from the same looper thread.
 * This class is a singleton.
 */
class PeerConnectionClient(
    private val appContext: Context,
    private val rootEglBase: EglBase,
    private val peerConnectionParameters: PeerConnectionParameters,
    private val events: PeerConnectionEvents
) {
    private val pcObserver = PCObserver()
    private val sdpObserver = SDPObserver()
    private val statsTimer = Timer()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var preferIsac = false
    private var videoCapturerStopped = false
    private var isError = false
    private var localRender: VideoSink? = null
    private var remoteSinks: List<VideoSink> = emptyList()
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoFps = 0
    private val audioConstraints: MediaConstraints by lazy {
        // Create audio constraints.
        MediaConstraints().also {
            // added for audio performance measurements
            if (peerConnectionParameters.noAudioProcessing) {
                Timber.d("Disabling audio processing")
                it.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
                it.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
                it.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
                it.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))
            }
        }
    }
    private val sdpMediaConstraints: MediaConstraints by lazy {
        // Create SDP constraints.
        MediaConstraints().also {
            it.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            it.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideoCallEnabled.toString()))
        }
    }

    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private var queuedRemoteCandidates: MutableList<IceCandidate>? = null
    private var isInitiator = false
    // either offer or answer SDP
    private var localSdp: SessionDescription? = null
    private var videoCapturer: VideoCapturer? = null

    // enableVideo is set to true if video should be rendered and sent.
    private var renderVideo = true
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null

    // enableAudio is set to true if audio should be sent.
    private var enableAudio = true
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private val dataChannelEnabled: Boolean = peerConnectionParameters.dataChannelParameters != null

    // Enable RtcEventLog.
    private var rtcEventLog: RtcEventLog? = null

    // Implements the WebRtcAudioRecordSamplesReadyCallback interface and writes
    // recorded audio samples to an output file.
    private var saveRecordedAudioToFile: RecordedAudioToFileController? = null

    /**
     * Peer connection parameters.
     */
    class DataChannelParameters(
        val ordered: Boolean, val maxRetransmitTimeMs: Int, val maxRetransmits: Int,
        val protocol: String, val negotiated: Boolean, val id: Int
    )

    /**
     * Peer connection parameters.
     */
    class PeerConnectionParameters(
        val videoCallEnabled: Boolean,
        val loopback: Boolean,
        val tracing: Boolean,
        val videoWidth: Int,
        val videoHeight: Int,
        val videoFps: Int,
        val videoMaxBitrate: Int,
        val videoCodec: String?,
        val videoCodecHwAcceleration: Boolean,
        val videoFlexfecEnabled: Boolean,
        val audioStartBitrate: Int,
        val audioCodec: String?,
        val noAudioProcessing: Boolean,
        val aecDump: Boolean,
        val saveInputAudioToFile: Boolean,
        val useOpenSLES: Boolean,
        val disableBuiltInAEC: Boolean,
        val disableBuiltInAGC: Boolean,
        val disableBuiltInNS: Boolean,
        val disableWebRtcAGCAndHPF: Boolean,
        val enableRtcEventLog: Boolean,
        val dataChannelParameters: DataChannelParameters?
    )

    /**
     * Peer connection events.
     */
    interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        fun onLocalDescription(sdp: SessionDescription)

        /**
         * Callback fired once local Ice candidate is generated.
         */
        fun onIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once local ICE candidates are removed.
         */
        fun onIceCandidatesRemoved(candidates: List<IceCandidate>)

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        fun onIceConnected()

        /**
         * Callback fired once connection is disconnected (IceConnectionState is
         * DISCONNECTED).
         */
        fun onIceDisconnected()

        /**
         * Callback fired once DTLS connection is established (PeerConnectionState
         * is CONNECTED).
         */
        fun onConnected()

        /**
         * Callback fired once DTLS connection is disconnected (PeerConnectionState
         * is DISCONNECTED).
         */
        fun onDisconnected()

        /**
         * Callback fired once peer connection is closed.
         */
        fun onPeerConnectionClosed()

        /**
         * Callback fired once peer connection statistics is ready.
         */
        fun onPeerConnectionStatsReady(reports: List<StatsReport>)

        /**
         * Callback fired once peer connection error happened.
         */
        fun onPeerConnectionError(description: String)
    }

    /**
     * This function should only be called once.
     */
    fun createPeerConnectionFactory(options: PeerConnectionFactory.Options) {
        check(factory == null) { "PeerConnectionFactory has already been constructed" }
        executor.execute {
            createPeerConnectionFactoryInternal(options)
        }
    }

    fun createPeerConnection(localRender: VideoSink,
                             remoteSink: VideoSink,
                             videoCapturer: VideoCapturer?,
                             signalingParameters: SignalingParameters) {
        if (peerConnectionParameters.videoCallEnabled && videoCapturer == null) {
            Timber.w("Video call enabled but no video capturer provided.")
        }
        createPeerConnection(localRender, listOf(remoteSink), videoCapturer, signalingParameters)
    }

    fun createPeerConnection(localRender: VideoSink,
                             remoteSinks: List<VideoSink>,
                             videoCapturer: VideoCapturer?,
                             signalingParameters: SignalingParameters) {
        this.localRender = localRender
        this.remoteSinks = remoteSinks
        this.videoCapturer = videoCapturer
        executor.execute {
            try {
                createMediaConstraintsInternal()
                createPeerConnectionInternal(signalingParameters)
                maybeCreateAndStartRtcEventLog()
            } catch (e: Exception) {
                reportError("Failed to create peer connection: " + e.message)
                throw e
            }
        }
    }

    fun close() {
        executor.execute { closeInternal() }
    }

    private val isVideoCallEnabled: Boolean
        get() = peerConnectionParameters.videoCallEnabled && videoCapturer != null

    private fun createPeerConnectionFactoryInternal(options: PeerConnectionFactory.Options) {
        isError = false
        if (peerConnectionParameters.tracing) {
            appContext.getExternalFilesDir(null)?.also {
                PeerConnectionFactory.startInternalTracingCapture(File(it, "webrtc-trace.txt").absolutePath)
            }
        }

        // Check if ISAC is used by default.
        preferIsac = (peerConnectionParameters.audioCodec != null
                && peerConnectionParameters.audioCodec == AUDIO_CODEC_ISAC)

        // It is possible to save a copy in raw PCM format on a file by checking
        // the "Save input audio to file" checkbox in the Settings UI. A callback
        // interface is set when this flag is enabled. As a result, a copy of recorded
        // audio samples are provided to this client directly from the native audio
        // layer in Java.
        if (peerConnectionParameters.saveInputAudioToFile) {
            if (peerConnectionParameters.useOpenSLES) {
                // TODO(henrika): ensure that the UI reflects that if OpenSL ES is selected,
                // then the "Save inut audio to file" option shall be grayed out.
                Timber.e("Recording of input audio is not supported for OpenSL ES")
            } else {
                Timber.d("Enable recording of microphone input audio to file")
                saveRecordedAudioToFile = RecordedAudioToFileController(appContext, executor)
            }
        }
        val adm = createJavaAudioDevice()

        // Create peer connection factory.
        Timber.d("Factory networkIgnoreMask option: %d", options.networkIgnoreMask)
        val enableH264HighProfile = VIDEO_CODEC_H264_HIGH == peerConnectionParameters.videoCodec
        val encoderFactory: VideoEncoderFactory
        val decoderFactory: VideoDecoderFactory
        if (peerConnectionParameters.videoCodecHwAcceleration) {
            encoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, enableH264HighProfile)
            decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        } else {
            encoderFactory = SoftwareVideoEncoderFactory()
            decoderFactory = SoftwareVideoDecoderFactory()
        }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        Timber.d("Peer connection factory created.")
        adm.release()
    }

    fun createJavaAudioDevice(): AudioDeviceModule {
        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters.useOpenSLES) {
            Timber.w("External OpenSLES ADM not implemented yet.")
            // TODO(magjed): Add support for external OpenSLES ADM.
        }

        // Set audio record error callbacks.
        val audioRecordErrorCallback = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Timber.e("onWebRtcAudioRecordInitError: %s", errorMessage)
                reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) {
                Timber.e("onWebRtcAudioRecordStartError: %s. %s", errorCode, errorMessage)
                reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Timber.e("onWebRtcAudioRecordError: %s", errorMessage)
                reportError(errorMessage)
            }
        }
        val audioTrackErrorCallback = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Timber.e("onWebRtcAudioTrackInitError: %s", errorMessage)
                reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String) {
                Timber.e("onWebRtcAudioTrackStartError: %s. %s", errorCode, errorMessage)
                reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Timber.e("onWebRtcAudioTrackError: %s", errorMessage)
                reportError(errorMessage)
            }
        }

        // Set audio record state callbacks.
        val audioRecordStateCallback = object : JavaAudioDeviceModule.AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                Timber.i("Audio recording starts")
            }

            override fun onWebRtcAudioRecordStop() {
                Timber.i("Audio recording stops")
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback = object : JavaAudioDeviceModule.AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                Timber.i("Audio playout starts")
            }

            override fun onWebRtcAudioTrackStop() {
                Timber.i("Audio playout stops")
            }
        }
        return JavaAudioDeviceModule.builder(appContext)
            .setSamplesReadyCallback(saveRecordedAudioToFile)
            .setUseHardwareAcousticEchoCanceler(!peerConnectionParameters.disableBuiltInAEC)
            .setUseHardwareNoiseSuppressor(!peerConnectionParameters.disableBuiltInNS)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .createAudioDeviceModule()
    }

    private fun createMediaConstraintsInternal() {
        // Create video constraints if video call is enabled.
        if (isVideoCallEnabled) {
            videoWidth = peerConnectionParameters.videoWidth
            videoHeight = peerConnectionParameters.videoHeight
            videoFps = peerConnectionParameters.videoFps

            // If video resolution is not specified, default to HD.
            if (videoWidth == 0 || videoHeight == 0) {
                videoWidth = HD_VIDEO_WIDTH
                videoHeight = HD_VIDEO_HEIGHT
            }

            // If fps is not specified, default to 30.
            if (videoFps == 0) {
                videoFps = 30
            }
            Timber.d("Capturing format: %dx%d@%d", videoWidth, videoHeight, videoFps)
        }
    }

    private fun createPeerConnectionInternal(signalingParameters: SignalingParameters) {
        val factory = factory ?: run {
            Timber.e("Peerconnection factory is not created")
            return
        }
        if (isError) {
            Timber.e("Peerconnection factory is not created")
            return
        }

        Timber.d("Create peer connection.")
        queuedRemoteCandidates = arrayListOf()
        val rtcConfig = RTCConfiguration(signalingParameters.iceServers).also {
            // TCP candidates are only useful when connecting to a server that supports
            // ICE-TCP.
            it.tcpCandidatePolicy = TcpCandidatePolicy.DISABLED
            it.bundlePolicy = BundlePolicy.MAXBUNDLE
            it.rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE
            it.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Use ECDSA encryption.
            it.keyType = KeyType.ECDSA
            // Enable DTLS for normal calls and disable for loopback calls.
            it.enableDtlsSrtp = !peerConnectionParameters.loopback
            it.sdpSemantics = SdpSemantics.UNIFIED_PLAN
        }
        val peerConnection = factory.createPeerConnection(rtcConfig, pcObserver) ?: return
        this.peerConnection = peerConnection
        if (dataChannelEnabled) {
            val init = DataChannel.Init()
            peerConnectionParameters.dataChannelParameters?.also {
                init.ordered = it.ordered
                init.negotiated = it.negotiated
                init.maxRetransmits = it.maxRetransmits
                init.maxRetransmitTimeMs = it.maxRetransmitTimeMs
                init.id = it.id
                init.protocol = it.protocol
            }
            dataChannel = peerConnection.createDataChannel("ApprtcDemo data", init)
        }
        isInitiator = false

        // Set INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)
        val mediaStreamLabels = listOf("ARDAMS")
        if (isVideoCallEnabled) {
            peerConnection.addTrack(createVideoTrack(videoCapturer), mediaStreamLabels)
            // We can add the renderers right away because we don't need to wait for an
            // answer to get the remote track.
            remoteVideoTrack = getRemoteVideoTrack()?.also {
                it.setEnabled(renderVideo)
                for (remoteSink in remoteSinks) {
                    it.addSink(remoteSink)
                }
            }
        }
        peerConnection.addTrack(createAudioTrack(), mediaStreamLabels)
        if (isVideoCallEnabled) {
            findVideoSender()
        }
        if (peerConnectionParameters.aecDump) {
            try {
                val aecDumpFileDescriptor = ParcelFileDescriptor.open(
                    File(appContext.getExternalFilesDir(null), "Download/audio.aecdump"),
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
                            or ParcelFileDescriptor.MODE_TRUNCATE
                )
                factory.startAecDump(aecDumpFileDescriptor.detachFd(), -1)
            } catch (e: IOException) {
                Timber.e(e, "Can not open aecdump file")
            }
        }
        if (saveRecordedAudioToFile?.start() == true) {
            Timber.d("Recording input audio to file is activated")
        }
        Timber.d("Peer connection created.")
    }

    private fun createRtcEventLogOutputFile(): File {
        val dateFormat: DateFormat = SimpleDateFormat("yyyyMMdd_hhmm_ss", Locale.getDefault())
        val date = Date()
        val outputFileName = "event_log_" + dateFormat.format(date) + ".log"
        return File(appContext.getDir(RTCEVENTLOG_OUTPUT_DIR_NAME, Context.MODE_PRIVATE), outputFileName)
    }

    private fun maybeCreateAndStartRtcEventLog() {
        val peerConnection = peerConnection ?: return

        if (!peerConnectionParameters.enableRtcEventLog) {
            Timber.d("RtcEventLog is disabled.")
            return
        }
        rtcEventLog = RtcEventLog(peerConnection).also {
            it.start(createRtcEventLogOutputFile())
        }
    }

    private fun closeInternal() {
        if (peerConnectionParameters.aecDump) {
            factory?.stopAecDump()
        }
        Timber.d("Closing peer connection.")
        statsTimer.cancel()
        dataChannel?.dispose()
        dataChannel = null
        // RtcEventLog should stop before the peer connection is disposed.
        rtcEventLog?.stop()
        rtcEventLog = null
        peerConnection?.dispose()
        peerConnection = null
        Timber.d("Closing audio source.")
        audioSource?.dispose()
        audioSource = null
        Timber.d("Stopping capture.")
        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
        videoCapturerStopped = true
        videoCapturer?.dispose()
        videoCapturer = null
        Timber.d("Closing video source.")
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        saveRecordedAudioToFile?.also {
            Timber.d("Closing audio file for recorded input audio.")
            it.stop()
            saveRecordedAudioToFile = null
        }
        localRender = null
        remoteSinks = emptyList()
        Timber.d("Closing peer connection factory.")
        factory?.dispose()
        factory = null
        rootEglBase.release()
        Timber.d("Closing peer connection done.")
        events.onPeerConnectionClosed()
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    val isHDVideo: Boolean
        get() = isVideoCallEnabled && videoWidth * videoHeight >= 1280 * 720

    private fun getStats() {
        val peerConnection = peerConnection ?: return
        if (isError) {
            return
        }
        peerConnection.getStats({ reports -> events.onPeerConnectionStatsReady(reports.asList()) }, null)
    }

    fun enableStatsEvents(enable: Boolean, periodMs: Int) {
        if (enable) {
            try {
                statsTimer.schedule(object : TimerTask() {
                    override fun run() {
                        executor.execute { getStats() }
                    }
                }, 0, periodMs.toLong())
            } catch (e: Exception) {
                Timber.e(e, "Can not schedule statistics timer")
            }
        } else {
            statsTimer.cancel()
        }
    }

    fun setAudioEnabled(enable: Boolean) {
        executor.execute {
            enableAudio = enable
            localAudioTrack?.setEnabled(enable)
        }
    }

    fun setVideoEnabled(enable: Boolean) {
        executor.execute {
            renderVideo = enable
            localVideoTrack?.setEnabled(enable)
            remoteVideoTrack?.setEnabled(enable)
        }
    }

    fun createOffer() {
        executor.execute {
            val peerConnection = peerConnection ?: return@execute
            if (!isError) {
                Timber.d("PC create OFFER")
                isInitiator = true
                peerConnection.createOffer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun createAnswer() {
        executor.execute {
            val peerConnection = peerConnection ?: return@execute
            if (!isError) {
                Timber.d("PC create ANSWER")
                isInitiator = false
                peerConnection.createAnswer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            val peerConnection = peerConnection ?: return@execute
            if (!isError) {
                queuedRemoteCandidates?.add(candidate) ?: run {
                    peerConnection.addIceCandidate(candidate)
                }
            }
        }
    }

    fun removeRemoteIceCandidates(candidates: List<IceCandidate>) {
        executor.execute {
            val peerConnection = peerConnection ?: return@execute
            if (isError) {
                return@execute
            }
            // Drain the queued remote candidates if there is any so that
            // they are processed in the proper order.
            drainCandidates()
            peerConnection.removeIceCandidates(candidates.toTypedArray())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            val peerConnection = peerConnection ?: return@execute
            if (isError) {
                return@execute
            }
            val sdpDescription = SdpSessionDescription.parse(sdp.description)
            if (preferIsac) {
                preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true)
            }
            if (isVideoCallEnabled) {
                preferCodec(sdpDescription, getSdpVideoCodecName(peerConnectionParameters), false)
            }
            if (peerConnectionParameters.audioStartBitrate > 0) {
                setStartBitrate(sdpDescription, AUDIO_CODEC_OPUS, false, peerConnectionParameters.audioStartBitrate)
            }
            Timber.d("Set remote SDP.")
            val sdpRemote = SessionDescription(sdp.type, sdpDescription.toString())
            peerConnection.setRemoteDescription(sdpObserver, sdpRemote)
        }
    }

    fun stopVideoSource() {
        executor.execute {
            val videoCapturer = videoCapturer ?: return@execute
            if (!videoCapturerStopped) {
                Timber.d("Stop video source.")
                try {
                    videoCapturer.stopCapture()
                } catch (e: InterruptedException) {
                }
                videoCapturerStopped = true
            }
        }
    }

    fun startVideoSource() {
        executor.execute {
            val videoCapturer = videoCapturer ?: return@execute
            if (videoCapturerStopped) {
                Timber.d("Restart video source.")
                videoCapturer.startCapture(videoWidth, videoHeight, videoFps)
                videoCapturerStopped = false
            }
        }
    }

    fun setVideoMaxBitrate(maxBitrateKbps: Int?) {
        executor.execute {
            peerConnection ?: return@execute
            val localVideoSender = localVideoSender ?: return@execute
            if (isError) {
                return@execute
            }
            Timber.d("Requested max video bitrate: %d", maxBitrateKbps)
            val parameters = localVideoSender.parameters
            if (parameters.encodings.size == 0) {
                Timber.w("RtpParameters are not ready.")
                return@execute
            }
            for (encoding in parameters.encodings) {
                // Null value means no limit.
                encoding.maxBitrateBps = if (maxBitrateKbps == null) null else maxBitrateKbps * BPS_IN_KBPS
            }
            if (!localVideoSender.setParameters(parameters)) {
                Timber.e("RtpSender.setParameters failed.")
            }
            Timber.d("Configured max video bitrate to: %d", maxBitrateKbps)
        }
    }

    private fun reportError(errorMessage: String) {
        Timber.e("Peerconnection error: %s", errorMessage)
        executor.execute {
            if (!isError) {
                events.onPeerConnectionError(errorMessage)
                isError = true
            }
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        val factory = requireNotNull(factory)
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).also {
            it.setEnabled(enableAudio)
        }
        return localAudioTrack
    }

    private fun createVideoTrack(capturer: VideoCapturer?): VideoTrack? {
        capturer ?: return null

        val factory = requireNotNull(factory)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
        videoSource = factory.createVideoSource(capturer.isScreencast).also {
            capturer.initialize(surfaceTextureHelper, appContext, it.capturerObserver)
            capturer.startCapture(videoWidth, videoHeight, videoFps)
        }
        val localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).also {
            it.setEnabled(renderVideo)
            it.addSink(localRender)
        }
        this.localVideoTrack = localVideoTrack
        return localVideoTrack
    }

    private fun findVideoSender() {
        val senders = peerConnection?.senders ?: emptyList()
        senders.firstOrNull { it.track()?.kind() == VIDEO_TRACK_TYPE }?.also {
            Timber.d("Found video sender.")
            localVideoSender = it
        }
    }

    // Returns the remote VideoTrack, assuming there is only one.
    private fun getRemoteVideoTrack(): VideoTrack? {
        val transceivers = peerConnection?.transceivers ?: emptyList()
        for (transceiver in transceivers) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) {
                return track
            }
        }
        return null
    }

    private fun drainCandidates() {
        queuedRemoteCandidates?.also {
            Timber.d("Add %d remote candidates", it.size)
            for (candidate in it) {
                peerConnection?.addIceCandidate(candidate)
            }
            queuedRemoteCandidates = null
        }
    }

    private fun switchCameraInternal() {
        if (videoCapturer is CameraVideoCapturer) {
            if (!isVideoCallEnabled || isError) {
                Timber.e("Failed to switch camera. Video: %b. Error : %b", isVideoCallEnabled, isError)
                return  // No video is sent or only one camera is available or error happened.
            }
            Timber.d("Switch camera")
            val cameraVideoCapturer = videoCapturer as CameraVideoCapturer
            cameraVideoCapturer.switchCamera(null)
        } else {
            Timber.d("Will not switch camera, video caputurer is not a camera")
        }
    }

    fun switchCamera() {
        executor.execute { switchCameraInternal() }
    }

    fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        executor.execute {
            changeCaptureFormatInternal(
                width,
                height,
                framerate
            )
        }
    }

    private fun changeCaptureFormatInternal(width: Int, height: Int, framerate: Int) {
        if (!isVideoCallEnabled || isError || videoCapturer == null) {
            Timber.e("Failed to change capture format. Video: %b. Error : %b", isVideoCallEnabled, isError)
            return
        }
        Timber.d("changeCaptureFormat: %dx%dx@%d", width, height, framerate)
        videoSource?.adaptOutputFormat(width, height, framerate)
    }

    // Implementation detail: observe ICE & stream changes and react accordingly.
    private inner class PCObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            executor.execute {
                events.onIceCandidate(candidate)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            executor.execute {
                events.onIceCandidatesRemoved(candidates.asList())
            }
        }

        override fun onSignalingChange(newState: SignalingState) {
            Timber.d("SignalingState: %s", newState)
        }

        override fun onIceConnectionChange(newState: IceConnectionState) {
            executor.execute {
                Timber.d("IceConnectionState: %s", newState)
                when (newState) {
                    IceConnectionState.CONNECTED -> events.onIceConnected()
                    IceConnectionState.DISCONNECTED -> events.onIceDisconnected()
                    IceConnectionState.FAILED -> reportError("ICE connection failed.")
                    else -> Unit
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnectionState) {
            executor.execute {
                Timber.d("PeerConnectionState: %s", newState)
                when (newState) {
                    PeerConnectionState.CONNECTED -> events.onConnected()
                    PeerConnectionState.DISCONNECTED -> events.onDisconnected()
                    PeerConnectionState.FAILED -> reportError("DTLS connection failed.")
                    else -> Unit
                }
            }
        }

        override fun onIceGatheringChange(newState: IceGatheringState) {
            Timber.d("IceGatheringState: %s", newState)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Timber.d("IceConnectionReceiving changed to %b", receiving)
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            Timber.d("Selected candidate pair changed because: %s", event)
        }

        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {
            Timber.d("New Data channel %s", dc.label())
            if (!dataChannelEnabled) return
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {
                    Timber.d("Data channel buffered amount changed: %s: %s", dc.label(), dc.state())
                }

                override fun onStateChange() {
                    Timber.d("Data channel state changed: %s: %s", dc.label(), dc.state())
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) {
                        Timber.d("Received binary msg over %s", dc)
                        return
                    }
                    val data = buffer.data
                    val bytes = ByteArray(data.capacity())
                    data.get(bytes)
                    val strData = bytes.toString(Charsets.UTF_8)
                    Timber.d("Got msg: %s over %s", strData, dc)
                }
            })
        }

        override fun onRenegotiationNeeded() {
            // No need to do anything; AppRTC follows a pre-agreed-upon
            // signaling/negotiation protocol.
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private inner class SDPObserver : SdpObserver {
        override fun onCreateSuccess(origSdp: SessionDescription) {
            if (localSdp != null) {
                reportError("Multiple SDP create.")
                return
            }
            val sdpDescription = SdpSessionDescription.parse(origSdp.description)
            if (preferIsac) {
                preferCodec(sdpDescription, AUDIO_CODEC_ISAC, true)
            }
            if (isVideoCallEnabled) {
                preferCodec(sdpDescription, getSdpVideoCodecName(peerConnectionParameters), false)
            }
            val sdp = SessionDescription(origSdp.type, sdpDescription.toString())
            localSdp = sdp
            executor.execute {
                val peerConnection = peerConnection ?: return@execute
                if (!isError) {
                    Timber.d("Set local SDP from %s: %s", sdp.type, sdp)
                    peerConnection.setLocalDescription(sdpObserver, sdp)
                }
            }
        }

        override fun onSetSuccess() {
            executor.execute {
                val peerConnection = peerConnection ?: return@execute
                if (isError) {
                    return@execute
                }
                if (isInitiator) {
                    // For offering peer connection we first create offer and set
                    // local SDP, then after receiving answer set remote SDP.
                    if (peerConnection.remoteDescription == null) {
                        // We've just set our local SDP so time to send it.
                        Timber.d("Local SDP set successfully")
                        localSdp?.also { events.onLocalDescription(it) }
                    } else {
                        // We've just set remote description, so drain remote
                        // and send local ICE candidates.
                        Timber.d("Remote SDP set successfully")
                        drainCandidates()
                    }
                } else {
                    // For answering peer connection we set remote SDP and then
                    // create answer and set local SDP.
                    if (peerConnection.localDescription == null) {
                        // We've just set remote SDP - do nothing for now -
                        // answer will be created soon.
                        Timber.d("Remote SDP set successfully")

                    } else {
                        // We've just set our local SDP so time to send it, drain
                        // remote and send local ICE candidates.
                        Timber.d("Local SDP set successfully")
                        localSdp?.also { events.onLocalDescription(it) }
                        drainCandidates()
                    }
                }
            }
        }

        override fun onCreateFailure(error: String) {
            reportError("createSDP error: $error")
        }

        override fun onSetFailure(error: String) {
            reportError("setSDP error: $error")
        }
    }

    companion object {
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        const val AUDIO_TRACK_ID = "ARDAMSa0"
        const val VIDEO_TRACK_TYPE = "video"
        private const val VIDEO_CODEC_VP8 = "VP8"
        private const val VIDEO_CODEC_VP9 = "VP9"
        private const val VIDEO_CODEC_H264 = "H264"
        private const val VIDEO_CODEC_H264_BASELINE = "H264 Baseline"
        private const val VIDEO_CODEC_H264_HIGH = "H264 High"
        private const val AUDIO_CODEC_OPUS = "opus"
        private const val AUDIO_CODEC_ISAC = "ISAC"
        private const val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
        private const val VIDEO_FLEXFEC_FIELDTRIAL =
            "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/"
        private const val VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL =
            "WebRTC-IntelVP8/Enabled/"
        private const val DISABLE_WEBRTC_AGC_FIELDTRIAL =
            "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/"
        private const val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"
        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"
        private const val DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement"
        private const val HD_VIDEO_WIDTH = 1280
        private const val HD_VIDEO_HEIGHT = 720
        private const val BPS_IN_KBPS = 1000
        private const val RTCEVENTLOG_OUTPUT_DIR_NAME = "rtc_event_log"

        // Executor thread is started once in private ctor and is used for all
        // peer connection API calls to ensure new peer connection factory is
        // created on the same thread as previously destroyed factory.
        private val executor =
            Executors.newSingleThreadExecutor()

        private fun getSdpVideoCodecName(parameters: PeerConnectionParameters): String {
            return when (parameters.videoCodec) {
                VIDEO_CODEC_VP8 -> VIDEO_CODEC_VP8
                VIDEO_CODEC_VP9 -> VIDEO_CODEC_VP9
                VIDEO_CODEC_H264_HIGH, VIDEO_CODEC_H264_BASELINE -> VIDEO_CODEC_H264
                else -> VIDEO_CODEC_VP8
            }
        }

        private fun getFieldTrials(peerConnectionParameters: PeerConnectionParameters): String {
            return buildString {
                if (peerConnectionParameters.videoFlexfecEnabled) {
                    append(VIDEO_FLEXFEC_FIELDTRIAL)
                    Timber.d("Enable FlexFEC field trial.")
                }
                append(VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL)
                if (peerConnectionParameters.disableWebRtcAGCAndHPF) {
                    append(DISABLE_WEBRTC_AGC_FIELDTRIAL)
                    Timber.d("Disable WebRTC AGC field trial.")
                }
            }
        }

        private fun setStartBitrate(sdpDescription: SdpSessionDescription, codec: String, isVideoCodec: Boolean, bitrateKbps: Int) {
            sdpDescription.getMediaDescriptions()
                .mapNotNull { media ->
                    // Search for codec rtpmap in format
                    // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
                    media.getAttribute<RTPMapAttribute>()?.let {
                        Timber.d("Found %s rtpmap %s", codec, it)
                        media to it
                    }
                }
                .forEach { (media, rtmp) ->
                    // Check if a=fmtp string already exist in remote SDP for this codec and
                    // update it with new bitrate parameter.
                    media.getAttributes<FormatAttribute>()
                        .filter { it.format == rtmp.payloadType }
                        .forEach {
                            Timber.d("Found %s %s", codec, it)

                            if (isVideoCodec) {
                                it.addParameter(VIDEO_CODEC_PARAM_START_BITRATE, bitrateKbps)
                            } else {
                                it.addParameter(AUDIO_CODEC_PARAM_BITRATE, bitrateKbps * 1000)
                            }
                            Timber.d("Update remote SDP line: %s", it)
                            return
                        }
                }
        }

        private fun movePayloadTypesToFront(preferredPayloadTypes: List<String>, mediaDescription: SdpMediaDescription) {
            // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
            val formats = mediaDescription.formats
            // Reconstruct the line with |preferredPayloadTypes| moved to the beginning of the payload types.
            formats.removeAll(preferredPayloadTypes)
            formats.addAll(0, preferredPayloadTypes)
        }

        private fun preferCodec(sdpDescription: SdpSessionDescription, codec: String, isAudio: Boolean) {
            val type = if (isAudio) "audio" else "video"
            val mediaDescription = sdpDescription.getMediaDescriptions().firstOrNull { it.type == type } ?: run {
                Timber.w("No mediaDescription line, so can't prefer %s", codec)
                return
            }
            // A list with all the payload types with name |codec|. The payload types are integers in the
            // range 96-127, but they are stored as strings here.
            val codecPayloadTypes = mediaDescription.getAttributes<RTPMapAttribute>()
                .filter { it.encodingName == codec }
                .map { it.payloadType.toString() }
                .toList()

            if (codecPayloadTypes.isEmpty()) {
                Timber.w("No payload types with name %s", codec)
                return
            }
            movePayloadTypesToFront(codecPayloadTypes, mediaDescription)
        }
    }

    /**
     * Create a PeerConnectionClient with the specified parameters. PeerConnectionClient takes
     * ownership of |eglBase|.
     */
    init {
        Timber.d("Preferred video codec: %s", getSdpVideoCodecName(peerConnectionParameters))
        val fieldTrials = getFieldTrials(peerConnectionParameters)
        executor.execute {
            Timber.d("Initialize WebRTC. Field trials: %s", fieldTrials)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setFieldTrials(fieldTrials)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
        }
    }
}