package com.phamdu.flipcamera.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ViewerSpeakerHubTest {

    @Test
    fun rejectsChunksWhenDisabled() {
        val hub = ViewerSpeakerHub(isEnabled = false)
        assertFalse(hub.isEnabled)
        assertFalse(hub.playChunk(ByteArray(100)))
        assertEquals(0, hub.queueSize)
    }

    @Test
    fun rejectsEmptyChunks() {
        val hub = ViewerSpeakerHub(isEnabled = true)
        assertFalse(hub.playChunk(ByteArray(0)))
        assertEquals(0, hub.queueSize)
    }

    @Test
    fun enqueuesAndDispatchesToPlayerWhenEnabled() {
        val latch = CountDownLatch(1)
        val writtenBytes = AtomicInteger(0)
        val mockPlayer = object : ViewerSpeakerHub.AudioTrackPlayer {
            override fun init(): Boolean = true
            override fun play() {}
            override fun write(data: ByteArray): Int {
                writtenBytes.addAndGet(data.size)
                latch.countDown()
                return data.size
            }
            override fun stop() {}
            override fun release() {}
        }

        val hub = ViewerSpeakerHub(isEnabled = true, audioTrackFactory = { mockPlayer })
        val chunk = ByteArray(256) { 1 }
        val accepted = hub.playChunk(chunk)

        assertTrue(accepted)
        assertTrue("Player should receive chunk within 1s", latch.await(1, TimeUnit.SECONDS))
        assertEquals(256, writtenBytes.get())

        hub.close()
    }

    @Test
    fun boundedQueueDropsOldestOnOverflow() {
        val holdingLatch = CountDownLatch(1)
        val mockPlayer = object : ViewerSpeakerHub.AudioTrackPlayer {
            override fun init(): Boolean = true
            override fun play() {}
            override fun write(data: ByteArray): Int {
                holdingLatch.await(100, TimeUnit.MILLISECONDS)
                return data.size
            }
            override fun stop() {}
            override fun release() {}
        }

        val hub = ViewerSpeakerHub(isEnabled = true, audioTrackFactory = { mockPlayer })

        // Fill queue past capacity (MAX_QUEUED_CHUNKS = 16)
        for (i in 0 until 30) {
            hub.playChunk(byteArrayOf(i.toByte()))
        }

        assertTrue("Queue size should never exceed MAX_QUEUED_CHUNKS", hub.queueSize <= ViewerSpeakerHub.MAX_QUEUED_CHUNKS)

        holdingLatch.countDown()
        hub.close()
    }

    @Test
    fun stopPlaybackClearsQueueAndStopsActivePlayer() {
        val stopLatch = CountDownLatch(1)
        val mockPlayer = object : ViewerSpeakerHub.AudioTrackPlayer {
            override fun init(): Boolean = true
            override fun play() {}
            override fun write(data: ByteArray): Int {
                Thread.sleep(50)
                return data.size
            }
            override fun stop() {
                stopLatch.countDown()
            }
            override fun release() {}
        }
        val hub = ViewerSpeakerHub(isEnabled = true, audioTrackFactory = { mockPlayer })
        hub.playChunk(ByteArray(64))
        Thread.sleep(20)
        hub.stopPlayback()
        assertEquals(0, hub.queueSize)
        assertFalse(hub.isPlaying)
        assertTrue("Player.stop() must be invoked immediately", stopLatch.await(1, TimeUnit.SECONDS))
        hub.close()
    }

    @Test
    fun idleTimeoutReleasesPlayerAndReinitializesOnNextChunk() {
        val initCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        val firstChunkLatch = CountDownLatch(1)
        val secondChunkLatch = CountDownLatch(1)

        val mockPlayer = object : ViewerSpeakerHub.AudioTrackPlayer {
            override fun init(): Boolean {
                initCount.incrementAndGet()
                return true
            }
            override fun play() {}
            override fun write(data: ByteArray): Int {
                if (initCount.get() == 1) firstChunkLatch.countDown()
                if (initCount.get() == 2) secondChunkLatch.countDown()
                return data.size
            }
            override fun stop() {}
            override fun release() {
                releaseCount.incrementAndGet()
            }
        }

        // Configure short idle timeout of 60ms
        val hub = ViewerSpeakerHub(isEnabled = true, idleTimeoutMs = 60L, audioTrackFactory = { mockPlayer })

        // Chunk 1
        hub.playChunk(ByteArray(100))
        assertTrue("First chunk processed", firstChunkLatch.await(1, TimeUnit.SECONDS))
        assertEquals(1, initCount.get())

        // Wait for idle timeout (60ms) to trigger player release
        Thread.sleep(150)
        assertTrue("Player should be released after idle timeout", releaseCount.get() >= 1)
        assertFalse("isPlaying must be false when idle", hub.isPlaying)

        // Chunk 2 arrives after idle release
        hub.playChunk(ByteArray(100))
        assertTrue("Second chunk processed after idle", secondChunkLatch.await(1, TimeUnit.SECONDS))
        assertEquals("Player should re-initialize on new chunk", 2, initCount.get())

        hub.close()
    }

    @Test
    fun closePreventsFurtherPlayback() {
        val hub = ViewerSpeakerHub(isEnabled = true)
        hub.close()
        assertFalse(hub.playChunk(ByteArray(64)))
    }
}
