package com.xiaomi.liquidwatermark

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object VideoFrameWatermarker {

    private fun computeSafeVideoDimensions(srcW: Int, srcH: Int, maxLongEdge: Int = 1280): Pair<Int, Int> {
        val w0 = max(16, srcW)
        val h0 = max(16, srcH)
        val longEdge = max(w0, h0)
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge.toFloat() else 1.0f
        val targetW = (((w0 * scale).roundToInt() + 7) / 16 * 16).coerceIn(320, maxLongEdge)
        val targetH = (((h0 * scale).roundToInt() + 7) / 16 * 16).coerceIn(320, maxLongEdge)
        return targetW to targetH
    }

    private fun createConfiguredAvcEncoder(width: Int, height: Int, bitrate: Int, fps: Int): Pair<MediaCodec, Boolean> {
        val candidates = listOf<() -> MediaCodec>(
            { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) },
            { MediaCodec.createByCodecName("c2.android.avc.encoder") },
            { MediaCodec.createByCodecName("OMX.google.h264.encoder") }
        )
        val colorFormats = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar to false,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar to true
        )

        for (factory in candidates) {
            for ((colorFmt, isPlanar) in colorFormats) {
                var codec: MediaCodec? = null
                try {
                    codec = factory()
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    codec.start()
                    return codec to isPlanar
                } catch (t: Throwable) {
                    try { codec?.release() } catch (ignored: Throwable) { }
                }
            }
        }
        throw IllegalStateException("无法初始化 H.264 视频编码器")
    }

    fun processEmbeddedMp4WithWatermark(
        context: Context,
        rawMp4Bytes: ByteArray,
        meta: PhotoMetadata,
        optics: LiquidOpticsConfig,
        logoBitmap: Bitmap? = null
    ): ByteArray {
        val inFile = File.createTempFile("xiaomi_live_in_", ".mp4", context.cacheDir)
        val outFile = File.createTempFile("xiaomi_live_out_", ".mp4", context.cacheDir)
        try {
            inFile.writeBytes(rawMp4Bytes)

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(inFile.absolutePath)

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1500L
            val firstFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return rawMp4Bytes
            val (width, height) = computeSafeVideoDimensions(firstFrame.width, firstFrame.height, maxLongEdge = 1280)
            firstFrame.recycle()

            val extractor = MediaExtractor()
            extractor.setDataSource(inFile.absolutePath)
            var sourceVideoTrack = -1
            var sourceAudioTrack = -1
            var audioFormat: MediaFormat? = null
            var detectedFps = 30

            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && sourceVideoTrack < 0) {
                    sourceVideoTrack = i
                    if (tf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        detectedFps = tf.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
                    }
                } else if (mime.startsWith("audio/") && sourceAudioTrack < 0) {
                    sourceAudioTrack = i
                    audioFormat = tf
                }
            }

            val frameTimestampsUs = ArrayList<Long>()
            if (sourceVideoTrack >= 0) {
                extractor.selectTrack(sourceVideoTrack)
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0) break
                    frameTimestampsUs.add(t)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(sourceVideoTrack)
                frameTimestampsUs.sort()
            }

            if (frameTimestampsUs.isEmpty()) {
                val count = ((durationMs / 1000.0) * detectedFps).roundToInt().coerceIn(15, 90)
                val stepUs = (durationMs * 1000L) / count
                for (i in 0 until count) {
                    frameTimestampsUs.add(i * stepUs)
                }
            }

            val (encoder, isPlanarYuv) = createConfiguredAvcEncoder(width, height, 10_000_000, detectedFps)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerAudioTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val argbPixels = IntArray(width * height)
            val yuvBuffer = ByteArray(width * height * 3 / 2)

            for (ptsUs in frameTimestampsUs) {
                val rawBmp = retriever.getFrameAtTime(ptsUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                val scaledBmp = if (rawBmp.width != width || rawBmp.height != height) {
                    Bitmap.createScaledBitmap(rawBmp, width, height, true).also {
                        if (it !== rawBmp) rawBmp.recycle()
                    }
                } else {
                    rawBmp
                }

                val watermarkedBmp = LiquidGlassRenderer.renderWatermark(scaledBmp, meta, optics, logoBitmap)
                if (watermarkedBmp !== scaledBmp) scaledBmp.recycle()

                watermarkedBmp.getPixels(argbPixels, 0, width, 0, 0, width, height)
                watermarkedBmp.recycle()

                encodeArgbToYuv420(argbPixels, yuvBuffer, width, height, isPlanarYuv)

                val inIndex = encoder.dequeueInputBuffer(15_000L)
                if (inIndex >= 0) {
                    val inBuf = encoder.getInputBuffer(inIndex)
                    if (inBuf != null && inBuf.capacity() >= yuvBuffer.size) {
                        inBuf.clear()
                        inBuf.put(yuvBuffer)
                        encoder.queueInputBuffer(inIndex, 0, yuvBuffer.size, ptsUs, 0)
                    }
                }

                drainEncoder(
                    encoder = encoder,
                    muxer = muxer,
                    bufferInfo = bufferInfo,
                    endOfStream = false,
                    getVideoTrack = { muxerVideoTrack },
                    onFormatChanged = { outFmt ->
                        if (!muxerStarted) {
                            // 严格遵循 Android / OPPO / vivo 动态照片规范 5.2：主视频轨道必须是第一个轨道 (Track 0)！
                            muxerVideoTrack = muxer.addTrack(outFmt)
                            if (audioFormat != null) {
                                try {
                                    muxerAudioTrack = muxer.addTrack(audioFormat)
                                } catch (ignored: Throwable) {
                                    muxerAudioTrack = -1
                                }
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                )
            }

            val eosIndex = encoder.dequeueInputBuffer(15_000L)
            if (eosIndex >= 0) {
                val lastPts = (frameTimestampsUs.lastOrNull() ?: 1_000_000L) + 33_333L
                encoder.queueInputBuffer(eosIndex, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }

            drainEncoder(
                encoder = encoder,
                muxer = muxer,
                bufferInfo = bufferInfo,
                endOfStream = true,
                getVideoTrack = { muxerVideoTrack },
                onFormatChanged = { outFmt ->
                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(outFmt)
                        if (audioFormat != null) {
                            try {
                                muxerAudioTrack = muxer.addTrack(audioFormat)
                            } catch (ignored: Throwable) {
                                muxerAudioTrack = -1
                            }
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                }
            )

            if (muxerStarted && sourceAudioTrack >= 0 && muxerAudioTrack >= 0) {
                extractor.selectTrack(sourceAudioTrack)
                val audioBuf = ByteBuffer.allocate(262144)
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    val sampleSize = extractor.readSampleData(audioBuf, 0)
                    if (sampleSize < 0) break
                    val sampleTime = extractor.sampleTime
                    if (sampleTime >= 0) {
                        audioInfo.offset = 0
                        audioInfo.size = sampleSize
                        audioInfo.presentationTimeUs = sampleTime
                        audioInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        try {
                            muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo)
                        } catch (ignored: Throwable) { }
                    }
                    if (!extractor.advance()) break
                }
            }

            try { extractor.release() } catch (ignored: Throwable) { }
            try { retriever.release() } catch (ignored: Throwable) { }
            try { encoder.stop() } catch (ignored: Throwable) { }
            try { encoder.release() } catch (ignored: Throwable) { }
            if (muxerStarted) {
                try { muxer.stop() } catch (ignored: Throwable) { }
            }
            try { muxer.release() } catch (ignored: Throwable) { }

            val resultBytes = outFile.readBytes()
            return if (resultBytes.size > 1024) resultBytes else rawMp4Bytes
        } catch (t: Throwable) {
            t.printStackTrace()
            return rawMp4Bytes
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    fun generateStaticLiveMp4FromBitmap(context: Context, watermarkedBitmap: Bitmap): ByteArray {
        val outFile = File.createTempFile("static_to_live_", ".mp4", context.cacheDir)
        try {
            val (width, height) = computeSafeVideoDimensions(watermarkedBitmap.width, watermarkedBitmap.height, maxLongEdge = 1280)
            val scaledBmp = if (watermarkedBitmap.width != width || watermarkedBitmap.height != height) {
                Bitmap.createScaledBitmap(watermarkedBitmap, width, height, true)
            } else {
                watermarkedBitmap
            }

            val fps = 30
            val totalFrames = 45 // 1.5 秒标准实况视频流
            val stepUs = 1_000_000L / fps

            val (encoder, isPlanar) = createConfiguredAvcEncoder(width, height, 8_000_000, fps)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerVideoTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val argbPixels = IntArray(width * height)
            scaledBmp.getPixels(argbPixels, 0, width, 0, 0, width, height)
            if (scaledBmp !== watermarkedBitmap) scaledBmp.recycle()

            val yuvBuffer = ByteArray(width * height * 3 / 2)
            encodeArgbToYuv420(argbPixels, yuvBuffer, width, height, isPlanar)

            for (i in 0 until totalFrames) {
                val ptsUs = i * stepUs
                val inIndex = encoder.dequeueInputBuffer(15_000L)
                if (inIndex >= 0) {
                    val inBuf = encoder.getInputBuffer(inIndex)
                    if (inBuf != null && inBuf.capacity() >= yuvBuffer.size) {
                        inBuf.clear()
                        inBuf.put(yuvBuffer)
                        encoder.queueInputBuffer(inIndex, 0, yuvBuffer.size, ptsUs, 0)
                    }
                }
                drainEncoder(
                    encoder = encoder,
                    muxer = muxer,
                    bufferInfo = bufferInfo,
                    endOfStream = false,
                    getVideoTrack = { muxerVideoTrack },
                    onFormatChanged = { outFmt ->
                        if (!muxerStarted) {
                            muxerVideoTrack = muxer.addTrack(outFmt)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                )
            }

            val eosIndex = encoder.dequeueInputBuffer(15_000L)
            if (eosIndex >= 0) {
                encoder.queueInputBuffer(eosIndex, 0, 0, totalFrames * stepUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(
                encoder = encoder,
                muxer = muxer,
                bufferInfo = bufferInfo,
                endOfStream = true,
                getVideoTrack = { muxerVideoTrack },
                onFormatChanged = { outFmt ->
                    if (!muxerStarted) {
                        muxerVideoTrack = muxer.addTrack(outFmt)
                        muxer.start()
                        muxerStarted = true
                    }
                }
            )

            try { encoder.stop() } catch (ignored: Throwable) { }
            try { encoder.release() } catch (ignored: Throwable) { }
            if (muxerStarted) {
                try { muxer.stop() } catch (ignored: Throwable) { }
            }
            try { muxer.release() } catch (ignored: Throwable) { }

            return outFile.readBytes()
        } catch (t: Throwable) {
            t.printStackTrace()
            return ByteArray(0)
        } finally {
            outFile.delete()
        }
    }

    private fun encodeArgbToYuv420(argb: IntArray, yuv: ByteArray, width: Int, height: Int, isPlanar: Boolean) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4

        var index = 0
        for (j in 0 until height) {
            val isEvenRow = (j and 1) == 0
            for (i in 0 until width) {
                val c = argb[index++]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (isEvenRow && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uByte = u.coerceIn(0, 255).toByte()
                    val vByte = v.coerceIn(0, 255).toByte()
                    if (isPlanar) {
                        yuv[uIndex++] = uByte
                        yuv[vIndex++] = vByte
                    } else {
                        yuv[uvIndex++] = uByte
                        yuv[uvIndex++] = vByte
                    }
                }
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        getVideoTrack: () -> Int,
        onFormatChanged: (MediaFormat) -> Unit
    ) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormatChanged(encoder.outputFormat)
            } else if (outIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outIndex)
                if (encodedData != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && getVideoTrack() >= 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(getVideoTrack(), encodedData, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }
}
