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
import okhttp3.*
import okio.ByteString
import org.appspot.apprtc.util.AsyncHttpURLConnection
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * WebSocket client implementation.
 *
 *
 * All public methods should be called from a looper executor thread
 * passed in a constructor, otherwise exception will be thrown.
 * All events are dispatched on the same thread.
 */
class WebSocketChannelClient(
    private val handler: Handler,
    private val events: WebSocketChannelEvents,
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private var wsServerUrl: String? = null
    private var postServerUrl: String? = null
    private var roomID: String? = null
    private var clientID: String? = null
    var state = WebSocketConnectionState.NEW
        private set

    // Do not remove this member variable. If this is removed, the observer gets garbage collected and
    // this causes test breakages.
    private var wsObserver: WebSocketObserver? = null
    private val closeEventLock = ReentrantLock()
    private val closeEventCondition = closeEventLock.newCondition()
    private var closeEvent = false

    // WebSocket send queue. Messages are added to the queue when WebSocket
    // client is not registered and are consumed in register() call.
    private val wsSendQueue = arrayListOf<String>()

    /**
     * Possible WebSocket connection states.
     */
    enum class WebSocketConnectionState {
        NEW, CONNECTED, REGISTERED, CLOSED, ERROR
    }

    /**
     * Callback interface for messages delivered on WebSocket.
     * All events are dispatched from a looper executor thread.
     */
    interface WebSocketChannelEvents {
        fun onWebSocketMessage(message: String)
        fun onWebSocketClose()
        fun onWebSocketError(description: String)
    }

    fun connect(wsUrl: String, postUrl: String) {
        checkIfCalledOnValidThread()
        if (state != WebSocketConnectionState.NEW) {
            Timber.e("WebSocket is already connected.")
            return
        }
        wsServerUrl = wsUrl
        postServerUrl = postUrl
        closeEvent = false
        Timber.d("Connecting WebSocket to: %s. Post URL: %s", wsUrl, postUrl)
        val request = Request.Builder().url(wsUrl).header("Origin", wsUrl).build()
        wsObserver = WebSocketObserver().also {
            ws = client.newWebSocket(request, it)
        }
    }

    fun register(roomID: String, clientID: String?) {
        checkIfCalledOnValidThread()
        this.roomID = roomID
        this.clientID = clientID
        if (state != WebSocketConnectionState.CONNECTED) {
            Timber.w("WebSocket register() in state %s", state)
            return
        }
        Timber.d("Registering WebSocket for room %s. ClientID: %s", roomID, clientID)
        val json = JSONObject()
        try {
            json.put("cmd", "register")
            json.put("roomid", roomID)
            json.put("clientid", clientID)
            Timber.d("C->WSS: %s", json)
            ws?.send(json.toString())
            state = WebSocketConnectionState.REGISTERED
            // Send any previously accumulated messages.
            for (sendMessage in wsSendQueue) {
                send(sendMessage)
            }
            wsSendQueue.clear()
        } catch (e: JSONException) {
            reportError("WebSocket register JSON error: " + e.message)
        }
    }

    fun send(message: String) {
        checkIfCalledOnValidThread()
        when (state) {
            WebSocketConnectionState.NEW, WebSocketConnectionState.CONNECTED -> {
                // Store outgoing messages and send them after websocket client
                // is registered.
                Timber.d("WS ACC: %s", message)
                wsSendQueue.add(message)
                return
            }
            WebSocketConnectionState.ERROR, WebSocketConnectionState.CLOSED -> {
                Timber.e("WebSocket send() in error or closed state : %s", message)
                return
            }
            WebSocketConnectionState.REGISTERED -> {
                val json = JSONObject()
                try {
                    json.put("cmd", "send")
                    json.put("msg", message)
                    val newMessage = json.toString()
                    Timber.d("C->WSS: %s", newMessage)
                    ws?.send(newMessage)
                } catch (e: JSONException) {
                    reportError("WebSocket send JSON error: " + e.message)
                }
            }
        }
    }

    // This call can be used to send WebSocket messages before WebSocket
    // connection is opened.
    fun post(message: String) {
        checkIfCalledOnValidThread()
        sendWSSMessage("POST", message)
    }

    fun disconnect(waitForComplete: Boolean) {
        checkIfCalledOnValidThread()
        Timber.d("Disconnect WebSocket. State: %s", state)
        if (state == WebSocketConnectionState.REGISTERED) {
            // Send "bye" to WebSocket server.
            send("{\"type\": \"bye\"}")
            state = WebSocketConnectionState.CONNECTED
            // Send http DELETE to http WebSocket server.
            sendWSSMessage("DELETE", "")
        }
        // Close WebSocket in CONNECTED or ERROR states only.
        if (state == WebSocketConnectionState.CONNECTED || state == WebSocketConnectionState.ERROR) {
            ws?.close(1000, "Goodbye !")
            state = WebSocketConnectionState.CLOSED

            // Wait for websocket close event to prevent websocket library from
            // sending any pending messages to deleted looper thread.
            if (waitForComplete) {
                try {
                    closeEventLock.lock()
                    while (!closeEvent) {
                        try {
                            closeEventCondition.await(CLOSE_TIMEOUT, TimeUnit.MILLISECONDS)
                            break
                        } catch (e: InterruptedException) {
                            Timber.e(e, "Wait error:")
                        }
                    }
                } finally {
                    closeEventLock.unlock()
                }
            }
        }
        Timber.d("Disconnecting WebSocket done.")
    }

    private fun reportError(errorMessage: String) {
        Timber.e(errorMessage)
        handler.post {
            if (state != WebSocketConnectionState.ERROR) {
                state = WebSocketConnectionState.ERROR
                events.onWebSocketError(errorMessage)
            }
        }
    }

    // Asynchronously send POST/DELETE to WebSocket server.
    private fun sendWSSMessage(method: String, message: String) {
        val postUrl = "$postServerUrl/$roomID/$clientID"
        Timber.d("WS %s : %s: %s", method, postUrl, message)
        val httpConnection =
            AsyncHttpURLConnection(method, postUrl, message, object : AsyncHttpEvents {
                override fun onHttpError(errorMessage: String) {
                    reportError("WS $method error: $errorMessage")
                }

                override fun onHttpComplete(response: String) = Unit
            })
        httpConnection.send()
    }

    // Helper method for debugging purposes. Ensures that WebSocket method is
    // called on a looper thread.
    private fun checkIfCalledOnValidThread() {
        check(!(Thread.currentThread() !== handler.looper.thread)) { "WebSocket method is not called on valid thread" }
    }

    private inner class WebSocketObserver : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.d("WebSocket connection opened to: %s", wsServerUrl)
            handler.post {
                state = WebSocketConnectionState.CONNECTED
                // Check if we have pending register request.
                val roomID = roomID ?: return@post
                val clientID = clientID ?: return@post
                register(roomID, clientID)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            reportError("WebSocket connection error: " + t.message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.d(
                "WebSocket connection closed. Code: %d. Reason: %s. State: %s",
                code,
                reason,
                state
            )
            try {
                closeEventLock.lock()
                closeEvent = true
                closeEventCondition.signalAll()
            } finally {
                closeEventLock.unlock()
            }

            handler.post {
                if (state != WebSocketConnectionState.CLOSED) {
                    state = WebSocketConnectionState.CLOSED
                    events.onWebSocketClose()
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Timber.d("WSS->C: %s", text)
            handler.post {
                if (state == WebSocketConnectionState.CONNECTED || state == WebSocketConnectionState.REGISTERED) {
                    events.onWebSocketMessage(text)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
    }

    companion object {
        private const val CLOSE_TIMEOUT = 1000L
    }
}
