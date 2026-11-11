package com.phamdu.flipcamera

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import androidx.camera.video.Recorder
import androidx.camera.video.internal.encoder.AudioEncoderConfig
import androidx.camera.video.internal.encoder.Encoder
import androidx.camera.video.internal.encoder.EncoderConfig
import androidx.camera.video.internal.encoder.EncoderFactory
import androidx.camera.video.internal.encoder.EncoderImpl
import androidx.camera.video.internal.encoder.VideoEncoderConfig
import com.phamdu.flipcamera.data.MIME_HEVC
import com.phamdu.flipcamera.data.TuningPlan
import java.util.concurrent.Executor

/**
 * Tối ưu cấu hình encoder video cho CameraX 1.4.1.
 *
 * `Recorder.Builder.setVideoEncoderFactory` là package-private trong CameraX 1.4.1, nên được gọi
 * bằng reflection. Mọi lỗi (reflection, dựng config, khởi tạo encoder) đều được log và rơi về
 * hành vi mặc định của CameraX; việc ghi hình không bị ảnh hưởng. Chỉ áp dụng cho video.
 */
@SuppressLint("RestrictedApi")
object EncoderTuning {
    private const val TAG = "EncoderTuning"
    private const val SETTER_NAME = "setVideoEncoderFactory"
    private const val AUDIO_SETTER_NAME = "setAudioEncoderFactory"
    const val DEFAULT_AUDIO_BITRATE = 48_000 // 48 kbps AAC mono

    /** Kiểm tra máy có encoder HEVC (video/hevc) hay không. Lỗi được coi là không có. */
    fun hasHevcEncoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME_HEVC, ignoreCase = true) }
        }
    } catch (error: Exception) {
        Log.w(TAG, "Không đọc được danh sách codec; coi như không có HEVC.", error)
        false
    }

    /**
     * Gắn factory tùy chỉnh vào [builder]. Trả về true khi đã gắn; false nếu không gắn được
     * (khi đó CameraX dùng encoder mặc định).
     */
    fun install(builder: Recorder.Builder, plan: TuningPlan): Boolean {
        if (plan.isDefault) return false
        return try {
            val setter = Recorder.Builder::class.java.getDeclaredMethod(
                SETTER_NAME,
                EncoderFactory::class.java,
            )
            setter.isAccessible = true
            setter.invoke(builder, TunedVideoEncoderFactory(plan))
            Log.i(TAG, "Đã áp dụng tối ưu encoder: mime=${plan.mime}, fps=${plan.frameRate}, iFrame=${plan.iFrameSeconds}s")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Không áp dụng được tối ưu encoder; dùng cấu hình mặc định.", error)
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "Không áp dụng được tối ưu encoder; dùng cấu hình mặc định.", error)
            false
        }
    }

    /** Gắn factory tùy chỉnh audio (AAC 48 kbps mono) vào [builder]. */
    fun installAudioTuning(builder: Recorder.Builder, bitrate: Int = DEFAULT_AUDIO_BITRATE): Boolean {
        return try {
            val setter = Recorder.Builder::class.java.getDeclaredMethod(
                AUDIO_SETTER_NAME,
                EncoderFactory::class.java,
            )
            setter.isAccessible = true
            setter.invoke(builder, TunedAudioEncoderFactory(bitrate))
            Log.i(TAG, "Đã áp dụng tối ưu audio encoder: AAC mono $bitrate bps")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Không áp dụng được tối ưu audio encoder; dùng cấu hình mặc định.", error)
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "Không áp dụng được tối ưu audio encoder; dùng cấu hình mặc định.", error)
            false
        }
    }
}

/**
 * Factory video: dựng lại config với fps/I-frame/mime theo [plan], rồi tạo [EncoderImpl] như CameraX.
 * Nếu config tối ưu không dựng được hoặc encoder từ chối, dùng đúng config gốc của CameraX.
 */
@SuppressLint("RestrictedApi")
internal class TunedVideoEncoderFactory(private val plan: TuningPlan) : EncoderFactory {
    override fun createEncoder(executor: Executor, config: EncoderConfig): Encoder {
        val tuned: VideoEncoderConfig? = try {
            tunedConfigOrNull(config)
        } catch (error: Exception) {
            Log.w(TAG, "Không dựng được config encoder tối ưu; dùng cấu hình gốc.", error)
            null
        }
        if (tuned != null) {
            try {
                return EncoderImpl(executor, tuned)
            } catch (error: Exception) {
                Log.w(TAG, "Encoder tối ưu bị từ chối; dùng cấu hình gốc.", error)
            }
        }
        return EncoderImpl(executor, config)
    }

    private fun tunedConfigOrNull(config: EncoderConfig): VideoEncoderConfig? {
        if (config !is VideoEncoderConfig) return null
        val hevc = plan.mime == MIME_HEVC
        return VideoEncoderConfig.builder()
            .setMimeType(plan.mime)
            .setProfile(if (hevc) HEVC_PROFILE_MAIN else config.profile)
            .setInputTimebase(config.inputTimebase)
            .setResolution(config.resolution)
            .setColorFormat(config.colorFormat)
            .setDataSpace(config.dataSpace)
            .setFrameRate(plan.frameRate ?: config.frameRate)
            .setIFrameInterval(plan.iFrameSeconds ?: config.iFrameInterval)
            .setBitrate(config.bitrate)
            .build()
    }

    private companion object {
        const val TAG = "EncoderTuning"
        /** Tương đương MediaCodecInfo.CodecProfileLevel.HEVCProfileMain. */
        val HEVC_PROFILE_MAIN: Int = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
    }
}

/**
 * Factory audio: dựng lại config với kênh mono và bitrate 48 kbps (vừa phải, tiết kiệm dung lượng,
 * âm thanh rõ ràng cho camera giám sát). Nếu config không dựng được hoặc encoder từ chối, dùng
 * đúng config gốc của CameraX.
 */
@SuppressLint("RestrictedApi")
internal class TunedAudioEncoderFactory(
    private val bitrate: Int = EncoderTuning.DEFAULT_AUDIO_BITRATE,
) : EncoderFactory {
    override fun createEncoder(executor: Executor, config: EncoderConfig): Encoder {
        val tuned: AudioEncoderConfig? = try {
            tunedConfigOrNull(config)
        } catch (error: Exception) {
            Log.w("EncoderTuning", "Không dựng được config audio encoder tối ưu; dùng cấu hình gốc.", error)
            null
        }
        if (tuned != null) {
            try {
                return EncoderImpl(executor, tuned)
            } catch (error: Exception) {
                Log.w("EncoderTuning", "Audio encoder tối ưu bị từ chối; dùng cấu hình gốc.", error)
            }
        }
        return EncoderImpl(executor, config)
    }

    private fun tunedConfigOrNull(config: EncoderConfig): AudioEncoderConfig? {
        if (config !is AudioEncoderConfig) return null
        return AudioEncoderConfig.builder()
            .setMimeType(config.mimeType)
            .setProfile(config.profile)
            .setInputTimebase(config.inputTimebase)
            .setBitrate(bitrate)
            .setSampleRate(config.sampleRate)
            .setChannelCount(1) // Mono: tối ưu dung lượng
            .build()
    }
}
