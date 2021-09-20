/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import org.webrtc.ThreadUtils
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantLock

/**
 * Replacement for WebSocketChannelClient for direct communication between two IP addresses. Handles
 * the signaling between the two clients using a TCP connection.
 *
 *
 * All public methods should be called from a looper executor thread
 * passed in a constructor, otherwise exception will be thrown.
 * All events are dispatched on the same thread.
 *
 * @param eventListener Listener that will receive events from the client.
 * @param ip            IP address to listen on or connect to.
 * @param port          Port to listen on or connect to.
 */
class TCPChannelClient(
    private val executor: ExecutorService,
    private val eventListener: TCPChannelEvents,
    ip: String?,
    port: Int,
) {
    private val executorThreadCheck: ThreadUtils.ThreadChecker = ThreadUtils.ThreadChecker()
    private var socket: TCPSocket? = null

    /**
     * Callback interface for messages delivered on TCP Connection. All callbacks are invoked from the
     * looper executor thread.
     */
    interface TCPChannelEvents {
        fun onTCPConnected(server: Boolean)
        fun onTCPMessage(message: String)
        fun onTCPError(description: String)
        fun onTCPClose()
    }

    /**
     * Disconnects the client if not already disconnected. This will fire the onTCPClose event.
     */
    fun disconnect() {
        executorThreadCheck.checkIsOnValidThread()
        socket?.disconnect()
        socket = null
    }

    /**
     * Sends a message on the socket.
     *
     * @param message Message to be sent.
     */
    fun send(message: String) {
        executorThreadCheck.checkIsOnValidThread()
        socket?.send(message)
    }

    /**
     * Helper method for firing onTCPError events. Calls onTCPError on the executor thread.
     */
    private fun reportError(message: String) {
        Timber.e("TCP Error: %s", message)
        executor.execute { eventListener.onTCPError(message) }
    }

    /**
     * Base class for server and client sockets. Contains a listening thread that will call
     * eventListener.onTCPMessage on new messages.
     */
    private abstract inner class TCPSocket : Thread() {
        // Lock for editing out and rawSocket
        protected val rawSocketLock = ReentrantLock()
        private var out: BufferedWriter? = null
        private var rawSocket: Socket? = null

        /**
         * Connect to the peer, potentially a slow operation.
         *
         * @return Socket connection, null if connection failed.
         */
        abstract fun connect(): Socket?

        /** Returns true if sockets is a server rawSocket.  */
        abstract val isServer: Boolean

        /**
         * The listening thread.
         */
        override fun run() {
            Timber.d("Listening thread started...")

            // Receive connection to temporary variable first, so we don't block.
            val tempSocket = connect()
            val `in`: BufferedReader
            Timber.d("TCP connection established.")
            try {
                rawSocketLock.lock()
                if (rawSocket != null) {
                    Timber.e("Socket already existed and will be replaced.")
                }
                rawSocket = tempSocket

                // Connecting failed, error has already been reported, just exit.
                val rawSocket = rawSocket ?: return
                out = rawSocket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                `in` = rawSocket.getInputStream().bufferedReader(Charsets.UTF_8)
            } catch (e: IOException) {
                reportError("Failed to open IO on rawSocket: " + e.message)
                return
            } finally {
                rawSocketLock.unlock()
            }
            Timber.v("Execute onTCPConnected")
            executor.execute {
                Timber.v("Run onTCPConnected")
                eventListener.onTCPConnected(isServer)
            }
            while (true) {
                val message: String? = try {
                    `in`.readLine()
                } catch (e: IOException) {
                    try {
                        rawSocketLock.lock()
                        // If socket was closed, this is expected.
                        rawSocket ?: break
                    } finally {
                        rawSocketLock.unlock()
                    }
                    reportError("Failed to read from rawSocket: " + e.message)
                    break
                }

                // No data received, rawSocket probably closed.
                message ?: break
                executor.execute {
                    Timber.v("Receive: %s", message)
                    eventListener.onTCPMessage(message)
                }
            }
            Timber.d("Receiving thread exiting...")

            // Close the rawSocket if it is still open.
            disconnect()
        }

        /** Closes the rawSocket if it is still open. Also fires the onTCPClose event.  */
        open fun disconnect() {
            try {
                rawSocketLock.lock()
                rawSocket?.also {
                    it.close()
                    rawSocket = null
                    out = null
                    executor.execute { eventListener.onTCPClose() }
                }
            } catch (e: IOException) {
                reportError("Failed to close rawSocket: " + e.message)
            } finally {
                rawSocketLock.unlock()
            }
        }

        /**
         * Sends a message on the socket. Should only be called on the executor thread.
         */
        fun send(message: String) {
            Timber.v("Send: %s", message)
            try {
                rawSocketLock.lock()
                out?.also {
                    it.write("$message\n")
                    it.flush()
                } ?: run {
                    reportError("Sending data on closed socket.")
                }
            } finally {
                rawSocketLock.unlock()
            }
        }
    }

    private inner class TCPSocketServer(private val address: InetAddress, private val port: Int) :
        TCPSocket() {
        // Server socket is also guarded by rawSocketLock.
        private var serverSocket: ServerSocket? = null

        /** Opens a listening socket and waits for a connection.  */
        override fun connect(): Socket? {
            Timber.d("Listening on [%s]:%d", address.hostAddress, port)
            val tempSocket = try {
                ServerSocket(port, 0, address)
            } catch (e: IOException) {
                reportError("Failed to create server socket: " + e.message)
                return null
            }
            try {
                rawSocketLock.lock()
                serverSocket?.also {
                    Timber.e("Server rawSocket was already listening and new will be opened.")
                }
                serverSocket = tempSocket
            } finally {
                rawSocketLock.unlock()
            }
            return try {
                tempSocket.accept()
            } catch (e: IOException) {
                reportError("Failed to receive connection: " + e.message)
                null
            }
        }

        /** Closes the listening socket and calls super.  */
        override fun disconnect() {
            try {
                rawSocketLock.lock()
                serverSocket?.close()
                serverSocket = null
            } catch (e: IOException) {
                reportError("Failed to close server socket: " + e.message)
            } finally {
                rawSocketLock.unlock()
            }
            super.disconnect()
        }

        override val isServer: Boolean
            get() = true

    }

    private inner class TCPSocketClient(private val address: InetAddress, private val port: Int) :
        TCPSocket() {
        /** Connects to the peer.  */
        override fun connect(): Socket? {
            Timber.d("Connecting to [%s]:%d", address.hostAddress, port)
            return try {
                Socket(address, port)
            } catch (e: IOException) {
                reportError("Failed to connect: " + e.message)
                null
            }
        }

        override val isServer: Boolean
            get() = false

    }

    /**
     * Initializes the TCPChannelClient. If IP is a local IP address, starts a listening server on
     * that IP. If not, instead connects to the IP.
     */
    init {
        executorThreadCheck.detachThread()
        try {
            val address = InetAddress.getByName(ip)

            val socket = if (address.isAnyLocalAddress) {
                TCPSocketServer(address, port)
            } else {
                TCPSocketClient(address, port)
            }
            this.socket = socket
            socket.start()
        } catch (e: UnknownHostException) {
            reportError("Invalid IP address.")
        }
    }
}