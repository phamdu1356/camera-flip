package com.phamdu.flipcamera.viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerFrameHubTest {
    @Test
    fun slowSubscriberReceivesLatestFrameWithoutBlockingPublisher() = runBlocking {
        val hub = ViewerFrameHub()
        val firstFrameReceived = CompletableDeferred<Unit>()
        val releaseSubscriber = CompletableDeferred<Unit>()
        val observedSequences = mutableListOf<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            hub.subscribeFrames().take(2).collect { frame ->
                observedSequences += frame.sequence
                if (frame.sequence == 1L) {
                    firstFrameReceived.complete(Unit)
                    releaseSubscriber.await()
                }
            }
        }

        hub.publishJpeg(byteArrayOf(1), capturedAtUtc = 1_000L)
        firstFrameReceived.await()
        repeat(100) { index ->
            hub.publishJpeg(byteArrayOf(index.toByte()), capturedAtUtc = 2_000L + index)
        }
        releaseSubscriber.complete(Unit)
        collector.join()

        assertEquals(listOf(1L, 101L), observedSequences)
        assertEquals(0, hub.subscriberCount)
    }
}
