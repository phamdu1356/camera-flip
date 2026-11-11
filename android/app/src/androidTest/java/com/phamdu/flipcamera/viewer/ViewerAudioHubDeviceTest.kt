package com.phamdu.flipcamera.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Chạy trên Flip3 thật: cần RECORD_AUDIO đã cấp cho app. Không ghi file, không đụng dữ liệu. */
@RunWith(AndroidJUnit4::class)
class ViewerAudioHubDeviceTest {
    @Test
    fun unavailableHubNeverOpensMicrophone() {
        val hub = ViewerAudioHub { false }
        assertNull(hub.subscribe())
        assertEquals(0, hub.subscriberCount)
    }

    @Test
    fun listenerReceivesLivePcmAndLastLeaveClosesMic() = runBlocking {
        val hub = ViewerAudioHub { true }
        val channel = assertNotNull_(hub.subscribe())
        var bytes = 0
        var chunks = 0
        var peak = 0
        val start = System.currentTimeMillis()
        withTimeoutOrNull(3_000) {
            while (chunks < 20) {
                val chunk = channel.receive()
                chunks += 1
                bytes += chunk.size
                for (i in 0 until chunk.size - 1 step 2) {
                    val s = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(s))
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        android.util.Log.i("ViewerAudioHubTest", "chunks=$chunks bytes=$bytes peak=$peak elapsedMs=$elapsed")
        hub.unsubscribe(channel)
        assertTrue("chunks=$chunks", chunks >= 15)
        assertEquals(0, hub.subscriberCount)
        // Thread thoát sau khi người nghe cuối rời.
        Thread.sleep(500)
        val again = assertNotNull_(hub.subscribe())
        hub.unsubscribe(again)
    }

    private fun <T : Any> assertNotNull_(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
