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

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import android.widget.AdapterView.AdapterContextMenuInfo
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import org.appspot.apprtc.databinding.ActivityConnectBinding
import org.json.JSONArray
import org.json.JSONException
import timber.log.Timber
import java.util.*

/**
 * Handles the initial setup where the user selects which room to join.
 */
class ConnectActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConnectBinding

    private val sharedPref: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(
            this
        )
    }
    private val keyprefResolution: String by lazy { getString(R.string.pref_resolution_key) }
    private val keyprefFps: String by lazy { getString(R.string.pref_fps_key) }
    private val keyprefVideoBitrateType: String by lazy { getString(R.string.pref_maxvideobitrate_key) }
    private val keyprefVideoBitrateValue: String by lazy { getString(R.string.pref_maxvideobitratevalue_key) }
    private val keyprefAudioBitrateType: String by lazy { getString(R.string.pref_startaudiobitrate_key) }
    private val keyprefAudioBitrateValue: String by lazy { getString(R.string.pref_startaudiobitratevalue_key) }
    private val keyprefRoomServerUrl: String by lazy { getString(R.string.pref_room_server_url_key) }
    private val keyprefWebSocketServerUrl: String by lazy { getString(R.string.pref_websocket_server_url_key) }
    private val keyprefRoom: String by lazy { getString(R.string.pref_room_key) }
    private val keyprefRoomList: String by lazy { getString(R.string.pref_room_list_key) }
    private val roomList = arrayListOf<String>()
    private val adapter by lazy {
        ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            roomList
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityConnectBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Get setting keys.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        binding.roomEdittext.setOnEditorActionListener(OnEditorActionListener { _, i, _ ->
            if (i == EditorInfo.IME_ACTION_DONE) {
                binding.addFavoriteButton.performClick()
                return@OnEditorActionListener true
            }
            false
        })
        binding.roomEdittext.requestFocus()
        binding.roomListview.also {
            it.emptyView = findViewById(android.R.id.empty)
            it.onItemClickListener = roomListClickListener
            registerForContextMenu(it)
        }
        binding.connectButton.setOnClickListener(connectListener)
        binding.addFavoriteButton.setOnClickListener(addFavoriteListener)
        requestPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.connect_menu, menu)
        return true
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo) {
        if (v.id == R.id.room_listview) {
            val info = menuInfo as AdapterContextMenuInfo
            menu.setHeaderTitle(roomList[info.position])
            val menuItems = resources.getStringArray(R.array.roomListContextMenu)
            for (i in menuItems.indices) {
                menu.add(Menu.NONE, i, i, menuItems[i])
            }
        } else {
            super.onCreateContextMenu(menu, v, menuInfo)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        if (item.itemId == REMOVE_FAVORITE_INDEX) {
            val info = item.menuInfo as AdapterContextMenuInfo
            roomList.removeAt(info.position)
            adapter.notifyDataSetChanged()
            return true
        }
        return super.onContextItemSelected(item)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle presses on the action bar items.
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_loopback -> {
                connectToRoom(
                    null,
                    commandLineRun = false,
                    loopback = true,
                    useValuesFromIntent = false,
                    runTimeMs = 0,
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    public override fun onPause() {
        super.onPause()
        val room = binding.roomEdittext.text.toString()
        val roomListJson = JSONArray(roomList).toString()
        sharedPref.edit {
            putString(keyprefRoom, room)
            putString(keyprefRoomList, roomListJson)
        }
    }

    public override fun onResume() {
        super.onResume()
        val room = sharedPref.getString(keyprefRoom, "")
        binding.roomEdittext.setText(room)
        roomList.clear()
        sharedPref.getString(keyprefRoomList, null)?.also {
            try {
                val jsonArray = JSONArray(it)
                for (i in 0 until jsonArray.length()) {
                    roomList.add(jsonArray[i].toString())
                }
            } catch (e: JSONException) {
                Timber.e(e, "Failed to load room list:")
            }
        }
        adapter.notifyDataSetChanged()
        binding.roomListview.also {
            it.adapter = adapter
            if (adapter.count > 0) {
                it.requestFocus()
                it.setItemChecked(0, true)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONNECTION_REQUEST && commandLineRun) {
            Timber.d("Return: %d", resultCode)
            setResult(resultCode)
            commandLineRun = false
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST) {
            val missingPermissions = getMissingPermissions()
            if (missingPermissions.isNotEmpty()) {
                // User didn't grant all the permissions. Warn that the application might not work
                // correctly.
                AlertDialog.Builder(this)
                    .setMessage(R.string.missing_permissions_try_again)
                    .setPositiveButton(R.string.yes) { dialog: DialogInterface, _: Int ->
                        // User wants to try giving the permissions again.
                        dialog.cancel()
                        requestPermissions()
                    }
                    .setNegativeButton(R.string.no) { dialog: DialogInterface, _: Int ->
                        // User doesn't want to give the permissions.
                        dialog.cancel()
                        onPermissionsGranted()
                    }
                    .show()
            } else {
                // All permissions granted.
                onPermissionsGranted()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun onPermissionsGranted() {
        // If an implicit VIEW intent is launching the app, go directly to that URL.
        val intent = intent
        if ("android.intent.action.VIEW" == intent.action && !commandLineRun) {
            val loopback = intent.getBooleanExtra(CallActivity.EXTRA_LOOPBACK, false)
            val runTimeMs = intent.getIntExtra(CallActivity.EXTRA_RUNTIME, 0)
            val useValuesFromIntent =
                intent.getBooleanExtra(CallActivity.EXTRA_USE_VALUES_FROM_INTENT, false)
            val roomId = sharedPref.getString(keyprefRoom, null) ?: ""
            connectToRoom(roomId, true, loopback, useValuesFromIntent, runTimeMs)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dynamic permissions are not required before Android M.
            onPermissionsGranted()
            return
        }
        val missingPermissions = getMissingPermissions()
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions, PERMISSION_REQUEST)
        } else {
            onPermissionsGranted()
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun getMissingPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return emptyArray()
        }
        val info = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.w(e, "Failed to retrieve permissions.")
            return emptyArray()
        }
        val requestedPermissions = info.requestedPermissions
        val requestedPermissionsFlags = info.requestedPermissionsFlags
        if (requestedPermissions == null || requestedPermissionsFlags == null) {
            Timber.w("No requested permissions.")
            return emptyArray()
        }
        val missingPermissions = ArrayList<String>()
        for (i in requestedPermissions.indices) {
            if (requestedPermissionsFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED == 0) {
                missingPermissions.add(requestedPermissions[i])
            }
        }
        Timber.d("Missing permissions: %s", missingPermissions)
        return missingPermissions.toTypedArray()
    }

    /**
     * Get a value from the shared preference or from the intent, if it does not
     * exist the default is used.
     */
    private fun sharedPrefGetString(
        attributeId: Int,
        intentName: String,
        defaultId: Int,
        useFromIntent: Boolean
    ): String {
        val defaultValue = getString(defaultId)
        return if (useFromIntent) {
            val value = intent.getStringExtra(intentName)
            value
        } else {
            val attributeName = getString(attributeId)
            sharedPref.getString(attributeName, null)
        } ?: defaultValue
    }

    /**
     * Get a value from the shared preference or from the intent, if it does not
     * exist the default is used.
     */
    private fun sharedPrefGetBoolean(
        attributeId: Int,
        intentName: String,
        defaultId: Int,
        useFromIntent: Boolean
    ): Boolean {
        val defaultValue = java.lang.Boolean.parseBoolean(getString(defaultId))
        return if (useFromIntent) {
            intent.getBooleanExtra(intentName, defaultValue)
        } else {
            val attributeName = getString(attributeId)
            sharedPref.getBoolean(attributeName, defaultValue)
        }
    }

    /**
     * Get a value from the shared preference or from the intent, if it does not
     * exist the default is used.
     */
    private fun sharedPrefGetInteger(
        attributeId: Int,
        intentName: String,
        defaultId: Int,
        useFromIntent: Boolean
    ): Int {
        val defaultString = getString(defaultId)
        val defaultValue = defaultString.toInt()
        return if (useFromIntent) {
            intent.getIntExtra(intentName, defaultValue)
        } else {
            val attributeName = getString(attributeId)
            val value = sharedPref.getString(attributeName, null)
            value?.toIntOrNull() ?: run {
                Timber.e("Wrong setting for: %s:%s", attributeName, value)
                defaultValue
            }
        }
    }

    private fun connectToRoom(
        roomId: String?, commandLineRun: Boolean, loopback: Boolean,
        useValuesFromIntent: Boolean, runTimeMs: Int
    ) {
        Companion.commandLineRun = commandLineRun

        var newRoomId = roomId
        // roomId is random for loopback.
        if (loopback) {
            newRoomId = Random().nextInt(100000000).toString()
        }
        val roomUrl = sharedPref.getString(
            keyprefRoomServerUrl,
            getString(R.string.pref_room_server_url_default)
        )
        val webSocketServerUrl = sharedPref.getString(
            keyprefWebSocketServerUrl,
            getString(R.string.pref_websocket_server_url_default)
        )

        // Video call enabled flag.
        val videoCallEnabled = sharedPrefGetBoolean(
            R.string.pref_videocall_key,
            CallActivity.EXTRA_VIDEO_CALL, R.string.pref_videocall_default, useValuesFromIntent
        )

        // Use screencapture option.
        val useScreencapture = sharedPrefGetBoolean(
            R.string.pref_screencapture_key,
            CallActivity.EXTRA_SCREENCAPTURE,
            R.string.pref_screencapture_default,
            useValuesFromIntent
        )

        // Use Camera2 option.
        val useCamera2 = sharedPrefGetBoolean(
            R.string.pref_camera2_key, CallActivity.EXTRA_CAMERA2,
            R.string.pref_camera2_default, useValuesFromIntent
        )

        // Get default codecs.
        val videoCodec = sharedPrefGetString(
            R.string.pref_videocodec_key,
            CallActivity.EXTRA_VIDEOCODEC, R.string.pref_videocodec_default, useValuesFromIntent
        )
        val audioCodec = sharedPrefGetString(
            R.string.pref_audiocodec_key,
            CallActivity.EXTRA_AUDIOCODEC, R.string.pref_audiocodec_default, useValuesFromIntent
        )

        // Check HW codec flag.
        val hwCodec = sharedPrefGetBoolean(
            R.string.pref_hwcodec_key,
            CallActivity.EXTRA_HWCODEC_ENABLED, R.string.pref_hwcodec_default, useValuesFromIntent
        )

        // Check Capture to texture.
        val captureToTexture = sharedPrefGetBoolean(
            R.string.pref_capturetotexture_key,
            CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED, R.string.pref_capturetotexture_default,
            useValuesFromIntent
        )

        // Check FlexFEC.
        val flexfecEnabled = sharedPrefGetBoolean(
            R.string.pref_flexfec_key,
            CallActivity.EXTRA_FLEXFEC_ENABLED, R.string.pref_flexfec_default, useValuesFromIntent
        )

        // Check Disable Audio Processing flag.
        val noAudioProcessing = sharedPrefGetBoolean(
            R.string.pref_noaudioprocessing_key,
            CallActivity.EXTRA_NOAUDIOPROCESSING_ENABLED, R.string.pref_noaudioprocessing_default,
            useValuesFromIntent
        )
        val aecDump = sharedPrefGetBoolean(
            R.string.pref_aecdump_key,
            CallActivity.EXTRA_AECDUMP_ENABLED, R.string.pref_aecdump_default, useValuesFromIntent
        )
        val saveInputAudioToFile = sharedPrefGetBoolean(
            R.string.pref_enable_save_input_audio_to_file_key,
            CallActivity.EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED,
            R.string.pref_enable_save_input_audio_to_file_default, useValuesFromIntent
        )

        // Check OpenSL ES enabled flag.
        val useOpenSLES = sharedPrefGetBoolean(
            R.string.pref_opensles_key,
            CallActivity.EXTRA_OPENSLES_ENABLED, R.string.pref_opensles_default, useValuesFromIntent
        )

        // Check Disable built-in AEC flag.
        val disableBuiltInAEC = sharedPrefGetBoolean(
            R.string.pref_disable_built_in_aec_key,
            CallActivity.EXTRA_DISABLE_BUILT_IN_AEC, R.string.pref_disable_built_in_aec_default,
            useValuesFromIntent
        )

        // Check Disable built-in AGC flag.
        val disableBuiltInAGC = sharedPrefGetBoolean(
            R.string.pref_disable_built_in_agc_key,
            CallActivity.EXTRA_DISABLE_BUILT_IN_AGC, R.string.pref_disable_built_in_agc_default,
            useValuesFromIntent
        )

        // Check Disable built-in NS flag.
        val disableBuiltInNS = sharedPrefGetBoolean(
            R.string.pref_disable_built_in_ns_key,
            CallActivity.EXTRA_DISABLE_BUILT_IN_NS, R.string.pref_disable_built_in_ns_default,
            useValuesFromIntent
        )

        // Check Disable gain control
        val disableWebRtcAGCAndHPF = sharedPrefGetBoolean(
            R.string.pref_disable_webrtc_agc_and_hpf_key,
            CallActivity.EXTRA_DISABLE_WEBRTC_AGC_AND_HPF,
            R.string.pref_disable_webrtc_agc_and_hpf_key,
            useValuesFromIntent
        )

        // Get video resolution from settings.
        var videoWidth = 0
        var videoHeight = 0
        if (useValuesFromIntent) {
            videoWidth = intent.getIntExtra(CallActivity.EXTRA_VIDEO_WIDTH, 0)
            videoHeight = intent.getIntExtra(CallActivity.EXTRA_VIDEO_HEIGHT, 0)
        }
        if (videoWidth == 0 && videoHeight == 0) {
            val resolution =
                sharedPref.getString(keyprefResolution, getString(R.string.pref_resolution_default))
            val dimensions = resolution?.split("[ x]+")?.toTypedArray() ?: emptyArray()
            if (dimensions.size == 2) {
                try {
                    videoWidth = dimensions[0].toInt()
                    videoHeight = dimensions[1].toInt()
                } catch (e: NumberFormatException) {
                    videoWidth = 0
                    videoHeight = 0
                    Timber.e("Wrong video resolution setting: %s", resolution)
                }
            }
        }

        // Get camera fps from settings.
        var cameraFps = 0
        if (useValuesFromIntent) {
            cameraFps = intent.getIntExtra(CallActivity.EXTRA_VIDEO_FPS, 0)
        }
        if (cameraFps == 0) {
            val fps = sharedPref.getString(keyprefFps, getString(R.string.pref_fps_default))
            val fpsValues = fps?.split("[ x]+")?.toTypedArray() ?: emptyArray()
            if (fpsValues.size == 2) {
                try {
                    cameraFps = fpsValues[0].toInt()
                } catch (e: NumberFormatException) {
                    cameraFps = 0
                    Timber.e("Wrong camera fps setting: %s", fps)
                }
            }
        }

        // Check capture quality slider flag.
        val captureQualitySlider = sharedPrefGetBoolean(
            R.string.pref_capturequalityslider_key,
            CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED,
            R.string.pref_capturequalityslider_default, useValuesFromIntent
        )

        // Get video and audio start bitrate.
        var videoStartBitrate = 0
        if (useValuesFromIntent) {
            videoStartBitrate = intent.getIntExtra(CallActivity.EXTRA_VIDEO_BITRATE, 0)
        }
        if (videoStartBitrate == 0) {
            val bitrateTypeDefault = getString(R.string.pref_maxvideobitrate_default)
            val bitrateType = sharedPref.getString(keyprefVideoBitrateType, bitrateTypeDefault)
            if (bitrateType != bitrateTypeDefault) {
                sharedPref.getString(
                    keyprefVideoBitrateValue,
                    getString(R.string.pref_maxvideobitratevalue_default)
                )?.also {
                    videoStartBitrate = it.toInt()
                }
            }
        }
        var audioStartBitrate = 0
        if (useValuesFromIntent) {
            audioStartBitrate = intent.getIntExtra(CallActivity.EXTRA_AUDIO_BITRATE, 0)
        }
        if (audioStartBitrate == 0) {
            val bitrateTypeDefault = getString(R.string.pref_startaudiobitrate_default)
            val bitrateType = sharedPref.getString(keyprefAudioBitrateType, bitrateTypeDefault)
            if (bitrateType != bitrateTypeDefault) {
                sharedPref.getString(
                    keyprefAudioBitrateValue,
                    getString(R.string.pref_startaudiobitratevalue_default)
                )?.also {
                    audioStartBitrate = it.toInt()
                }
            }
        }

        // Check statistics display option.
        val displayHud = sharedPrefGetBoolean(
            R.string.pref_displayhud_key,
            CallActivity.EXTRA_DISPLAY_HUD, R.string.pref_displayhud_default, useValuesFromIntent
        )
        val tracing = sharedPrefGetBoolean(
            R.string.pref_tracing_key, CallActivity.EXTRA_TRACING,
            R.string.pref_tracing_default, useValuesFromIntent
        )

        // Check Enable RtcEventLog.
        val rtcEventLogEnabled = sharedPrefGetBoolean(
            R.string.pref_enable_rtceventlog_key,
            CallActivity.EXTRA_ENABLE_RTCEVENTLOG, R.string.pref_enable_rtceventlog_default,
            useValuesFromIntent
        )

        // Get datachannel options
        val dataChannelEnabled = sharedPrefGetBoolean(
            R.string.pref_enable_datachannel_key,
            CallActivity.EXTRA_DATA_CHANNEL_ENABLED, R.string.pref_enable_datachannel_default,
            useValuesFromIntent
        )
        val ordered = sharedPrefGetBoolean(
            R.string.pref_ordered_key, CallActivity.EXTRA_ORDERED,
            R.string.pref_ordered_default, useValuesFromIntent
        )
        val negotiated = sharedPrefGetBoolean(
            R.string.pref_negotiated_key,
            CallActivity.EXTRA_NEGOTIATED, R.string.pref_negotiated_default, useValuesFromIntent
        )
        val maxRetrMs = sharedPrefGetInteger(
            R.string.pref_max_retransmit_time_ms_key,
            CallActivity.EXTRA_MAX_RETRANSMITS_MS, R.string.pref_max_retransmit_time_ms_default,
            useValuesFromIntent
        )
        val maxRetr = sharedPrefGetInteger(
            R.string.pref_max_retransmits_key, CallActivity.EXTRA_MAX_RETRANSMITS,
            R.string.pref_max_retransmits_default, useValuesFromIntent
        )
        val id = sharedPrefGetInteger(
            R.string.pref_data_id_key, CallActivity.EXTRA_ID,
            R.string.pref_data_id_default, useValuesFromIntent
        )
        val protocol = sharedPrefGetString(
            R.string.pref_data_protocol_key,
            CallActivity.EXTRA_PROTOCOL, R.string.pref_data_protocol_default, useValuesFromIntent
        )

        // Start AppRTCMobile activity.
        Timber.d("Connecting to room %s at URL %s", newRoomId, roomUrl)
        if (validateUrl(roomUrl)) {
            val uri = Uri.parse(roomUrl)
            val intent = Intent(this, CallActivity::class.java)
            intent.data = uri
            intent.putExtra(CallActivity.EXTRA_ROOMID, newRoomId)
            if (webSocketServerUrl?.isNotBlank() == true && validateUrl(webSocketServerUrl)) {
                val webSocketUri = Uri.parse(webSocketServerUrl)
                val wstls = URLUtil.isHttpsUrl(webSocketServerUrl)
                intent.putExtra(CallActivity.EXTRA_URLPARAMETERS,
                    "wshpp=%s:%s&wstls=%b&ts=".format(webSocketUri.host, webSocketUri.port, wstls)
                )
            }
            intent.putExtra(CallActivity.EXTRA_LOOPBACK, loopback)
            intent.putExtra(CallActivity.EXTRA_VIDEO_CALL, videoCallEnabled)
            intent.putExtra(CallActivity.EXTRA_SCREENCAPTURE, useScreencapture)
            intent.putExtra(CallActivity.EXTRA_CAMERA2, useCamera2)
            intent.putExtra(CallActivity.EXTRA_VIDEO_WIDTH, videoWidth)
            intent.putExtra(CallActivity.EXTRA_VIDEO_HEIGHT, videoHeight)
            intent.putExtra(CallActivity.EXTRA_VIDEO_FPS, cameraFps)
            intent.putExtra(
                CallActivity.EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED,
                captureQualitySlider
            )
            intent.putExtra(CallActivity.EXTRA_VIDEO_BITRATE, videoStartBitrate)
            intent.putExtra(CallActivity.EXTRA_VIDEOCODEC, videoCodec)
            intent.putExtra(CallActivity.EXTRA_HWCODEC_ENABLED, hwCodec)
            intent.putExtra(CallActivity.EXTRA_CAPTURETOTEXTURE_ENABLED, captureToTexture)
            intent.putExtra(CallActivity.EXTRA_FLEXFEC_ENABLED, flexfecEnabled)
            intent.putExtra(CallActivity.EXTRA_NOAUDIOPROCESSING_ENABLED, noAudioProcessing)
            intent.putExtra(CallActivity.EXTRA_AECDUMP_ENABLED, aecDump)
            intent.putExtra(
                CallActivity.EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED,
                saveInputAudioToFile
            )
            intent.putExtra(CallActivity.EXTRA_OPENSLES_ENABLED, useOpenSLES)
            intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_AEC, disableBuiltInAEC)
            intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_AGC, disableBuiltInAGC)
            intent.putExtra(CallActivity.EXTRA_DISABLE_BUILT_IN_NS, disableBuiltInNS)
            intent.putExtra(CallActivity.EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, disableWebRtcAGCAndHPF)
            intent.putExtra(CallActivity.EXTRA_AUDIO_BITRATE, audioStartBitrate)
            intent.putExtra(CallActivity.EXTRA_AUDIOCODEC, audioCodec)
            intent.putExtra(CallActivity.EXTRA_DISPLAY_HUD, displayHud)
            intent.putExtra(CallActivity.EXTRA_TRACING, tracing)
            intent.putExtra(CallActivity.EXTRA_ENABLE_RTCEVENTLOG, rtcEventLogEnabled)
            intent.putExtra(CallActivity.EXTRA_CMDLINE, commandLineRun)
            intent.putExtra(CallActivity.EXTRA_RUNTIME, runTimeMs)
            intent.putExtra(CallActivity.EXTRA_DATA_CHANNEL_ENABLED, dataChannelEnabled)
            if (dataChannelEnabled) {
                intent.putExtra(CallActivity.EXTRA_ORDERED, ordered)
                intent.putExtra(CallActivity.EXTRA_MAX_RETRANSMITS_MS, maxRetrMs)
                intent.putExtra(CallActivity.EXTRA_MAX_RETRANSMITS, maxRetr)
                intent.putExtra(CallActivity.EXTRA_PROTOCOL, protocol)
                intent.putExtra(CallActivity.EXTRA_NEGOTIATED, negotiated)
                intent.putExtra(CallActivity.EXTRA_ID, id)
            }
            if (useValuesFromIntent) {
                if (getIntent().hasExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA)) {
                    val videoFileAsCamera =
                        getIntent().getStringExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA)
                    intent.putExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA, videoFileAsCamera)
                }
                if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE)) {
                    val saveRemoteVideoToFile =
                        getIntent().getStringExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE)
                    intent.putExtra(
                        CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE,
                        saveRemoteVideoToFile
                    )
                }
                if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH)) {
                    val videoOutWidth = getIntent().getIntExtra(
                        CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH,
                        0
                    )
                    intent.putExtra(
                        CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH,
                        videoOutWidth
                    )
                }
                if (getIntent().hasExtra(CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT)) {
                    val videoOutHeight = getIntent().getIntExtra(
                        CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT,
                        0
                    )
                    intent.putExtra(
                        CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT,
                        videoOutHeight
                    )
                }
            }
            startActivityForResult(intent, CONNECTION_REQUEST)
        }
    }

    private fun validateUrl(url: String?): Boolean {
        if (URLUtil.isHttpsUrl(url) || URLUtil.isHttpUrl(url)) {
            return true
        }
        AlertDialog.Builder(this)
            .setTitle(getText(R.string.invalid_url_title))
            .setMessage(getString(R.string.invalid_url_text, url))
            .setCancelable(false)
            .setNeutralButton(R.string.ok) { dialog, _ -> dialog.cancel() }
            .create()
            .show()
        return false
    }

    private val roomListClickListener = OnItemClickListener { _, view, _, _ ->
        val roomId = (view as TextView).text.toString()
        connectToRoom(
            roomId = roomId,
            commandLineRun = false,
            loopback = false,
            useValuesFromIntent = false,
            runTimeMs = 0
        )
    }
    private val addFavoriteListener =
        View.OnClickListener {
            val newRoom = binding.roomEdittext.text.toString()
            if (newRoom.isNotEmpty() && !roomList.contains(newRoom)) {
                adapter.add(newRoom)
                adapter.notifyDataSetChanged()
            }
        }
    private val connectListener =
        View.OnClickListener {
            connectToRoom(
                roomId = binding.roomEdittext.text.toString(),
                commandLineRun = false,
                loopback = false,
                useValuesFromIntent = false,
                runTimeMs = 0
            )
        }

    companion object {
        private const val CONNECTION_REQUEST = 1
        private const val PERMISSION_REQUEST = 2
        private const val REMOVE_FAVORITE_INDEX = 0
        private var commandLineRun = false
    }
}
