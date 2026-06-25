/*
 * Copyright 2023-2026 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.e2ee

import android.util.Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.annotations.Beta
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.util.LKLog
import livekit.org.webrtc.FrameCryptor
import livekit.org.webrtc.FrameCryptor.FrameCryptionState
import livekit.org.webrtc.FrameCryptorAlgorithm
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender

private const val ZYNA_E2EE_DIAGNOSTIC_TAG = "ZynaLiveKitE2EE"

class E2EEManager
@AssistedInject
constructor(
    @Assisted val keyProvider: KeyProvider,
    val peerConnectionFactory: PeerConnectionFactory,
    dataPacketCryptorManagerFactory: DataPacketCryptorManager.Factory,
) {
    private var room: Room? = null
    private val frameCryptorsLock = Any()
    private val frameCryptors = mutableMapOf<Pair<String, Participant.Identity>, FrameCryptor>()
    private var algorithm: FrameCryptorAlgorithm = FrameCryptorAlgorithm.AES_GCM
    private lateinit var emitEvent: (roomEvent: RoomEvent) -> Unit?

    internal var dataPacketCryptorManager: DataPacketCryptorManager = dataPacketCryptorManagerFactory.create(keyProvider)

    init {
        (keyProvider as? BaseKeyProvider)?.participantKeyIndexListener = { participantId, keyIndex ->
            updateParticipantFrameCryptorKeyIndex(participantId, keyIndex)
        }
    }

    var enabled: Boolean = false
        set(value) {
            field = value
            val currentFrameCryptors = synchronized(frameCryptorsLock) {
                frameCryptors.values.toList()
            }
            currentFrameCryptors.forEach { frameCryptor ->
                frameCryptor.isEnabled = enabled
            }
        }

    /**
     * Enables data channel encryption. Decryption is always enabled for forward compatibility.
     */
    @Beta
    var dataChannelEncryptionEnabled = false

    fun isDataChannelEncryptionEnabled(): Boolean {
        return enabled && dataChannelEncryptionEnabled
    }

    fun keyProvider(): KeyProvider {
        return this.keyProvider
    }

    fun setup(room: Room, emitEvent: (roomEvent: RoomEvent) -> Unit) {
        if (this.room != room && this.room != null) {
            // E2EEManager already setup, clean up first
            cleanup()
        }
        this.enabled = true
        this.room = room
        this.emitEvent = emitEvent
        this.room?.localParticipant?.trackPublications?.forEach { item ->
            val participant = this.room!!.localParticipant
            val publication = item.value
            if (publication.track != null) {
                addPublishedTrack(publication.track!!, publication, participant, room)
            }
        }
        this.room?.remoteParticipants?.forEach { item ->
            val participant = item.value
            participant.trackPublications.forEach { item ->
                val publication = item.value
                if (publication.track != null) {
                    addSubscribedTrack(publication.track!!, publication, participant, room)
                }
            }
        }
    }

    fun addSubscribedTrack(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {
        val rtpReceiver: RtpReceiver? = when (publication.track!!) {
            is RemoteAudioTrack -> (publication.track!! as RemoteAudioTrack).receiver
            is RemoteVideoTrack -> (publication.track!! as RemoteVideoTrack).receiver
            else -> {
                throw IllegalArgumentException("unsupported track type")
            }
        }
        val frameCryptor = addRtpReceiver(rtpReceiver!!, participant.identity!!, publication.sid, publication.track!!.kind.name.lowercase())
        frameCryptor.setObserver { trackId, state ->
            LKLog.i { "Receiver::onFrameCryptionStateChanged: $trackId, state:  $state" }
            emitEvent(
                RoomEvent.TrackE2EEStateEvent(
                    room,
                    publication.track!!,
                    publication,
                    participant,
                    state = e2eeStateFromFrameCryptoState(state),
                ),
            )
        }
    }

    fun removeSubscribedTrack(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {
        val trackId = publication.sid
        val participantId = participant.identity
        val frameCryptor = synchronized(frameCryptorsLock) {
            frameCryptors.remove(trackId to participantId)
        }
        if (frameCryptor != null) {
            frameCryptor.isEnabled = false
            frameCryptor.dispose()
        }
    }

    fun addPublishedTrack(track: Track, publication: TrackPublication, participant: LocalParticipant, room: Room) {
        val rtpSender: RtpSender = when (publication.track!!) {
            is LocalAudioTrack -> (publication.track!! as LocalAudioTrack).sender
            is LocalVideoTrack -> (publication.track!! as LocalVideoTrack).sender
            else -> {
                throw IllegalArgumentException("unsupported track type")
            }
        } ?: throw IllegalArgumentException("rtpSender is null")

        val frameCryptor = addRtpSender(rtpSender, participant.identity!!, publication.sid, publication.track!!.kind.name.lowercase())
        frameCryptor.setObserver { trackId, state ->
            LKLog.i { "Sender::onFrameCryptionStateChanged: $trackId, state:  $state" }
            emitEvent(
                RoomEvent.TrackE2EEStateEvent(
                    room,
                    publication.track!!,
                    publication,
                    participant,
                    state = e2eeStateFromFrameCryptoState(state),
                ),
            )
        }
    }

    fun removePublishedTrack(track: Track, publication: TrackPublication, participant: LocalParticipant, room: Room) {
        val trackId = publication.sid
        val participantId = participant.identity
        val frameCryptor = synchronized(frameCryptorsLock) {
            frameCryptors.remove(trackId to participantId)
        }
        if (frameCryptor != null) {
            frameCryptor.isEnabled = false
            frameCryptor.dispose()
        }
    }

    private fun e2eeStateFromFrameCryptoState(state: FrameCryptionState?): E2EEState {
        return when (state) {
            FrameCryptionState.NEW -> E2EEState.NEW
            FrameCryptionState.OK -> E2EEState.OK
            FrameCryptionState.KEYRATCHETED -> E2EEState.KEY_RATCHETED
            FrameCryptionState.MISSINGKEY -> E2EEState.MISSING_KEY
            FrameCryptionState.ENCRYPTIONFAILED -> E2EEState.ENCRYPTION_FAILED
            FrameCryptionState.DECRYPTIONFAILED -> E2EEState.DECRYPTION_FAILED
            FrameCryptionState.INTERNALERROR -> E2EEState.INTERNAL_ERROR
            else -> E2EEState.INTERNAL_ERROR
        }
    }

    private fun addRtpSender(sender: RtpSender, participantId: Participant.Identity, trackId: String, kind: String): FrameCryptor {
        val frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
            peerConnectionFactory,
            sender,
            participantId.value,
            algorithm,
            keyProvider.rtcKeyProvider,
        )

        synchronized(frameCryptorsLock) {
            frameCryptors[trackId to participantId] = frameCryptor
        }
        frameCryptor.isEnabled = enabled
        val latestKeyIndex = keyProvider.getLatestKeyIndex(participantId.value)
        frameCryptor.keyIndex = latestKeyIndex
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "addRtpSender participantId=${participantId.value} trackId=$trackId kind=$kind " +
                "latestKeyIndex=$latestKeyIndex frameCryptorKeyIndex=${frameCryptor.keyIndex} enabled=$enabled",
        )
        return frameCryptor
    }

    private fun addRtpReceiver(receiver: RtpReceiver, participantId: Participant.Identity, trackId: String, kind: String): FrameCryptor {
        val frameCryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
            peerConnectionFactory,
            receiver,
            participantId.value,
            algorithm,
            keyProvider.rtcKeyProvider,
        )

        synchronized(frameCryptorsLock) {
            frameCryptors[trackId to participantId] = frameCryptor
        }
        frameCryptor.isEnabled = enabled
        val latestKeyIndex = keyProvider.getLatestKeyIndex(participantId.value)
        frameCryptor.keyIndex = latestKeyIndex
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "addRtpReceiver participantId=${participantId.value} trackId=$trackId kind=$kind " +
                "latestKeyIndex=$latestKeyIndex frameCryptorKeyIndex=${frameCryptor.keyIndex} enabled=$enabled",
        )
        return frameCryptor
    }

    /**
     * Enable or disable E2EE
     * @param enabled
     */
    fun enableE2EE(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Ratchet key for local participant
     */
    fun ratchetKey() {
        val newKey = keyProvider.ratchetSharedKey()
        LKLog.d { "ratchetSharedKey: newKey: $newKey" }
    }

    internal fun cleanup() {
        val currentFrameCryptors = synchronized(frameCryptorsLock) {
            frameCryptors.values.toList().also {
                frameCryptors.clear()
            }
        }
        for (frameCryptor in currentFrameCryptors) {
            frameCryptor.dispose()
        }
    }

    internal fun dispose() {
        (keyProvider as? BaseKeyProvider)?.participantKeyIndexListener = null
        dataPacketCryptorManager.dispose()
    }

    fun encrypt(byteArray: ByteArray): EncryptedPacket? {
        val participantId = room?.localParticipant?.identity ?: Participant.Identity("")
        return dataPacketCryptorManager.encrypt(
            participantId,
            keyIndex = keyProvider.getLatestKeyIndex(participantId.value),
            payload = byteArray,
        )
    }

    fun decrypt(participantId: Participant.Identity, packet: EncryptedPacket): ByteArray? {
        return dataPacketCryptorManager.decrypt(
            participantId = participantId,
            packet = packet,
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted keyProvider: KeyProvider,
        ): E2EEManager
    }

    private fun updateParticipantFrameCryptorKeyIndex(participantId: String, keyIndex: Int) {
        val targetFrameCryptors = synchronized(frameCryptorsLock) {
            frameCryptors.entries
                .filter { (frameCryptorId, _) -> frameCryptorId.second.value == participantId }
                .map { (frameCryptorId, frameCryptor) -> frameCryptorId.first to frameCryptor }
        }
        targetFrameCryptors.forEach { (_, frameCryptor) ->
            frameCryptor.keyIndex = keyIndex
        }
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "updateFrameCryptorKeyIndex participantId=$participantId keyIndex=$keyIndex " +
                "updated=${targetFrameCryptors.size} tracks=${targetFrameCryptors.joinToString { it.first }}",
        )
    }
}
