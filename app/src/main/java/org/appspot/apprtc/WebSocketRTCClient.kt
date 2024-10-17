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

import android.os.Handler
import android.os.HandlerThread
import org.appspot.apprtc.AppRTCClient.*
import org.appspot.apprtc.RoomParametersFetcher.RoomParametersFetcherEvents
import org.appspot.apprtc.WebSocketChannelClient.WebSocketChannelEvents
import org.appspot.apprtc.WebSocketChannelClient.WebSocketConnectionState
import org.appspot.apprtc.util.AsyncHttpURLConnection
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents
import org.appspot.apprtc.util.toList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber

/**
 * Negotiates signaling for chatting with https://appr.tc "rooms".
 * Uses the client<->server specifics of the apprtc AppEngine webapp.
 *
 *
 * To use: create an instance of this object (registering a message handler) and
 * call connectToRoom().  Once room connection is established
 * onConnectedToRoom() callback with room parameters is invoked.
 * Messages to other party (with local Ice candidates and answer SDP) can
 * be sent after WebSocket connection is established.
 */
class WebSocketRTCClient(private val events: SignalingEvents) : AppRTCClient,
    WebSocketChannelEvents {
    private enum class ConnectionState {
        NEW, CONNECTED, CLOSED, ERROR,
    }

    private enum class MessageType {
        MESSAGE, LEAVE,
    }

    private val handler: Handler
    private var initiator = false
    private var wsClient: WebSocketChannelClient? = null
    private var roomState = ConnectionState.NEW
    private lateinit var connectionParameters: RoomConnectionParameters
    private lateinit var messageUrl: String
    private lateinit var leaveUrl: String

    // --------------------------------------------------------------------
    // AppRTCClient interface implementation.
    // Asynchronously connect to an AppRTC room URL using supplied connection
    // parameters, retrieves room parameters and connect to WebSocket server.
    override fun connectToRoom(connectionParameters: RoomConnectionParameters) {
        this.connectionParameters = connectionParameters
        handler.post { connectToRoomInternal() }
    }

    override fun disconnectFromRoom() {
        handler.post {
            disconnectFromRoomInternal()
            handler.looper.quit()
        }
    }

    // Connects to room - function runs on a local looper thread.
    private fun connectToRoomInternal() {
        val connectionUrl = getConnectionUrl(connectionParameters)
        Timber.d("Connect to room: %s", connectionUrl)
        roomState = ConnectionState.NEW
        wsClient = WebSocketChannelClient(handler, this)
        val callbacks: RoomParametersFetcherEvents = object : RoomParametersFetcherEvents {
            override fun onSignalingParametersReady(params: SignalingParameters) {
                handler.post { signalingParametersReady(params) }
            }

            override fun onSignalingParametersError(description: String) {
                reportError(description)
            }
        }
        RoomParametersFetcher(connectionUrl, "", callbacks).makeRequest()
    }

    // Disconnect from room and send bye messages - runs on a local looper thread.
    private fun disconnectFromRoomInternal() {
        Timber.d("Disconnect. Room state: %s", roomState)
        if (roomState == ConnectionState.CONNECTED) {
            Timber.d("Closing room.")
            sendPostMessage(MessageType.LEAVE, leaveUrl, null)
        }
        roomState = ConnectionState.CLOSED
        wsClient?.disconnect(true)
    }

    // Helper functions to get connection, post message and leave message URLs
    private fun getConnectionUrl(connectionParameters: RoomConnectionParameters): String {
        return (connectionParameters.roomUrl + "/" + ROOM_JOIN + "/" + connectionParameters.roomId
                + getQueryString(connectionParameters))
    }

    private fun getMessageUrl(
        connectionParameters: RoomConnectionParameters,
        signalingParameters: SignalingParameters
    ): String {
        return (connectionParameters.roomUrl + "/" + ROOM_MESSAGE + "/" + connectionParameters.roomId
                + "/" + signalingParameters.clientId + getQueryString(connectionParameters))
    }

    private fun getLeaveUrl(
        connectionParameters: RoomConnectionParameters,
        signalingParameters: SignalingParameters
    ): String {
        return (connectionParameters.roomUrl + "/" + ROOM_LEAVE + "/" + connectionParameters.roomId + "/"
                + signalingParameters.clientId + getQueryString(connectionParameters))
    }

    private fun getQueryString(connectionParameters: RoomConnectionParameters): String {
        return connectionParameters.urlParameters?.let { "?$it" }.orEmpty()
    }

    // Callback issued when room parameters are extracted. Runs on local
    // looper thread.
    private fun signalingParametersReady(signalingParameters: SignalingParameters) {
        Timber.d("Room connection completed.")
        if (connectionParameters.loopback && (!signalingParameters.initiator || signalingParameters.offerSdp != null)) {
            reportError("Loopback room is busy.")
            return
        }
        if (!connectionParameters.loopback && !signalingParameters.initiator && signalingParameters.offerSdp == null) {
            Timber.w("No offer SDP in room response.")
        }
        initiator = signalingParameters.initiator
        messageUrl = getMessageUrl(connectionParameters, signalingParameters)
        leaveUrl = getLeaveUrl(connectionParameters, signalingParameters)
        Timber.d("Message URL: %s", messageUrl)
        Timber.d("Leave URL: %s", leaveUrl)
        roomState = ConnectionState.CONNECTED

        // Fire connection and signaling parameters events.
        events.onConnectedToRoom(signalingParameters)

        // Connect and register WebSocket client.
        wsClient?.also {
            it.connect(signalingParameters.wssUrl, signalingParameters.wssPostUrl)
            it.register(connectionParameters.roomId, signalingParameters.clientId)
        }
    }

    // Send local offer SDP to the other participant.
    override fun sendOfferSdp(sdp: SessionDescription) {
        handler.post(Runnable {
            if (roomState != ConnectionState.CONNECTED) {
                reportError("Sending offer SDP in non connected state.")
                return@Runnable
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "offer")
            sendPostMessage(
                MessageType.MESSAGE,
                messageUrl,
                json.toString()
            )
            if (connectionParameters.loopback) {
                // In loopback mode rename this offer to answer and route it back.
                val sdpAnswer = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm("answer"), sdp.description
                )
                events.onRemoteDescription(sdpAnswer)
            }
        })
    }

    // Send local answer SDP to the other participant.
    override fun sendAnswerSdp(sdp: SessionDescription) {
        handler.post(Runnable {
            if (connectionParameters.loopback) {
                Timber.e("Sending answer in loopback mode.")
                return@Runnable
            }
            val json = JSONObject()
            jsonPut(json, "sdp", sdp.description)
            jsonPut(json, "type", "answer")
            wsClient?.send(json.toString())
        })
    }

    // Send Ice candidate to the other participant.
    override fun sendLocalIceCandidate(candidate: IceCandidate) {
        handler.post(Runnable {
            val json = JSONObject()
            jsonPut(json, "type", "candidate")
            jsonPut(json, "label", candidate.sdpMLineIndex)
            jsonPut(json, "id", candidate.sdpMid)
            jsonPut(json, "candidate", candidate.sdp)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate in non connected state.")
                    return@Runnable
                }
                sendPostMessage(MessageType.MESSAGE, messageUrl, json.toString())
                if (connectionParameters.loopback) {
                    events.onRemoteIceCandidate(candidate)
                }
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient?.send(json.toString())
            }
        })
    }

    // Send removed Ice candidates to the other participant.
    override fun sendLocalIceCandidateRemovals(candidates: List<IceCandidate>) {
        handler.post(Runnable {
            val json = JSONObject()
            jsonPut(json, "type", "remove-candidates")
            val jsonArray = JSONArray()
            for (candidate in candidates) {
                jsonArray.put(toJsonCandidate(candidate))
            }
            jsonPut(json, "candidates", jsonArray)
            if (initiator) {
                // Call initiator sends ice candidates to GAE server.
                if (roomState != ConnectionState.CONNECTED) {
                    reportError("Sending ICE candidate removals in non connected state.")
                    return@Runnable
                }
                sendPostMessage(
                    MessageType.MESSAGE,
                    messageUrl,
                    json.toString()
                )
                if (connectionParameters.loopback) {
                    events.onRemoteIceCandidatesRemoved(candidates)
                }
            } else {
                // Call receiver sends ice candidates to websocket server.
                wsClient?.send(json.toString())
            }
        })
    }

    // --------------------------------------------------------------------
    // WebSocketChannelEvents interface implementation.
    // All events are called by WebSocketChannelClient on a local looper thread
    // (passed to WebSocket client constructor).
    override fun onWebSocketMessage(message: String) {
        if (wsClient?.state != WebSocketConnectionState.REGISTERED) {
            Timber.e("Got WebSocket message in non registered state.")
            return
        }
        try {
            var json = JSONObject(message)
            val msgText = json.getString("msg")
            val errorText = json.optString("error")
            if (msgText.isNotEmpty()) {
                json = JSONObject(msgText)
                when (val type = json.optString("type")) {
                    "candidate" -> {
                        events.onRemoteIceCandidate(toJavaCandidate(json))
                    }
                    "remove-candidates" -> {
                        val candidateArray = json.getJSONArray("candidates")
                        events.onRemoteIceCandidatesRemoved(candidateArray.toList {
                            toJavaCandidate(
                                it
                            )
                        })
                    }
                    "answer" -> {
                        if (initiator) {
                            val sdp = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type),
                                json.getString("sdp")
                            )
                            events.onRemoteDescription(sdp)
                        } else {
                            reportError("Received answer for call initiator: $message")
                        }
                    }
                    "offer" -> {
                        if (!initiator) {
                            val sdp = SessionDescription(
                                SessionDescription.Type.fromCanonicalForm(type),
                                json.getString("sdp")
                            )
                            events.onRemoteDescription(sdp)
                        } else {
                            reportError("Received offer for call receiver: $message")
                        }
                    }
                    "bye" -> {
                        events.onChannelClose()
                    }
                    else -> {
                        reportError("Unexpected WebSocket message: $message")
                    }
                }
            } else {
                if (errorText.isNotEmpty()) {
                    reportError("WebSocket error message: $errorText")
                } else {
                    reportError("Unexpected WebSocket message: $message")
                }
            }
        } catch (e: JSONException) {
            reportError("WebSocket message JSON parsing error: $e")
        }
    }

    override fun onWebSocketClose() {
        events.onChannelClose()
    }

    override fun onWebSocketError(description: String) {
        reportError("WebSocket error: $description")
    }

    // --------------------------------------------------------------------
    // Helper functions.
    private fun reportError(errorMessage: String) {
        Timber.e(errorMessage)
        handler.post {
            if (roomState != ConnectionState.ERROR) {
                roomState = ConnectionState.ERROR
                events.onChannelError(errorMessage)
            }
        }
    }

    // Send SDP or ICE candidate to a room server.
    private fun sendPostMessage(messageType: MessageType, url: String, message: String?) {
        val logInfo = buildString {
            append(url)
            message?.also {
                append(". Message: ").append(it)
            }
        }
        Timber.d("C->GAE: %s", logInfo)
        val httpConnection = AsyncHttpURLConnection("POST", url, message, object : AsyncHttpEvents {
            override fun onHttpError(errorMessage: String) {
                reportError("GAE POST error: $errorMessage")
            }

            override fun onHttpComplete(response: String) {
                if (messageType == MessageType.MESSAGE) {
                    try {
                        val roomJson = JSONObject(response)
                        val result = roomJson.getString("result")
                        if (result != "SUCCESS") {
                            reportError("GAE POST error: $result")
                        }
                    } catch (e: JSONException) {
                        reportError("GAE POST JSON error: $e")
                    }
                }
            }
        })
        httpConnection.send()
    }

    // Converts a Java candidate to a JSONObject.
    private fun toJsonCandidate(candidate: IceCandidate): JSONObject {
        val json = JSONObject()
        jsonPut(json, "label", candidate.sdpMLineIndex)
        jsonPut(json, "id", candidate.sdpMid)
        jsonPut(json, "candidate", candidate.sdp)
        return json
    }

    // Converts a JSON candidate to a Java object.
    @Throws(JSONException::class)
    fun toJavaCandidate(json: JSONObject): IceCandidate {
        return IceCandidate(
            json.getString("id"), json.getInt("label"), json.getString("candidate")
        )
    }

    companion object {
        private const val TAG = "WSRTCClient"
        private const val ROOM_JOIN = "join"
        private const val ROOM_MESSAGE = "message"
        private const val ROOM_LEAVE = "leave"

        // Put a `key`->`value` mapping in `json`.
        private fun jsonPut(json: JSONObject, key: String, value: Any) {
            try {
                json.put(key, value)
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }
        }
    }

    init {
        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
    }
}
