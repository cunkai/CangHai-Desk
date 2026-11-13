package com.alkaidlab.sunshine.service

import android.media.MediaCodecList
import com.alkaidlab.sunshine.data.EncoderCapability

/** Advertises device encoder capabilities, not features guaranteed by the bundled native engine. */
object EncoderCapabilities {

    /**
     * MediaCodecInfo.isHardwareAccelerated() 是 API 29 才有的方法，
     * 在 Android 9 及以下直接调用会 NoSuchMethodError（属于 Error，catch RuntimeException 兜不住），
     * 所以低版本按硬件厂商前缀猜：OMX.google / c2.android 走软编，其余都算硬编。
     */
    private fun hardwareOf(info: android.media.MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return try {
                info.isHardwareAccelerated
            } catch (_: Throwable) {
                legacyHardwareGuess(info.name)
            }
        }
        return legacyHardwareGuess(info.name)
    }

    private fun legacyHardwareGuess(name: String?): Boolean {
        val n = name ?: return false
        return !(n.startsWith("OMX.google.") || n.startsWith("c2.android."))
    }

    val encoders: List<EncoderCapability> by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { info ->
                info.supportedTypes.filter { it == "video/avc" || it == "video/hevc" }.mapNotNull { mime ->
                    try {
                        val capabilities = info.getCapabilitiesForType(mime)
                        val video = capabilities.videoCapabilities ?: return@mapNotNull null
                        EncoderCapability(info.name, mime, hardwareOf(info),
                            video.widthAlignment, video.heightAlignment,
                            video.supportedWidths.upper, video.supportedHeights.upper,
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && capabilities.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    } catch (_: RuntimeException) { null }
                }
            }
        } catch (_: RuntimeException) { emptyList() }
    }
}
