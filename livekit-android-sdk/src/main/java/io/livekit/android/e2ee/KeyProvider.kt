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
import io.livekit.android.util.LKLog
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm
import livekit.org.webrtc.FrameCryptorKeyProvider

private const val ZYNA_E2EE_DIAGNOSTIC_TAG = "ZynaLiveKitE2EE"

internal class KeyInfo(var participantId: String, var keyIndex: Int, var key: String) {
    override fun toString(): String {
        return "KeyInfo(participantId='$participantId', keyIndex=$keyIndex)"
    }
}

interface KeyProvider {
    fun setSharedKey(key: String, keyIndex: Int? = 0): Boolean

    /**
     * Set raw shared key material without applying string encoding.
     */
    fun setSharedKey(key: ByteArray, keyIndex: Int? = 0): Boolean

    fun ratchetSharedKey(keyIndex: Int? = 0): ByteArray
    fun exportSharedKey(keyIndex: Int? = 0): ByteArray
    fun setKey(key: String, participantId: String?, keyIndex: Int? = 0)

    /**
     * Set raw key material for a participant without applying string encoding.
     */
    fun setKey(key: ByteArray, participantId: String?, keyIndex: Int? = 0)

    fun ratchetKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun exportKey(participantId: String, keyIndex: Int? = 0): ByteArray
    fun setSifTrailer(trailer: ByteArray)
    fun getLatestKeyIndex(participantId: String): Int

    val rtcKeyProvider: FrameCryptorKeyProvider

    var enableSharedKey: Boolean
}

@Suppress("LongParameterList")
class BaseKeyProvider private constructor(
    ratchetSalt: String = defaultRatchetSalt,
    uncryptedMagicBytes: String = defaultMagicBytes,
    ratchetWindowSize: Int = defaultRatchetWindowSize,
    override var enableSharedKey: Boolean = true,
    failureTolerance: Int = defaultFailureTolerance,
    keyRingSize: Int = defaultKeyRingSize,
    discardFrameWhenCryptorNotReady: Boolean = defaultDiscardFrameWhenCryptorNotReady,
    keyDerivationAlgorithm: FrameCryptorKeyDerivationAlgorithm = defaultKeyDerivationAlgorithm,
    rtcKeyProvider: FrameCryptorKeyProvider? = null,
) : KeyProvider {

    constructor(
        ratchetSalt: String = defaultRatchetSalt,
        uncryptedMagicBytes: String = defaultMagicBytes,
        ratchetWindowSize: Int = defaultRatchetWindowSize,
        enableSharedKey: Boolean = true,
        failureTolerance: Int = defaultFailureTolerance,
        keyRingSize: Int = defaultKeyRingSize,
        discardFrameWhenCryptorNotReady: Boolean = defaultDiscardFrameWhenCryptorNotReady,
        keyDerivationAlgorithm: FrameCryptorKeyDerivationAlgorithm = defaultKeyDerivationAlgorithm,
    ) : this(
        ratchetSalt = ratchetSalt,
        uncryptedMagicBytes = uncryptedMagicBytes,
        ratchetWindowSize = ratchetWindowSize,
        enableSharedKey = enableSharedKey,
        failureTolerance = failureTolerance,
        keyRingSize = keyRingSize,
        discardFrameWhenCryptorNotReady = discardFrameWhenCryptorNotReady,
        keyDerivationAlgorithm = keyDerivationAlgorithm,
        rtcKeyProvider = null,
    )

    internal constructor(
        rtcKeyProvider: FrameCryptorKeyProvider,
        enableSharedKey: Boolean = true,
    ) : this(
        ratchetSalt = defaultRatchetSalt,
        uncryptedMagicBytes = defaultMagicBytes,
        ratchetWindowSize = defaultRatchetWindowSize,
        enableSharedKey = enableSharedKey,
        failureTolerance = defaultFailureTolerance,
        keyRingSize = defaultKeyRingSize,
        discardFrameWhenCryptorNotReady = defaultDiscardFrameWhenCryptorNotReady,
        keyDerivationAlgorithm = defaultKeyDerivationAlgorithm,
        rtcKeyProvider = rtcKeyProvider,
    )

    private val latestSetIndex = mutableMapOf<String, Int>()

    @Volatile
    internal var participantKeyIndexListener: ((participantId: String, keyIndex: Int) -> Unit)? = null

    override val rtcKeyProvider: FrameCryptorKeyProvider = rtcKeyProvider
        ?: FrameCryptorFactory.createFrameCryptorKeyProvider(
            enableSharedKey,
            ratchetSalt.toByteArray(),
            ratchetWindowSize,
            uncryptedMagicBytes.toByteArray(),
            failureTolerance,
            keyRingSize,
            discardFrameWhenCryptorNotReady,
            keyDerivationAlgorithm,
        )

    override fun setSharedKey(key: String, keyIndex: Int?): Boolean {
        return setSharedKey(key.toByteArray(Charsets.UTF_8), keyIndex)
    }

    override fun setSharedKey(key: ByteArray, keyIndex: Int?): Boolean {
        val targetKeyIndex = keyIndex ?: 0
        val result = rtcKeyProvider.setSharedKey(targetKeyIndex, key)
        val exportedBytes = runCatching {
            rtcKeyProvider.exportSharedKey(targetKeyIndex).size
        }.getOrNull()
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "setSharedKey keyIndex=$targetKeyIndex bytes=${key.size} exportedBytes=$exportedBytes result=$result",
        )
        return result
    }

    override fun ratchetSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetSharedKey(keyIndex ?: 0)
    }

    override fun exportSharedKey(keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportSharedKey(keyIndex ?: 0)
    }

    /**
     * Set a key for a participant
     * @param key
     * @param participantId
     * @param keyIndex
     */
    override fun setKey(key: String, participantId: String?, keyIndex: Int?) {
        setKey(key.toByteArray(Charsets.UTF_8), participantId, keyIndex)
    }

    /**
     * Set raw key material for a participant without applying string encoding.
     */
    override fun setKey(key: ByteArray, participantId: String?, keyIndex: Int?) {
        if (enableSharedKey) {
            Log.d(
                ZYNA_E2EE_DIAGNOSTIC_TAG,
                "setKey skipped sharedKeyMode participantId=$participantId keyIndex=${keyIndex ?: 0} bytes=${key.size}",
            )
            return
        }

        if (participantId == null) {
            LKLog.d { "Please provide valid participantId for non-SharedKey mode." }
            return
        }

        val targetKeyIndex = keyIndex ?: 0
        latestSetIndex[participantId] = targetKeyIndex

        rtcKeyProvider.setKey(participantId, targetKeyIndex, key)
        val exportedBytes = runCatching {
            rtcKeyProvider.exportKey(participantId, targetKeyIndex).size
        }.getOrNull()
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "setKey participantId=$participantId keyIndex=$targetKeyIndex bytes=${key.size} exportedBytes=$exportedBytes",
        )
        participantKeyIndexListener?.invoke(participantId, targetKeyIndex)
    }

    override fun ratchetKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.ratchetKey(participantId, keyIndex ?: 0)
    }

    override fun exportKey(participantId: String, keyIndex: Int?): ByteArray {
        return rtcKeyProvider.exportKey(participantId, keyIndex ?: 0)
    }

    override fun setSifTrailer(trailer: ByteArray) {
        rtcKeyProvider.setSifTrailer(trailer)
    }

    override fun getLatestKeyIndex(participantId: String): Int {
        val latestKeyIndex = latestSetIndex[participantId] ?: 0
        Log.d(
            ZYNA_E2EE_DIAGNOSTIC_TAG,
            "getLatestKeyIndex participantId=$participantId latestKeyIndex=$latestKeyIndex",
        )
        return latestKeyIndex
    }
}
