/*
 *  Copyright 2013 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection.IceServer
import org.webrtc.SessionDescription

/**
 * AppRTCClient is the interface representing an AppRTC client.
 */
interface AppRTCClient {
    /**
     * Struct holding the connection parameters of an AppRTC room.
     */
    class RoomConnectionParameters @JvmOverloads constructor(
        val roomUrl: String,
        val roomId: String,
        val loopback: Boolean,
        val urlParameters: String? = null,
    )

    /**
     * Asynchronously connect to an AppRTC room URL using supplied connection
     * parameters. Once connection is established onConnectedToRoom()
     * callback with room parameters is invoked.
     */
    fun connectToRoom(connectionParameters: RoomConnectionParameters)

    /**
     * Send offer SDP to the other participant.
     */
    fun sendOfferSdp(sdp: SessionDescription)

    /**
     * Send answer SDP to the other participant.
     */
    fun sendAnswerSdp(sdp: SessionDescription)

    /**
     * Send Ice candidate to the other participant.
     */
    fun sendLocalIceCandidate(candidate: IceCandidate)

    /**
     * Send removed ICE candidates to the other participant.
     */
    fun sendLocalIceCandidateRemovals(candidates: List<IceCandidate>)

    /**
     * Disconnect from room.
     */
    fun disconnectFromRoom()

    /**
     * Struct holding the signaling parameters of an AppRTC room.
     */
    class SignalingParameters(
        val iceServers: List<IceServer>,
        val initiator: Boolean,
        val clientId: String?,
        val wssUrl: String,
        val wssPostUrl: String,
        val offerSdp: SessionDescription?,
        val iceCandidates: List<IceCandidate>,
    )

    /**
     * Callback interface for messages delivered on signaling channel.
     *
     *
     * Methods are guaranteed to be invoked on the UI thread of `activity`.
     */
    interface SignalingEvents {
        /**
         * Callback fired once the room's signaling parameters
         * SignalingParameters are extracted.
         */
        fun onConnectedToRoom(params: SignalingParameters)

        /**
         * Callback fired once remote SDP is received.
         */
        fun onRemoteDescription(desc: SessionDescription)

        /**
         * Callback fired once remote Ice candidate is received.
         */
        fun onRemoteIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once remote Ice candidate removals are received.
         */
        fun onRemoteIceCandidatesRemoved(candidates: List<IceCandidate>)

        /**
         * Callback fired once channel is closed.
         */
        fun onChannelClose()

        /**
         * Callback fired once channel error happened.
         */
        fun onChannelError(description: String)
    }
}
