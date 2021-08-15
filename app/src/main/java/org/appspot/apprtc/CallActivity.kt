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
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentTransaction
import org.appspot.apprtc.AppRTCAudioManager.AudioManagerEvents
import org.appspot.apprtc.AppRTCClient.*
import org.appspot.apprtc.CallFragment.OnCallEvents
import org.appspot.apprtc.PeerConnectionClient.*
import org.appspot.apprtc.databinding.ActivityCallBinding
import org.webrtc.*
import org.webrtc.RendererCommon.ScalingType
import timber.log.Timber
import java.io.IOException

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
class CallActivity : AppCompatActivity(), SignalingEvents, PeerConnectionEvents, OnCallEvents {
    private class ProxyVideoSink : VideoSink {
        var target: VideoSink? = null
            @Synchronized
            set

        @Synchronized
        override fun onFrame(frame: VideoFrame) {
            target?.also {
                it.onFrame(frame)
            } ?: run {
                Timber.d("Dropping frame in proxy because target is null.")
            }
        }
    }

    private lateinit var binding: ActivityCallBinding

    private val remoteProxyRenderer = ProxyVideoSink()
    private val localProxyVideoSink = ProxyVideoSink()
    private var peerConnectionClient: PeerConnectionClient? = null
    private var appRtcClient: AppRTCClient? = null
    private lateinit var signalingParameters: SignalingParameters
    private var audioManager: AppRTCAudioManager? = null
    private var videoFileRenderer: VideoFileRenderer? = null
    private val remoteSinks: MutableList<VideoSink> = arrayListOf()
    private var logToast: Toast? = null
    private var commandLineRun = false
    private var activityRunning = false
    private lateinit var roomConnectionParameters: RoomConnectionParameters
    private lateinit var peerConnectionParameters: PeerConnectionParameters
    private var connected = false
    private var isError = false
    private var callControlFragmentVisible = true
    private var callStartedTimeMs: Long = 0
    private var micEnabled = true
    private var screencaptureEnabled = false

    // True if local view is in the fullscreen renderer.
    private var isSwappedFeeds = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set window styles for fullscreen-window size. Needs to be done before
        // adding content.
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        super.onCreate(savedInstanceState)

        binding = ActivityCallBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        Thread.setDefaultUncaughtExceptionHandler(UnhandledExceptionHandler(this))

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.decorView.systemUiVisibility = systemUiVisibility
        connected = false

        // Create UI controls.
        val callFragment = CallFragment()
        val hudFragment = HudFragment()

        // Show/hide call control fragment on view click.
        val listener = View.OnClickListener { toggleCallControlFragmentVisibility() }

        // Swap feeds on pip view click.
        binding.pipVideoView.setOnClickListener { setSwappedFeeds(!isSwappedFeeds) }
        binding.fullscreenVideoView.setOnClickListener(listener)
        remoteSinks.add(remoteProxyRenderer)
        val intent = intent
        val eglBase = EglBase.create()

        // Create video renderers.
        binding.pipVideoView.init(eglBase.eglBaseContext, null)
        binding.pipVideoView.setScalingType(ScalingType.SCALE_ASPECT_FIT)
        intent.getStringExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE)?.also { it ->
            // When saveRemoteVideoToFile is set we save the video from the remote to a file.
            val videoOutWidth = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, 0)
            val videoOutHeight = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, 0)
            try {
                videoFileRenderer = VideoFileRenderer(it, videoOutWidth, videoOutHeight, eglBase.eglBaseContext)
                    .also { renderer -> remoteSinks.add(renderer) }
            } catch (e: IOException) {
                throw RuntimeException("Failed to open video file for output: $it", e)
            }
        }
        binding.fullscreenVideoView.also {
            it.init(eglBase.eglBaseContext, null)
            it.setScalingType(ScalingType.SCALE_ASPECT_FILL)
            it.setEnableHardwareScaler(false)
        }
        binding.pipVideoView.also {
            it.setZOrderMediaOverlay(true)
            it.setEnableHardwareScaler(true)
        }
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true)

        // Check for mandatory permissions.
        MANDATORY_PERMISSIONS.forEach { permission ->
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission $permission is not granted")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
        }
        val roomUri = intent.data ?: run {
            logAndToast(getString(R.string.missing_url))
            Timber.e("Didn't get any URL in intent!")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        // Get Intent parameters.
        val roomId = intent.getStringExtra(EXTRA_ROOMID)
        Timber.d("Room ID: %s", roomId)
        if (roomId.isNullOrEmpty()) {
            logAndToast(getString(R.string.missing_url))
            Timber.e("Incorrect room ID in intent!")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false)
        val tracing = intent.getBooleanExtra(EXTRA_TRACING, false)
        var videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0)
        var videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0)
        screencaptureEnabled = intent.getBooleanExtra(EXTRA_SCREENCAPTURE, false)
        // If capturing format is not specified for screencapture, use screen resolution.
        if (screencaptureEnabled && videoWidth == 0 && videoHeight == 0) {
            val displayMetrics = getDisplayMetrics()?.also {
                videoWidth = it.widthPixels
                videoHeight = it.heightPixels
            }
        }
        var dataChannelParameters: DataChannelParameters? = null
        if (intent.getBooleanExtra(EXTRA_DATA_CHANNEL_ENABLED, false)) {
            dataChannelParameters = DataChannelParameters(
                ordered = intent.getBooleanExtra(EXTRA_ORDERED, true),
                maxRetransmitTimeMs = intent.getIntExtra(EXTRA_MAX_RETRANSMITS_MS, -1),
                maxRetransmits = intent.getIntExtra(EXTRA_MAX_RETRANSMITS, -1),
                protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: "",
                negotiated = intent.getBooleanExtra(EXTRA_NEGOTIATED, false),
                id = intent.getIntExtra(EXTRA_ID, -1)
            )
        }
        peerConnectionParameters = PeerConnectionParameters(
            intent.getBooleanExtra(EXTRA_VIDEO_CALL, true),
            loopback,
            tracing,
            videoWidth,
            videoHeight,
            intent.getIntExtra(EXTRA_VIDEO_FPS, 0),
            intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0),
            intent.getStringExtra(EXTRA_VIDEOCODEC),
            intent.getBooleanExtra(EXTRA_HWCODEC_ENABLED, true),
            intent.getBooleanExtra(EXTRA_FLEXFEC_ENABLED, false),
            intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0),
            intent.getStringExtra(EXTRA_AUDIOCODEC),
            intent.getBooleanExtra(EXTRA_NOAUDIOPROCESSING_ENABLED, false),
            intent.getBooleanExtra(EXTRA_AECDUMP_ENABLED, false),
            intent.getBooleanExtra(EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED, false),
            intent.getBooleanExtra(EXTRA_OPENSLES_ENABLED, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AEC, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AGC, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_NS, false),
            intent.getBooleanExtra(EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, false),
            intent.getBooleanExtra(EXTRA_ENABLE_RTCEVENTLOG, false),
            dataChannelParameters
        )
        commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false)
        val runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0)
        Timber.d("VIDEO_FILE: '%s'", intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA))

        // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
        // standard WebSocketRTCClient.
        appRtcClient = if (loopback || !DirectRTCClient.IP_PATTERN.matcher(roomId).matches()) {
            WebSocketRTCClient(this)
        } else {
            Timber.i("Using DirectRTCClient because room name looks like an IP.")
            DirectRTCClient(this)
        }
        // Create connection parameters.
        val urlParameters = intent.getStringExtra(EXTRA_URLPARAMETERS)
        roomConnectionParameters = RoomConnectionParameters(roomUri.toString(), roomId, loopback, urlParameters)

        // Send intent arguments to fragments.
        callFragment.arguments = intent.extras
        hudFragment.arguments = intent.extras
        // Activate call and HUD fragments and start the call.
        val ft = supportFragmentManager.beginTransaction()
        ft.add(R.id.call_fragment_container, callFragment)
        ft.add(R.id.hud_fragment_container, hudFragment)
        ft.commit()

        // For command line execution run connection for <runTimeMs> and exit.
        if (commandLineRun && runTimeMs > 0) {
            Handler().postDelayed({ disconnect() }, runTimeMs.toLong())
        }

        // Create peer connection client.
        peerConnectionClient = PeerConnectionClient(applicationContext, eglBase, peerConnectionParameters, this@CallActivity)
        val options = PeerConnectionFactory.Options()
        if (loopback) {
            options.networkIgnoreMask = 0
        }
        peerConnectionClient?.createPeerConnectionFactory(options)
        if (screencaptureEnabled) {
            startScreenCapture()
        } else {
            startCall()
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private fun getDisplayMetrics(): DisplayMetrics? {
        val windowManager: WindowManager = application.getSystemService() ?: return null
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return displayMetrics
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScreenCapture() {
        val mediaProjectionManager: MediaProjectionManager = application.getSystemService() ?: return
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), CAPTURE_PERMISSION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE) {
            return
        }
        mediaProjectionPermissionResultCode = resultCode
        mediaProjectionPermissionResultData = data
        startCall()
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this) &&
                intent.getBooleanExtra(EXTRA_CAMERA2, true)
    }

    private fun captureToTexture(): Boolean {
        return intent.getBooleanExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, false)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        Timber.d("Looking for front facing cameras.")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Timber.d("Creating front facing camera capturer.")
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        // Front facing camera not found, try something else
        Timber.d("Looking for other cameras.")
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Timber.d("Creating other camera capturer.")
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun createScreenCapturer(): VideoCapturer? {
        if (mediaProjectionPermissionResultCode != RESULT_OK) {
            reportError("User didn't give permission to capture the screen.")
            return null
        }
        return ScreenCapturerAndroid(
            mediaProjectionPermissionResultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    reportError("User revoked permission to capture the screen.")
                }
            })
    }

    // Activity interfaces
    public override fun onStop() {
        super.onStop()
        activityRunning = false
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        if (!screencaptureEnabled) {
            peerConnectionClient?.stopVideoSource()
        }
    }

    public override fun onStart() {
        super.onStart()
        activityRunning = true
        // Video is not paused for screencapture. See onPause.
        if (!screencaptureEnabled) {
            peerConnectionClient?.startVideoSource()
        }
    }

    override fun onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        disconnect()
        logToast?.cancel()
        activityRunning = false
        super.onDestroy()
    }

    // CallFragment.OnCallEvents interface implementation.
    override fun onCallHangUp() {
        disconnect()
    }

    override fun onCameraSwitch() {
        peerConnectionClient?.switchCamera()
    }

    override fun onVideoScalingSwitch(scalingType: ScalingType?) {
        binding.fullscreenVideoView.setScalingType(scalingType)
    }

    override fun onCaptureFormatChange(width: Int, height: Int, framerate: Int) {
        peerConnectionClient?.changeCaptureFormat(width, height, framerate)
    }

    override fun onToggleMic(): Boolean {
        val enabled = !micEnabled
        peerConnectionClient?.setAudioEnabled(enabled)
        micEnabled = enabled
        return enabled
    }

    // Helper functions.
    private fun toggleCallControlFragmentVisibility() {
        val hudFragment = requireNotNull(supportFragmentManager.findFragmentById(R.id.hud_fragment_container) as HudFragment?)
        val callFragment = requireNotNull(supportFragmentManager.findFragmentById(R.id.call_fragment_container) as CallFragment?)

        if (!connected || !callFragment.isAdded) {
            return
        }
        // Show/hide call control fragment
        callControlFragmentVisible = !callControlFragmentVisible
        val ft = supportFragmentManager.beginTransaction()
        if (callControlFragmentVisible) {
            ft.show(callFragment)
            ft.show(hudFragment)
        } else {
            ft.hide(callFragment)
            ft.hide(hudFragment)
        }
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        ft.commit()
    }

    private fun startCall() {
        val appRtcClient = appRtcClient ?: run {
            Timber.e("AppRTC client is not allocated for a call.")
            return
        }
        callStartedTimeMs = System.currentTimeMillis()

        // Start room connection.
        logAndToast(getString(R.string.connecting_to, roomConnectionParameters.roomUrl))
        appRtcClient.connectToRoom(roomConnectionParameters)

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        audioManager = AppRTCAudioManager(applicationContext)
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Timber.d("Starting the audio manager...")
        audioManager?.start(object : AudioManagerEvents {
            override fun onAudioDeviceChanged(
                selectedAudioDevice: AppRTCAudioManager.AudioDevice?,
                availableAudioDevices: Set<AppRTCAudioManager.AudioDevice>
            ) {
                // This method will be called each time the number of available audio
                // devices has changed.
                onAudioManagerDevicesChanged(selectedAudioDevice, availableAudioDevices)
            }
        })
    }

    // Should be called from UI thread
    private fun callConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        Timber.i("Call connected: delay=%dms", delta)
        val peerConnectionClient = peerConnectionClient
        if (peerConnectionClient == null || isError) {
            Timber.w("Call is connected in closed or error state")
            return
        }
        // Enable statistics callback.
        peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD)
        setSwappedFeeds(false /* isSwappedFeeds */)
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private fun onAudioManagerDevicesChanged(
        device: AppRTCAudioManager.AudioDevice?,
        availableDevices: Set<AppRTCAudioManager.AudioDevice>
    ) {
        Timber.d("onAudioManagerDevicesChanged: %s, selected: %s", availableDevices, device)
        // TODO(henrika): add callback handler.
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private fun disconnect() {
        activityRunning = false
        remoteProxyRenderer.target = null
        localProxyVideoSink.target = null

        appRtcClient?.disconnectFromRoom()
        appRtcClient = null

        binding.pipVideoView.release()

        videoFileRenderer?.release()
        videoFileRenderer = null

        binding.fullscreenVideoView.release()

        peerConnectionClient?.close()
        peerConnectionClient = null

        audioManager?.stop()
        audioManager = null

        if (connected && !isError) {
            setResult(RESULT_OK)
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun disconnectWithErrorMessage(errorMessage: String) {
        if (commandLineRun || !activityRunning) {
            Timber.e("Critical error: %s", errorMessage)
            disconnect()
        } else {
            AlertDialog.Builder(this)
                .setTitle(getText(R.string.channel_error_title))
                .setMessage(errorMessage)
                .setCancelable(false)
                .setNeutralButton(
                    R.string.ok
                ) { dialog, _ ->
                    dialog.cancel()
                    disconnect()
                }
                .create()
                .show()
        }
    }

    // Log |msg| and Toast about it.
    private fun logAndToast(msg: String) {
        Timber.d(msg)
        logToast?.cancel()
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also {
            it.show()
        }
    }

    private fun reportError(description: String) {
        runOnUiThread {
            if (!isError) {
                isError = true
                disconnectWithErrorMessage(description)
            }
        }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer?
        val videoFileAsCamera = intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA)
        videoCapturer = when {
            videoFileAsCamera != null -> {
                try {
                    FileVideoCapturer(videoFileAsCamera)
                } catch (e: IOException) {
                    reportError("Failed to open video file for emulated camera")
                    return null
                }
            }
            screencaptureEnabled -> createScreenCapturer()
            useCamera2() -> {
                if (!captureToTexture()) {
                    reportError(getString(R.string.camera2_texture_only_error))
                    return null
                }
                Timber.d("Creating capturer using camera2 API.")
                createCameraCapturer(Camera2Enumerator(this))
            }
            else -> {
                Timber.d("Creating capturer using camera1 API.")
                createCameraCapturer(Camera1Enumerator(captureToTexture()))
            }
        }
        return videoCapturer ?: run {
            reportError("Failed to open camera")
            null
        }
    }

    private fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        Timber.d("setSwappedFeeds: $isSwappedFeeds")
        this.isSwappedFeeds = isSwappedFeeds
        val fullscreenViewView = binding.fullscreenVideoView
        val pipViewView = binding.pipVideoView

        localProxyVideoSink.target = if (isSwappedFeeds) fullscreenViewView else pipViewView
        remoteProxyRenderer.target = if (isSwappedFeeds) pipViewView else fullscreenViewView
        fullscreenViewView.setMirror(isSwappedFeeds)
        pipViewView.setMirror(!isSwappedFeeds)
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private fun onConnectedToRoomInternal(params: SignalingParameters) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        val peerConnectionClient = peerConnectionClient ?: return

        signalingParameters = params
        logAndToast("Creating peer connection, delay=" + delta + "ms")
        var videoCapturer: VideoCapturer? = null
        if (peerConnectionParameters.videoCallEnabled) {
            videoCapturer = createVideoCapturer()
        }
        peerConnectionClient.createPeerConnection(localProxyVideoSink, remoteSinks, videoCapturer, params)
        if (params.initiator) {
            logAndToast("Creating OFFER...")
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            peerConnectionClient.createOffer()
        } else {
            if (params.offerSdp != null) {
                peerConnectionClient.setRemoteDescription(params.offerSdp)
                logAndToast("Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient.createAnswer()
            }
            // Add remote ICE candidates from room.
            for (iceCandidate in params.iceCandidates) {
                peerConnectionClient.addRemoteIceCandidate(iceCandidate)
            }
        }
    }

    override fun onConnectedToRoom(params: SignalingParameters) {
        runOnUiThread { onConnectedToRoomInternal(params) }
    }

    override fun onRemoteDescription(desc: SessionDescription) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            peerConnectionClient?.also {
                logAndToast("Received remote " + desc.type + ", delay=" + delta + "ms")
                it.setRemoteDescription(desc)
                if (!signalingParameters.initiator) {
                    logAndToast("Creating ANSWER...")
                    // Create answer. Answer SDP will be sent to offering client in
                    // PeerConnectionEvents.onLocalDescription event.
                    it.createAnswer()
                }
            } ?: run {
                Timber.e("Received remote SDP for non-initialized peer connection.")
            }
        }
    }

    override fun onRemoteIceCandidate(candidate: IceCandidate) {
        runOnUiThread {
            peerConnectionClient?.also {
                it.addRemoteIceCandidate(candidate)
            } ?: run {
                Timber.e("Received ICE candidate for a non-initialized peer connection.")
            }
        }
    }

    override fun onRemoteIceCandidatesRemoved(candidates: List<IceCandidate>) {
        runOnUiThread {
            peerConnectionClient?.also {
                it.removeRemoteIceCandidates(candidates)
            } ?: run {
                Timber.e("Received ICE candidate removals for a non-initialized peer connection.")
            }
        }
    }

    override fun onChannelClose() {
        runOnUiThread {
            logAndToast("Remote end hung up; dropping PeerConnection")
            disconnect()
        }
    }

    override fun onChannelError(description: String) {
        reportError(description)
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    override fun onLocalDescription(desc: SessionDescription) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            appRtcClient?.also {
                logAndToast("Sending " + desc.type + ", delay=" + delta + "ms")
                if (signalingParameters.initiator) {
                    it.sendOfferSdp(desc)
                } else {
                    it.sendAnswerSdp(desc)
                }
            }
            if (peerConnectionParameters.videoMaxBitrate > 0) {
                Timber.d("Set video maximum bitrate: %d", peerConnectionParameters.videoMaxBitrate)
                peerConnectionClient?.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate)
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        runOnUiThread {
            appRtcClient?.sendLocalIceCandidate(candidate)
        }
    }

    override fun onIceCandidatesRemoved(candidates: List<IceCandidate>) {
        runOnUiThread {
            appRtcClient?.sendLocalIceCandidateRemovals(candidates)
        }
    }

    override fun onIceConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread { logAndToast("ICE connected, delay=" + delta + "ms") }
    }

    override fun onIceDisconnected() {
        runOnUiThread { logAndToast("ICE disconnected") }
    }

    override fun onConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            logAndToast("DTLS connected, delay=" + delta + "ms")
            connected = true
            callConnected()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            logAndToast("DTLS disconnected")
            connected = false
            disconnect()
        }
    }

    override fun onPeerConnectionClosed() {}

    override fun onPeerConnectionStatsReady(reports: List<StatsReport>) {
        runOnUiThread {
            if (!isError && connected) {
                (supportFragmentManager.findFragmentById(R.id.hud_fragment_container) as HudFragment?)
                    ?.updateEncoderStatistics(reports)
            }
        }
    }

    override fun onPeerConnectionError(description: String) {
        reportError(description)
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    companion object {
        const val EXTRA_ROOMID = "org.appspot.apprtc.ROOMID"
        const val EXTRA_URLPARAMETERS = "org.appspot.apprtc.URLPARAMETERS"
        const val EXTRA_LOOPBACK = "org.appspot.apprtc.LOOPBACK"
        const val EXTRA_VIDEO_CALL = "org.appspot.apprtc.VIDEO_CALL"
        const val EXTRA_SCREENCAPTURE = "org.appspot.apprtc.SCREENCAPTURE"
        const val EXTRA_CAMERA2 = "org.appspot.apprtc.CAMERA2"
        const val EXTRA_VIDEO_WIDTH = "org.appspot.apprtc.VIDEO_WIDTH"
        const val EXTRA_VIDEO_HEIGHT = "org.appspot.apprtc.VIDEO_HEIGHT"
        const val EXTRA_VIDEO_FPS = "org.appspot.apprtc.VIDEO_FPS"
        const val EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED = "org.appsopt.apprtc.VIDEO_CAPTUREQUALITYSLIDER"
        const val EXTRA_VIDEO_BITRATE = "org.appspot.apprtc.VIDEO_BITRATE"
        const val EXTRA_VIDEOCODEC = "org.appspot.apprtc.VIDEOCODEC"
        const val EXTRA_HWCODEC_ENABLED = "org.appspot.apprtc.HWCODEC"
        const val EXTRA_CAPTURETOTEXTURE_ENABLED = "org.appspot.apprtc.CAPTURETOTEXTURE"
        const val EXTRA_FLEXFEC_ENABLED = "org.appspot.apprtc.FLEXFEC"
        const val EXTRA_AUDIO_BITRATE = "org.appspot.apprtc.AUDIO_BITRATE"
        const val EXTRA_AUDIOCODEC = "org.appspot.apprtc.AUDIOCODEC"
        const val EXTRA_NOAUDIOPROCESSING_ENABLED = "org.appspot.apprtc.NOAUDIOPROCESSING"
        const val EXTRA_AECDUMP_ENABLED = "org.appspot.apprtc.AECDUMP"
        const val EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED = "org.appspot.apprtc.SAVE_INPUT_AUDIO_TO_FILE"
        const val EXTRA_OPENSLES_ENABLED = "org.appspot.apprtc.OPENSLES"
        const val EXTRA_DISABLE_BUILT_IN_AEC = "org.appspot.apprtc.DISABLE_BUILT_IN_AEC"
        const val EXTRA_DISABLE_BUILT_IN_AGC = "org.appspot.apprtc.DISABLE_BUILT_IN_AGC"
        const val EXTRA_DISABLE_BUILT_IN_NS = "org.appspot.apprtc.DISABLE_BUILT_IN_NS"
        const val EXTRA_DISABLE_WEBRTC_AGC_AND_HPF = "org.appspot.apprtc.DISABLE_WEBRTC_GAIN_CONTROL"
        const val EXTRA_DISPLAY_HUD = "org.appspot.apprtc.DISPLAY_HUD"
        const val EXTRA_TRACING = "org.appspot.apprtc.TRACING"
        const val EXTRA_CMDLINE = "org.appspot.apprtc.CMDLINE"
        const val EXTRA_RUNTIME = "org.appspot.apprtc.RUNTIME"
        const val EXTRA_VIDEO_FILE_AS_CAMERA = "org.appspot.apprtc.VIDEO_FILE_AS_CAMERA"
        const val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE"
        const val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_WIDTH"
        const val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT"
        const val EXTRA_USE_VALUES_FROM_INTENT = "org.appspot.apprtc.USE_VALUES_FROM_INTENT"
        const val EXTRA_DATA_CHANNEL_ENABLED = "org.appspot.apprtc.DATA_CHANNEL_ENABLED"
        const val EXTRA_ORDERED = "org.appspot.apprtc.ORDERED"
        const val EXTRA_MAX_RETRANSMITS_MS = "org.appspot.apprtc.MAX_RETRANSMITS_MS"
        const val EXTRA_MAX_RETRANSMITS = "org.appspot.apprtc.MAX_RETRANSMITS"
        const val EXTRA_PROTOCOL = "org.appspot.apprtc.PROTOCOL"
        const val EXTRA_NEGOTIATED = "org.appspot.apprtc.NEGOTIATED"
        const val EXTRA_ID = "org.appspot.apprtc.ID"
        const val EXTRA_ENABLE_RTCEVENTLOG = "org.appspot.apprtc.ENABLE_RTCEVENTLOG"
        private const val CAPTURE_PERMISSION_REQUEST_CODE = 1

        // List of mandatory application permissions.
        private val MANDATORY_PERMISSIONS = arrayOf(
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO", "android.permission.INTERNET"
        )

        // Peer connection statistics callback period in ms.
        private const val STAT_CALLBACK_PERIOD = 1000
        private var mediaProjectionPermissionResultData: Intent? = null
        private var mediaProjectionPermissionResultCode = 0

        private const val systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}