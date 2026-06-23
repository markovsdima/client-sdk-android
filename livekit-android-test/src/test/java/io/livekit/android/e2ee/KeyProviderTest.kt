/*
 * Copyright 2026 LiveKit, Inc.
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

import livekit.org.webrtc.FrameCryptorKeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class KeyProviderTest {

    @Test
    fun setRawSharedKeyExportsSameBytes() {
        val rtcKeyProvider = mock<FrameCryptorKeyProvider>()
        val keyProvider = BaseKeyProvider(rtcKeyProvider = rtcKeyProvider)
        val keyData = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        val keyIndex = 254
        whenever(rtcKeyProvider.setSharedKey(keyIndex, keyData)).thenReturn(true)
        whenever(rtcKeyProvider.exportSharedKey(keyIndex)).thenReturn(keyData)

        assertTrue(keyProvider.setSharedKey(keyData, keyIndex))

        verify(rtcKeyProvider).setSharedKey(keyIndex, keyData)
        assertArrayEquals(keyData, keyProvider.exportSharedKey(keyIndex))
    }

    @Test
    fun setRawPerParticipantKeyExportsSameBytes() {
        val rtcKeyProvider = mock<FrameCryptorKeyProvider>()
        val keyProvider = BaseKeyProvider(rtcKeyProvider = rtcKeyProvider, enableSharedKey = false)
        val participantId = "@alice:example.org|DEVICEID"
        val keyData = byteArrayOf(15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        val keyIndex = 254
        whenever(rtcKeyProvider.exportKey(participantId, keyIndex)).thenReturn(keyData)

        keyProvider.setKey(keyData, participantId, keyIndex)

        verify(rtcKeyProvider).setKey(participantId, keyIndex, keyData)
        assertArrayEquals(keyData, keyProvider.exportKey(participantId, keyIndex))
        assertEquals(keyIndex, keyProvider.getLatestKeyIndex(participantId))
    }

    @Test
    fun stringSharedKeyExportsUtf8Bytes() {
        val rtcKeyProvider = mock<FrameCryptorKeyProvider>()
        val keyProvider = BaseKeyProvider(rtcKeyProvider = rtcKeyProvider)
        val key = "shared-key"
        val keyCaptor = argumentCaptor<ByteArray>()

        keyProvider.setSharedKey(key)

        verify(rtcKeyProvider).setSharedKey(eq(0), keyCaptor.capture())
        assertArrayEquals(key.toByteArray(Charsets.UTF_8), keyCaptor.firstValue)
    }
}
