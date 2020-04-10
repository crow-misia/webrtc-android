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

import org.appspot.apprtc.AppRTCClient.SignalingParameters
import org.appspot.apprtc.util.AsyncHttpURLConnection
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents
import org.appspot.apprtc.util.toList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import org.webrtc.SessionDescription
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * AsyncTask that converts an AppRTC room URL into the set of signaling
 * parameters to use with that room.
 */
class RoomParametersFetcher(
    private val roomUrl: String,
    private val roomMessage: String,
    private val events: RoomParametersFetcherEvents
) {

    /**
     * Room parameters fetcher callbacks.
     */
    interface RoomParametersFetcherEvents {
        /**
         * Callback fired once the room's signaling parameters
         * SignalingParameters are extracted.
         */
        fun onSignalingParametersReady(params: SignalingParameters)

        /**
         * Callback for room parameters extraction error.
         */
        fun onSignalingParametersError(description: String)
    }

    fun makeRequest() {
        Timber.d("Connecting to room: %s", roomUrl)
        val httpConnection = AsyncHttpURLConnection("POST", roomUrl, roomMessage, object : AsyncHttpEvents {
            override fun onHttpError(errorMessage: String) {
                Timber.e("Room connection error: %s", errorMessage)
                events.onSignalingParametersError(errorMessage)
            }

            override fun onHttpComplete(response: String) {
                roomHttpResponseParse(response)
            }
        })
        httpConnection.send()
    }

    private fun roomHttpResponseParse(response: String) {
        Timber.d("Room response: %s", response)
        try {
            val iceCandidates = arrayListOf<IceCandidate>()
            var offerSdp: SessionDescription? = null
            var roomJson = JSONObject(response)
            val result = roomJson.getString("result")
            if (result != "SUCCESS") {
                events.onSignalingParametersError("Room response error: $result")
                return
            }
            roomJson = JSONObject(roomJson.getString("params"))
            val roomId = roomJson.getString("room_id")
            val clientId = roomJson.getString("client_id")
            val wssUrl = roomJson.getString("wss_url")
            val wssPostUrl = roomJson.getString("wss_post_url")
            val initiator = roomJson.getBoolean("is_initiator")
            if (!initiator) {
                val messagesString = roomJson.getString("messages")
                val messages = JSONArray(messagesString)
                for (i in 0 until messages.length()) {
                    val messageString = messages.getString(i)
                    val message = JSONObject(messageString)
                    val messageType = message.getString("type")
                    Timber.d("GAE->C #%d : %s", i, messageString)
                    when (messageType) {
                        "offer" -> {
                            offerSdp = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(messageType),
                                message.getString("sdp")
                            )
                        }
                        "candidate" -> {
                            val candidate = IceCandidate(
                                message.getString("id"),
                                message.getInt("label"),
                                message.getString("candidate")
                            )
                            iceCandidates.add(candidate)
                        }
                        else -> {
                            Timber.e("Unknown message: %s", messageString)
                        }
                    }
                }
            }
            Timber.d("RoomId: %s. ClientId: %s", roomId, clientId)
            Timber.d("Initiator: %b", initiator)
            Timber.d("WSS url: %s", wssUrl)
            Timber.d("WSS POST url: %s", wssPostUrl)
            val iceServers = iceServersFromPCConfigJSON(roomJson.getString("pc_config"))
            var isTurnPresent = false
            for (server in iceServers) {
                Timber.d("IceServer: %s", server)
                for (uri in server.urls) {
                    if (uri.startsWith("turn:")) {
                        isTurnPresent = true
                        break
                    }
                }
            }
            // Request TURN servers.
            if (!isTurnPresent && roomJson.optString("ice_server_url").isNotEmpty()) {
                val turnServers = requestTurnServers(roomJson.getString("ice_server_url"))
                for (turnServer in turnServers) {
                    Timber.d("TurnServer: %s", turnServer)
                    iceServers.add(turnServer)
                }
            }
            val params = SignalingParameters(iceServers, initiator, clientId, wssUrl, wssPostUrl, offerSdp, iceCandidates)
            events.onSignalingParametersReady(params)
        } catch (e: JSONException) {
            events.onSignalingParametersError("Room JSON parsing error: $e")
        } catch (e: IOException) {
            events.onSignalingParametersError("Room IO error: $e")
        }
    }

    // Requests & returns a TURN ICE Server based on a request URL.  Must be run
    // off the main thread!
    @Throws(IOException::class, JSONException::class)
    private fun requestTurnServers(url: String): List<IceServer> {
        val turnServers: MutableList<IceServer> =
            ArrayList()
        Timber.d("Request TURN from: %s", url)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.doOutput = true
        connection.setRequestProperty("REFERER", "https://appr.tc")
        connection.connectTimeout = TURN_HTTP_TIMEOUT_MS
        connection.readTimeout = TURN_HTTP_TIMEOUT_MS
        val responseCode = connection.responseCode
        if (responseCode != 200) {
            throw IOException(
                "Non-200 response when requesting TURN server from " + url + " : "
                        + connection.getHeaderField(null)
            )
        }
        val responseStream = connection.inputStream
        val response = drainStream(responseStream)
        connection.disconnect()
        Timber.d("TURN response: %s", response)
        val responseJSON = JSONObject(response)
        val iceServers = responseJSON.getJSONArray("iceServers")
        for (i in 0 until iceServers.length()) {
            val server = iceServers.getJSONObject(i)
            val turnUrls = server.getJSONArray("urls")
            val username =
                if (server.has("username")) server.getString("username") else ""
            val credential =
                if (server.has("credential")) server.getString("credential") else ""
            for (j in 0 until turnUrls.length()) {
                val turnUrl = turnUrls.getString(j)
                val turnServer = IceServer.builder(turnUrl)
                    .setUsername(username)
                    .setPassword(credential)
                    .createIceServer()
                turnServers.add(turnServer)
            }
        }
        return turnServers
    }

    // Return the list of ICE servers described by a WebRTCPeerConnection
    // configuration string.
    @Throws(JSONException::class)
    private fun iceServersFromPCConfigJSON(pcConfig: String): MutableList<IceServer> {
        val json = JSONObject(pcConfig)
        return json.getJSONArray("iceServers").toList { server ->
            val url = server.getString("urls")
            val credential = if (server.has("credential")) server.getString("credential") else ""
            return@toList IceServer.builder(url)
                .setPassword(credential)
                .createIceServer()
        }
    }

    companion object {
        private const val TURN_HTTP_TIMEOUT_MS = 5000

        // Return the contents of an InputStream as a String.
        private fun drainStream(`in`: InputStream): String {
            val s = Scanner(`in`.bufferedReader(Charsets.UTF_8)).useDelimiter("\\A")
            return if (s.hasNext()) s.next() else ""
        }
    }

}