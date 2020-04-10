/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc.util

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * AppRTCUtils provides helper functions for managing thread safety.
 */
object AppRTCUtils {
    /** Helper method for building a string of thread information. */
    @JvmStatic
    fun getThreadInfo(): String = "@[name=" + Thread.currentThread().name + ", id=" + Thread.currentThread().id + "]"

    /** Information about the current build, taken from system properties.  */
    @JvmStatic
    fun logDeviceInfo() {
        Timber.d("Android SDK: %d, Release: %s, Brand: %s, Device: %s, Id: %s, Hardware: %s, Manufacturer: %s, Model: %s, Product: %s",
            Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.BRAND, Build.DEVICE,
            Build.ID, Build.HARDWARE, Build.MANUFACTURER, Build.MODEL, Build.PRODUCT
        )
    }
}

inline fun <reified T> JSONArray.toList(mapper: (JSONObject) -> T): MutableList<T> {
    val length = length()
    val results = ArrayList<T>(length)
    (0 until length).forEach {
        results[it] = mapper(getJSONObject(it))
    }
    return results
}
