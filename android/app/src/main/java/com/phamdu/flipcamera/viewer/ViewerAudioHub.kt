package com.phamdu.flipcamera.viewer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Live microphone fan-out (PCM 16-bit mono 16 kHz). The microphone is opened only while at least
 * one authenticated viewer is listening and closed with the last one; recording is independent.
 * Each listener has a small bounded queue that drops the oldest chunk, so a slow browser cannot
 * back up the capture thread.
 */
internal class ViewerAudioHub(
    private val onStartCapture: (() -> Unit)? = null,
    private val available: () -> Boolean,
) {
    private val subscribers = ConcurrentHashMap.newKeySet<Channel<ByteArray>>()
    private val lock = Any()
    @Volatile private var running = false
    private var thread: Thread? = null

    val isAvailable: Boolean
        get() = available()

    internal val subscriberCount: Int
        get() = subscribers.size

    /** Returns null when audio is off or not permitted. Caller must call [unsubscribe]. */
    fun subscribe(): Channel<ByteArray>? {
        if (!available()) return null
        onStartCapture?.invoke()
        val channel = Channel<ByteArray>(QUEUE_CHUNKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        synchronized(lock) {
            subscribers.add(channel)
            if (thread == null) {
                running = true
                thread = Thread(::capture, "viewer-audio").also { it.start() }
            }
        }
        return channel
    }

    fun unsubscribe(channel: Channel<ByteArray>) {
        subscribers.remove(channel)
        channel.close()
    }

    fun close() {
        val t: Thread?
        synchronized(lock) {
            running = false
            subscribers.forEach { it.close() }
            subscribers.clear()
            t = thread
            thread = null
        }
        if (t != null && t.isAlive && t !== Thread.currentThread()) {
            t.interrupt()
        }
    }

    @SuppressLint("MissingPermission")
    private fun capture() {
        var record: AudioRecord? = null
        var initialized = false
        try {
            val minBuffer = runCatching {
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            }.getOrElse { 0 }
            if (minBuffer <= 0) {
                runCatching { Log.w(TAG, "AudioRecord minBufferSize không hợp lệ: $minBuffer") }
                return
            }
            val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 4)
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { Log.w(TAG, "AudioRecord không khởi tạo được") }
                return
            }
            initialized = true
            record.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (available()) {
                val shouldStop = synchronized(lock) {
                    subscribers.isEmpty() || !available()
                }
                if (shouldStop) break
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    runCatching { Log.w(TAG, "AudioRecord read trả về mã lỗi: $read") }
                    break
                }
                val chunk = buffer.copyOf(read)
                subscribers.forEach { it.trySend(chunk) }
            }
        } catch (failure: Throwable) {
            runCatching { Log.w(TAG, "Live audio dừng: ${failure.javaClass.simpleName}") }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            synchronized(lock) {
                if (thread === Thread.currentThread()) {
                    thread = null
                    running = false
                    if (initialized && subscribers.isNotEmpty() && available()) {
                        running = true
                        thread = Thread(::capture, "viewer-audio").also { it.start() }
                    } else {
                        subscribers.forEach { it.close() }
                        subscribers.clear()
                    }
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3_200 // 100 ms
        private const val QUEUE_CHUNKS = 10
        private const val TAG = "ViewerAudioHub"
    }
}
