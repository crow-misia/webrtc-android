/*
 *  Copyright 2015 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc.util

import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.*

/**
 * Asynchronous http requests implementation.
 */
class AsyncHttpURLConnection(
    private val method: String,
    private val url: String,
    private val message: String?,
    private val events: AsyncHttpEvents,
    private var contentType: String = "text/plain; charset=utf-8",
) {
    /**
     * Http requests callbacks.
     */
    interface AsyncHttpEvents {
        fun onHttpError(errorMessage: String)
        fun onHttpComplete(response: String)
    }

    fun send() {
        Thread { sendHttpMessage() }.start()
    }

    private fun sendHttpMessage() {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            val postData = message?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
            connection.requestMethod = method
            connection.useCaches = false
            connection.doInput = true
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            // TODO(glaznev) - query request origin from pref_room_server_url_key preferences.
            connection.addRequestProperty("host", "0.0.0.0")
            var doOutput = false
            if (method == "POST") {
                doOutput = true
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(postData.size)
            }
            connection.setRequestProperty("Content-Type", contentType)

            // Send POST request.
            if (doOutput && postData.isNotEmpty()) {
                connection.outputStream.use {
                    it.write(postData)
                }
            }

            // Get response.
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                events.onHttpError("Non-200 response to $method to URL: $url : ${connection.getHeaderField(null)}")
                connection.disconnect()
                return
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                drainStream(it)
            }
            connection.disconnect()
            events.onHttpComplete(response)
        } catch (e: SocketTimeoutException) {
            events.onHttpError("HTTP $method to $url timeout")
        } catch (e: IOException) {
            events.onHttpError("HTTP $method to $url error: ${e.message}")
        }
    }

    companion object {
        private const val HTTP_TIMEOUT_MS = 8000

        // Return the contents of an InputStream as a String.
        private fun drainStream(source: Reader): String {
            val s = Scanner(source).useDelimiter("\\A")
            return if (s.hasNext()) s.next() else ""
        }
    }
}
