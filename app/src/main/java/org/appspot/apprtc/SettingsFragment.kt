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

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.webrtc.Camera2Enumerator
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Settings fragment for AppRTC.
 */
class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val keyprefVideoCall by lazy { getString(R.string.pref_videocall_key) }
    private val keyprefScreencapture by lazy { getString(R.string.pref_screencapture_key) }
    private val keyprefCamera2 by lazy { getString(R.string.pref_camera2_key) }
    private val keyprefResolution by lazy { getString(R.string.pref_resolution_key) }
    private val keyprefFps by lazy { getString(R.string.pref_fps_key) }
    private val keyprefCaptureQualitySlider by lazy { getString(R.string.pref_capturequalityslider_key) }
    private val keyprefMaxVideoBitrateType by lazy { getString(R.string.pref_maxvideobitrate_key) }
    private val keyprefMaxVideoBitrateValue by lazy { getString(R.string.pref_maxvideobitratevalue_key) }
    private val keyPrefVideoCodec by lazy { getString(R.string.pref_videocodec_key) }
    private val keyprefHwCodec by lazy { getString(R.string.pref_hwcodec_key) }
    private val keyprefCaptureToTexture by lazy { getString(R.string.pref_capturetotexture_key) }
    private val keyprefFlexfec by lazy { getString(R.string.pref_flexfec_key) }
    private val keyprefStartAudioBitrateType by lazy { getString(R.string.pref_startaudiobitrate_key) }
    private val keyprefStartAudioBitrateValue by lazy { getString(R.string.pref_startaudiobitratevalue_key) }
    private val keyPrefAudioCodec by lazy { getString(R.string.pref_audiocodec_key) }
    private val keyprefNoAudioProcessing by lazy { getString(R.string.pref_noaudioprocessing_key) }
    private val keyprefAecDump by lazy { getString(R.string.pref_aecdump_key) }
    private val keyprefEnableSaveInputAudioToFile by lazy { getString(R.string.pref_enable_save_input_audio_to_file_key) }
    private val keyprefOpenSLES by lazy { getString(R.string.pref_opensles_key) }
    private val keyprefDisableBuiltInAEC by lazy { getString(R.string.pref_disable_built_in_aec_key) }
    private val keyprefDisableBuiltInAGC by lazy { getString(R.string.pref_disable_built_in_agc_key) }
    private val keyprefDisableBuiltInNS by lazy { getString(R.string.pref_disable_built_in_ns_key) }
    private val keyprefDisableWebRtcAGCAndHPF by lazy { getString(R.string.pref_disable_webrtc_agc_and_hpf_key) }
    private val keyprefSpeakerphone by lazy { getString(R.string.pref_speakerphone_key) }
    private val keyPrefRoomServerUrl by lazy { getString(R.string.pref_room_server_url_key) }
    private val keyPrefDisplayHud by lazy { getString(R.string.pref_displayhud_key) }
    private val keyPrefTracing by lazy { getString(R.string.pref_tracing_key) }
    private val keyprefEnabledRtcEventLog by lazy { getString(R.string.pref_enable_rtceventlog_key) }
    private val keyprefEnableDataChannel by lazy { getString(R.string.pref_enable_datachannel_key) }
    private val keyprefOrdered by lazy { getString(R.string.pref_ordered_key) }
    private val keyprefMaxRetransmitTimeMs by lazy { getString(R.string.pref_max_retransmit_time_ms_key) }
    private val keyprefMaxRetransmits by lazy { getString(R.string.pref_max_retransmits_key) }
    private val keyprefDataProtocol by lazy { getString(R.string.pref_data_protocol_key) }
    private val keyprefNegotiated by lazy { getString(R.string.pref_negotiated_key) }
    private val keyprefDataId by lazy { getString(R.string.pref_data_id_key) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()

        // Set summary to be the user-description for the selected value
        val sharedPreferences = preferenceScreen.sharedPreferences
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        updateSummaryB(sharedPreferences, keyprefVideoCall)
        updateSummaryB(sharedPreferences, keyprefScreencapture)
        updateSummaryB(sharedPreferences, keyprefCamera2)
        updateSummary(sharedPreferences, keyprefResolution)
        updateSummary(sharedPreferences, keyprefFps)
        updateSummaryB(sharedPreferences, keyprefCaptureQualitySlider)
        updateSummary(sharedPreferences, keyprefMaxVideoBitrateType)
        updateSummaryBitrate(sharedPreferences, keyprefMaxVideoBitrateValue)
        setVideoBitrateEnable(sharedPreferences)
        updateSummary(sharedPreferences, keyPrefVideoCodec)
        updateSummaryB(sharedPreferences, keyprefHwCodec)
        updateSummaryB(sharedPreferences, keyprefCaptureToTexture)
        updateSummaryB(sharedPreferences, keyprefFlexfec)
        updateSummary(sharedPreferences, keyprefStartAudioBitrateType)
        updateSummaryBitrate(sharedPreferences, keyprefStartAudioBitrateValue)
        setAudioBitrateEnable(sharedPreferences)
        updateSummary(sharedPreferences, keyPrefAudioCodec)
        updateSummaryB(sharedPreferences, keyprefNoAudioProcessing)
        updateSummaryB(sharedPreferences, keyprefAecDump)
        updateSummaryB(sharedPreferences, keyprefEnableSaveInputAudioToFile)
        updateSummaryB(sharedPreferences, keyprefOpenSLES)
        updateSummaryB(sharedPreferences, keyprefDisableBuiltInAEC)
        updateSummaryB(sharedPreferences, keyprefDisableBuiltInAGC)
        updateSummaryB(sharedPreferences, keyprefDisableBuiltInNS)
        updateSummaryB(sharedPreferences, keyprefDisableWebRtcAGCAndHPF)
        updateSummaryList(keyprefSpeakerphone)
        updateSummaryB(sharedPreferences, keyprefEnableDataChannel)
        updateSummaryB(sharedPreferences, keyprefOrdered)
        updateSummary(sharedPreferences, keyprefMaxRetransmitTimeMs)
        updateSummary(sharedPreferences, keyprefMaxRetransmits)
        updateSummary(sharedPreferences, keyprefDataProtocol)
        updateSummaryB(sharedPreferences, keyprefNegotiated)
        updateSummary(sharedPreferences, keyprefDataId)
        setDataChannelEnable(sharedPreferences)
        updateSummary(sharedPreferences, keyPrefRoomServerUrl)
        updateSummaryB(sharedPreferences, keyPrefDisplayHud)
        updateSummaryB(sharedPreferences, keyPrefTracing)
        updateSummaryB(sharedPreferences, keyprefEnabledRtcEventLog)
        if (!Camera2Enumerator.isSupported(requireContext())) {
            findPreference<Preference>(keyprefCamera2)?.also {
                it.summary = getString(R.string.pref_camera2_not_supported)
                it.isEnabled = false
            }
        }
        if (!JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()) {
            findPreference<Preference>(keyprefDisableBuiltInAEC)?.also {
                it.summary = getString(R.string.pref_built_in_aec_not_available)
                it.isEnabled = false
            }
        }
        findPreference<Preference>(keyprefDisableBuiltInAGC)?.also {
            it.summary = getString(R.string.pref_built_in_agc_not_available)
            it.isEnabled = false
        }
        if (!JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()) {
            findPreference<Preference>(keyprefDisableBuiltInNS)?.also {
                it.summary = getString(R.string.pref_built_in_ns_not_available)
                it.isEnabled = false
            }
        }
    }

    override fun onPause() {
        val sharedPreferences = preferenceScreen.sharedPreferences
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

        super.onPause()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String
    ) {
        // clang-format off
        if (key == keyprefResolution || key == keyprefFps || key == keyprefMaxVideoBitrateType || key == keyPrefVideoCodec || key == keyprefStartAudioBitrateType || key == keyPrefAudioCodec || key == keyPrefRoomServerUrl || key == keyprefMaxRetransmitTimeMs || key == keyprefMaxRetransmits || key == keyprefDataProtocol || key == keyprefDataId) {
            updateSummary(sharedPreferences, key)
        } else if (key == keyprefMaxVideoBitrateValue || key == keyprefStartAudioBitrateValue) {
            updateSummaryBitrate(sharedPreferences, key)
        } else if (key == keyprefVideoCall || key == keyprefScreencapture || key == keyprefCamera2 || key == keyPrefTracing || key == keyprefCaptureQualitySlider || key == keyprefHwCodec || key == keyprefCaptureToTexture || key == keyprefFlexfec || key == keyprefNoAudioProcessing || key == keyprefAecDump || key == keyprefEnableSaveInputAudioToFile || key == keyprefOpenSLES || key == keyprefDisableBuiltInAEC || key == keyprefDisableBuiltInAGC || key == keyprefDisableBuiltInNS || key == keyprefDisableWebRtcAGCAndHPF || key == keyPrefDisplayHud || key == keyprefEnableDataChannel || key == keyprefOrdered || key == keyprefNegotiated || key == keyprefEnabledRtcEventLog) {
            updateSummaryB(sharedPreferences, key)
        } else if (key == keyprefSpeakerphone) {
            updateSummaryList(key)
        }
        // clang-format on
        if (key == keyprefMaxVideoBitrateType) {
            setVideoBitrateEnable(sharedPreferences)
        }
        if (key == keyprefStartAudioBitrateType) {
            setAudioBitrateEnable(sharedPreferences)
        }
        if (key == keyprefEnableDataChannel) {
            setDataChannelEnable(sharedPreferences)
        }
    }

    private fun updateSummary(sharedPreferences: SharedPreferences, key: String) {
        val updatedPref = findPreference<Preference>(key)
        // Set summary to be the user-description for the selected value
        updatedPref?.summary = sharedPreferences.getString(key, "")
    }

    private fun updateSummaryBitrate(sharedPreferences: SharedPreferences, key: String) {
        val updatedPref = findPreference<Preference>(key)
        updatedPref?.summary = sharedPreferences.getString(key, "") + " kbps"
    }

    private fun updateSummaryB(sharedPreferences: SharedPreferences, key: String) {
        val updatedPref = findPreference<Preference>(key)
        updatedPref?.summary = if (sharedPreferences.getBoolean(key, true))
            getString(R.string.pref_value_enabled) else getString(R.string.pref_value_disabled)
    }

    private fun updateSummaryList(key: String) {
        val updatedPref = findPreference<ListPreference>(key)
        updatedPref?.also { it.summary = it.entry }
    }


    private fun setVideoBitrateEnable(sharedPreferences: SharedPreferences) {
        val bitratePreferenceValue = findPreference<Preference>(keyprefMaxVideoBitrateValue)
        val bitrateTypeDefault = getString(R.string.pref_maxvideobitrate_default)
        val bitrateType = sharedPreferences.getString(keyprefMaxVideoBitrateType, bitrateTypeDefault)
        bitratePreferenceValue?.isEnabled = bitrateType != bitrateTypeDefault
    }

    private fun setAudioBitrateEnable(sharedPreferences: SharedPreferences) {
        val bitratePreferenceValue = findPreference<Preference>(keyprefStartAudioBitrateValue)
        val bitrateTypeDefault = getString(R.string.pref_startaudiobitrate_default)
        val bitrateType = sharedPreferences.getString(keyprefStartAudioBitrateType, bitrateTypeDefault)
        bitratePreferenceValue?.isEnabled = bitrateType != bitrateTypeDefault
    }

    private fun setDataChannelEnable(sharedPreferences: SharedPreferences) {
        val enabled = sharedPreferences.getBoolean(keyprefEnableDataChannel, true)
        findPreference<Preference>(keyprefOrdered)?.isEnabled = enabled
        findPreference<Preference>(keyprefMaxRetransmitTimeMs)?.isEnabled = enabled
        findPreference<Preference>(keyprefMaxRetransmits)?.isEnabled = enabled
        findPreference<Preference>(keyprefDataProtocol)?.isEnabled = enabled
        findPreference<Preference>(keyprefNegotiated)?.isEnabled = enabled
        findPreference<Preference>(keyprefDataId)?.isEnabled = enabled
    }
}