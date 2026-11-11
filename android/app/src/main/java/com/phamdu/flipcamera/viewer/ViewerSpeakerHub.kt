package com.phamdu.flipcamera.viewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles incoming PCM 16-bit mono 16 kHz audio chunks from viewers and plays them on
 * the device speaker (Talkback).
 *
 * AudioTrack is managed on a dedicated playback thread: initialized lazily when audio arrives
 * and stopped/released when idle for > [idleTimeoutMs] to conserve battery and avoid speaker hiss.
 *
 * If the queue fills up, older chunks are discarded to keep playback in real-time.
 */
internal class ViewerSpeakerHub(
    @Volatile var isEnabled: Boolean = true,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val audioTrackFactory: () -> AudioTrackPlayer? = { RealAudioTrackPlayer() },
) : Closeable {

    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)
    private val running = AtomicBoolean(true)
    @Volatile private var isPlayingAudio = false
    @Volatile private var activePlayer: AudioTrackPlayer? = null
    private var playbackThread: Thread? = null
    private val lock = Any()

    val isPlaying: Boolean
        get() = isPlayingAudio

    internal val queueSize: Int
        get() = queue.size

    /**
     * Enqueues an audio chunk for speaker playback.
     * Returns true if accepted, false if speaker is disabled, closed, or input is empty.
     */
    fun playChunk(data: ByteArray): Boolean {
        if (!isEnabled || !running.get() || data.isEmpty()) return false
        synchronized(lock) {
            while (queue.remainingCapacity() == 0) {
                queue.poll()
            }
            queue.offer(data)
            ensureThreadLocked()
        }
        return true
    }

    /** Clears pending audio and stops playback immediately. */
    fun stopPlayback() {
        queue.clear()
        isPlayingAudio = false
        val player = activePlayer
        runCatching { player?.stop() }
    }

    private fun ensureThreadLocked() {
        if (playbackThread == null || !playbackThread!!.isAlive) {
            playbackThread = Thread(::playbackLoop, "viewer-speaker").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun playbackLoop() {
        var player: AudioTrackPlayer? = null
        try {
            while (running.get() && isEnabled) {
                val chunk = queue.poll(idleTimeoutMs, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    isPlayingAudio = false
                    player?.stop()
                    player?.release()
                    player = null
                    activePlayer = null
                    synchronized(lock) {
                        if (queue.isEmpty()) {
                            playbackThread = null
                            return
                        }
                    }
                    continue
                }
                if (!isEnabled || !running.get()) {
                    break
                }
                if (player == null) {
                    player = audioTrackFactory()
                    if (player == null || !player.init()) {
                        runCatching { Log.w(TAG, "Không thể khởi tạo AudioTrack cho loa") }
                        player?.release()
                        player = null
                        activePlayer = null
                        break
                    }
                    activePlayer = player
                    player.play()
                }
                isPlayingAudio = true
                player.write(chunk)
            }
        } catch (ignored: InterruptedException) {
            // Thread interrupted on shutdown
        } finally {
            isPlayingAudio = false
            player?.stop()
            player?.release()
            activePlayer = null
            synchronized(lock) {
                if (playbackThread === Thread.currentThread()) {
                    playbackThread = null
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        stopPlayback()
        synchronized(lock) {
            playbackThread?.interrupt()
            playbackThread = null
        }
    }

    interface AudioTrackPlayer {
        fun init(): Boolean
        fun play()
        fun write(data: ByteArray): Int
        fun stop()
        fun release()
    }

    private class RealAudioTrackPlayer : AudioTrackPlayer {
        private var track: AudioTrack? = null

        override fun init(): Boolean = runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = maxOf(minBuf, 16_000)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.state == AudioTrack.STATE_INITIALIZED
        }.getOrDefault(false)

        override fun play() {
            runCatching {
                if (track?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track?.play()
                }
            }
        }

        override fun write(data: ByteArray): Int = runCatching {
            track?.write(data, 0, data.size) ?: 0
        }.getOrDefault(0)

        override fun stop() {
            runCatching {
                if (track?.state == AudioTrack.STATE_INITIALIZED) {
                    if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track?.pause()
                        track?.flush()
                    }
                }
            }
        }

        override fun release() {
            runCatching {
                track?.release()
                track = null
            }
        }
    }

    companion object {
        const val TAG = "ViewerSpeakerHub"
        const val SAMPLE_RATE = 16_000
        const val MAX_QUEUED_CHUNKS = 16
        const val IDLE_TIMEOUT_MS = 2_000L
    }
}
