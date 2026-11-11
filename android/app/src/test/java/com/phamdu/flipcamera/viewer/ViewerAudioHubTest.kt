package com.phamdu.flipcamera.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAudioHubTest {
    @Test
    fun returnsNullWhenAudioUnavailable() {
        val hub = ViewerAudioHub { false }
        assertFalse(hub.isAvailable)
        assertNull(hub.subscribe())
        assertEquals(0, hub.subscriberCount)
    }

    @Test
    fun returnsChannelWhenAudioAvailable() {
        val hub = ViewerAudioHub { true }
        assertTrue(hub.isAvailable)
        val channel = hub.subscribe()
        assertNotNull(channel)
        assertEquals(1, hub.subscriberCount)

        hub.unsubscribe(channel!!)
        assertEquals(0, hub.subscriberCount)
        assertTrue(channel.isClosedForSend)
    }

    @Test
    fun invokesOnStartCaptureCallbackWhenSubscribing() {
        var onStartCaptureCalled = false
        val hub = ViewerAudioHub(onStartCapture = { onStartCaptureCalled = true }) { true }
        val channel = hub.subscribe()
        assertNotNull(channel)
        assertTrue(onStartCaptureCalled)
        hub.unsubscribe(channel!!)
    }

    @Test
    fun doesNotInvokeOnStartCaptureWhenUnavailable() {
        var onStartCaptureCalled = false
        val hub = ViewerAudioHub(onStartCapture = { onStartCaptureCalled = true }) { false }
        val channel = hub.subscribe()
        assertNull(channel)
        assertFalse(onStartCaptureCalled)
    }

    @Test
    fun multipleSubscribersManageCountCorrectly() {
        val hub = ViewerAudioHub { true }
        val ch1 = hub.subscribe()
        val ch2 = hub.subscribe()
        assertNotNull(ch1)
        assertNotNull(ch2)
        assertEquals(2, hub.subscriberCount)

        hub.unsubscribe(ch1!!)
        assertEquals(1, hub.subscriberCount)
        assertTrue(ch1.isClosedForSend)

        hub.unsubscribe(ch2!!)
        assertEquals(0, hub.subscriberCount)
        assertTrue(ch2.isClosedForSend)
    }

    @Test
    fun closeCleansUpAllSubscribers() {
        val hub = ViewerAudioHub { true }
        val ch1 = hub.subscribe()
        val ch2 = hub.subscribe()
        assertEquals(2, hub.subscriberCount)

        hub.close()
        assertEquals(0, hub.subscriberCount)
        assertTrue(ch1!!.isClosedForSend)
        assertTrue(ch2!!.isClosedForSend)
    }
}
