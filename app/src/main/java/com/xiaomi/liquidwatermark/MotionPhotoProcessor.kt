package com.xiaomi.liquidwatermark

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BrandType {
    XIAOMI, LEICA, ONEPLUS, OPPO, VIVO, IQOO, CUSTOM
}

data class PhotoMetadata(
    val make: String = "Xiaomi",
    val model: String = "Xiaomi 15 Pro",
    val dateTime: String = "2026.08.23  12:38:06",
    val specs: String = "14mm f/2.2 1/55s ISO100",
    val gps: String = "34°37'40.7\"N 135°30'53.0\"E",
    val detectedBrand: BrandType = BrandType.XIAOMI,
    val isMotionPhoto: Boolean = false,
    val presentationTimestampUs: Long = 500_000L,
    val embeddedMp4Bytes: ByteArray? = null,
    val sourceExifBytes: ByteArray? = null
)

object MotionPhotoProcessor {

    private val FTYP_SIG = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    private val EXIF_TAGS_TO_PRESERVE = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_EXIF_VERSION,
        ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_SOFTWARE
    )

    fun detectBrand(makeStr: String?, modelStr: String?): BrandType {
        val s = "${makeStr.orEmpty()} ${modelStr.orEmpty()}".lowercase()
        val oneplusRegex = Regex("\\b(pj[a-z0-9]{4}|phb110|pjd110|pje110|pkx110|cph2581|cph2609)\\b")
        val oppoRegex = Regex("\\b(ph[a-z0-9]{4}|pk[a-z0-9]{4}|pe[a-z0-9]{4}|pg[a-z0-9]{4})\\b")
        return when {
            s.contains("iqoo") -> BrandType.IQOO
            s.contains("vivo") || s.contains("zeiss") || s.contains("蔡司") || s.contains("x100") || s.contains("x200") || s.contains("x300") || s.contains("x90") -> BrandType.VIVO
            s.contains("oneplus") || s.contains("一加") || oneplusRegex.containsMatchIn(s) -> BrandType.ONEPLUS
            s.contains("oppo") || s.contains("find x") || s.contains("reno") || s.contains("hasselblad") || s.contains("哈苏") || oppoRegex.containsMatchIn(s) -> BrandType.OPPO
            s.contains("leica") || s.contains("徕卡") -> BrandType.LEICA
            else -> BrandType.XIAOMI
        }
    }

    fun parseLocalMediaItem(context: Context, item: LocalMediaItem): Pair<Bitmap, PhotoMetadata> {
        if (item.isVideo) {
            return parseVideoItem(context, item.uri, item.filePath)
        }

        val rawBytes = if (!item.filePath.isNullOrEmpty() && File(item.filePath).canRead()) {
            File(item.filePath).readBytes()
        } else {
            readUriBytesSafely(context, item.uri)
        }

        val pairResult = parseRawImageBytes(context, rawBytes, item.filePath, item.displayName)

        if (!pairResult.second.isMotionPhoto) {
            val pairedMp4 = try {
                if (!item.pairedVideoPath.isNullOrEmpty() && File(item.pairedVideoPath).canRead()) {
                    File(item.pairedVideoPath).readBytes()
                } else if (item.pairedVideoUri != null) {
                    readUriBytesSafely(context, item.pairedVideoUri)
                } else {
                    queryPairedVideoFromMediaStore(context, item.displayName)
                }
            } catch (t: Throwable) {
                null
            }
            if (pairedMp4 != null && pairedMp4.size > 512) {
                return pairResult.first to pairResult.second.copy(
                    isMotionPhoto = true,
                    embeddedMp4Bytes = pairedMp4
                )
            }
        }
        return pairResult
    }

    fun parseUri(context: Context, uri: Uri): Pair<Bitmap, PhotoMetadata> {
        val mime = context.contentResolver.getType(uri) ?: ""
        if (mime.startsWith("video/") || uri.toString().endsWith(".mp4", ignoreCase = true)) {
            return parseVideoItem(context, uri, null)
        }
        val rawBytes = readUriBytesSafely(context, uri)
        val realPath = queryRealFilePath(context, uri)
        val displayName = queryDisplayName(context, uri)
        return parseRawImageBytes(context, rawBytes, realPath, displayName)
    }

    private fun parseVideoItem(context: Context, uri: Uri, filePath: String?): Pair<Bitmap, PhotoMetadata> {
        val mp4Bytes = if (!filePath.isNullOrEmpty() && File(filePath).canRead()) {
            File(filePath).readBytes()
        } else {
            readUriBytesSafely(context, uri)
        }

        val retriever = MediaMetadataRetriever()
        try {
            if (!filePath.isNullOrEmpty() && File(filePath).canRead()) {
                retriever.setDataSource(filePath)
            } else {
                retriever.setDataSource(context, uri)
            }
            val firstFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalArgumentException("无法从视频提取封面帧")
            val model = Build.MODEL?.takeIf { it.isNotEmpty() } ?: "Xiaomi 15 Pro"
            val make = Build.MANUFACTURER?.takeIf { it.isNotEmpty() } ?: "Xiaomi"
            val brand = detectBrand(make, model)
            val nowStr = SimpleDateFormat("yyyy.MM.dd  HH:mm:ss", Locale.getDefault()).format(Date())
            val gps = extractIso6709FromMp4(mp4Bytes) ?: "34°37'40.7\"N 135°30'53.0\"E"

            return firstFrame to PhotoMetadata(
                make = make,
                model = model,
                dateTime = nowStr,
                specs = "23mm f/1.6 1/60s ISO100",
                gps = gps,
                detectedBrand = brand,
                isMotionPhoto = true,
                presentationTimestampUs = 500_000L,
                embeddedMp4Bytes = mp4Bytes
            )
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) { }
        }
    }

    private fun readUriBytesSafely(context: Context, uri: Uri): ByteArray {
        val realPath = queryRealFilePath(context, uri)
        if (!realPath.isNullOrEmpty()) {
            val file = File(realPath)
            if (file.exists() && file.canRead() && file.length() > 0L) {
                try {
                    return file.readBytes()
                } catch (ignored: Throwable) { }
            }
        }

        return try {
            val uriStr = uri.toString()
            val isPickerUri = uriStr.contains("/picker", ignoreCase = true) || uriStr.contains("photopicker", ignoreCase = true)
            val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isPickerUri) {
                try {
                    MediaStore.setRequireOriginal(uri)
                } catch (t: Throwable) {
                    uri
                }
            } else {
                uri
            }
            context.contentResolver.openInputStream(originalUri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } ?: try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } ?: try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            }
        } catch (t: Throwable) {
            null
        } ?: throw IllegalArgumentException("无法读取所选媒体文件")
    }

    private fun queryRealFilePath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 针对 vivo / iQOO (OriginOS) 双文件实况：当通过分享或 Uri 打开单张 JPG 时，自动在 MediaStore.Video 中按相同文件名前缀检索配对 MP4！
     */
    private fun queryPairedVideoFromMediaStore(context: Context, displayName: String?): ByteArray? {
        val stem = displayName?.substringBeforeLast('.')?.takeIf { it.length >= 4 } ?: return null
        return try {
            val sel = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("$stem.%")
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA),
                sel,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (dataIdx >= 0) {
                        val path = c.getString(dataIdx)
                        if (!path.isNullOrEmpty() && File(path).canRead()) {
                            return File(path).readBytes()
                        }
                    }
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val vidUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idIdx))
                    return readUriBytesSafely(context, vidUri)
                }
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun parseRawImageBytes(
        context: Context,
        rawBytes: ByteArray,
        filePath: String?,
        displayName: String? = null
    ): Pair<Bitmap, PhotoMetadata> {
        // 扫描前 256KB，确保跨过 OPPO/vivo 高达 64KB 的 EXIF 缩略图段读取到 XMP APP1 段
        val headerStr = String(rawBytes, 0, minOf(rawBytes.size, 262144), Charsets.ISO_8859_1)

        val mp4StartOffset = findEmbeddedMp4Offset(rawBytes, headerStr)
        var isMotion = mp4StartOffset > 0 && mp4StartOffset < rawBytes.size - 16
        var mp4Bytes = if (isMotion) extractCleanMp4Slice(rawBytes, mp4StartOffset) else null
        val jpegEnd = if (isMotion) mp4StartOffset else rawBytes.size

        // vivo OriginOS / iQOO 同目录双文件 (.jpg + .mp4) 自动配对检索
        if (!isMotion && !filePath.isNullOrEmpty()) {
            try {
                val imgFile = File(filePath)
                val parent = imgFile.parentFile
                val stem = imgFile.nameWithoutExtension
                if (parent != null && stem.isNotEmpty()) {
                    val candidates = listOf(
                        File(parent, "$stem.mp4"),
                        File(parent, "$stem.MP4"),
                        File(parent, "$stem.mov"),
                        File(parent, "$stem.MOV")
                    )
                    for (cand in candidates) {
                        if (cand.exists() && cand.canRead() && cand.length() > 1024L) {
                            mp4Bytes = cand.readBytes()
                            isMotion = true
                            break
                        }
                    }
                }
            } catch (ignored: Throwable) { }
        }

        if (!isMotion) {
            val stemName = displayName ?: filePath?.substringAfterLast('/')
            val mediaStoreMp4 = queryPairedVideoFromMediaStore(context, stemName)
            if (mediaStoreMp4 != null && mediaStoreMp4.size > 1024) {
                mp4Bytes = mediaStoreMp4
                isMotion = true
            }
        }

        val tsRegex = Regex("(?:MicroVideoPresentationTimestampUs|MotionPhotoPresentationTimestampUs|MotionPhotoPrimaryPresentationTimestampUs)[=\"'>\\s]+(\\d+)")
        val rawTs = tsRegex.find(headerStr)?.groupValues?.get(1)?.toLongOrNull() ?: 500_000L
        val timestampUs = if (rawTs > 0L) rawTs else 500_000L

        val exif = ExifInterface(ByteArrayInputStream(rawBytes, 0, jpegEnd))
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.takeIf { it.isNotEmpty() }
            ?: "Xiaomi 15 Pro"
        val brand = detectBrand(make.ifEmpty { Build.MANUFACTURER }, model)

        val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: "2026:08:23 12:38:06"
        val formattedDate = rawDate.replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})\\s+"), "$1.$2.$3  ")

        val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        val focalStr = if (focal35 > 0) "${focal35}mm" else {
            val fl = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 14.0)
            "${fl.roundToInt()}mm"
        }

        val fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 2.2)
        val fNumStr = "f/${String.format("%.1f", fNum).removeSuffix(".0")}"

        val expTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 1.0 / 55.0)
        val expStr = if (expTime > 0 && expTime < 1.0) {
            "1/${(1.0 / expTime).roundToInt()}s"
        } else {
            "${String.format("%.1f", expTime)}s"
        }

        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: "100"
        val specsStr = "$focalStr $fNumStr $expStr ISO$iso"

        val latLong = FloatArray(2)
        val hasValidExifGps = exif.getLatLong(latLong) && (abs(latLong[0]) > 0.0001f || abs(latLong[1]) > 0.0001f)
        val gpsStr = if (hasValidExifGps) {
            "${formatDms(latLong[0], isLat = true)} ${formatDms(latLong[1], isLat = false)}"
        } else {
            val mp4IsoGps = mp4Bytes?.let { extractIso6709FromMp4(it) }
            mp4IsoGps ?: "34°37'40.7\"N 135°30'53.0\"E"
        }

        // 保存原图前 128KB 以复制原始相机 EXIF 属性
        val exifHeaderSlice = rawBytes.copyOfRange(0, minOf(jpegEnd, 131072))

        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, jpegEnd)
        return bitmap to PhotoMetadata(
            make = make.ifEmpty { Build.MANUFACTURER ?: "Xiaomi" },
            model = model,
            dateTime = formattedDate,
            specs = specsStr,
            gps = gpsStr,
            detectedBrand = brand,
            isMotionPhoto = isMotion,
            presentationTimestampUs = timestampUs,
            embeddedMp4Bytes = mp4Bytes,
            sourceExifBytes = exifHeaderSlice
        )
    }

    /**
     * 从 mp4StartOffset 开始按 MP4 Box 树结构精确切片，自动剥离 OPPO 等机型在 MP4 末尾附加的 336 字节私有签名尾块
     */
    private fun extractCleanMp4Slice(rawBytes: ByteArray, mp4StartOffset: Int): ByteArray {
        var pos = mp4StartOffset
        var lastValidBoxEnd = mp4StartOffset
        var hasMoovOrMdat = false
        while (pos + 8 <= rawBytes.size) {
            val boxSize = ((rawBytes[pos].toInt() and 0xFF).toLong() shl 24) or
                    ((rawBytes[pos + 1].toInt() and 0xFF).toLong() shl 16) or
                    ((rawBytes[pos + 2].toInt() and 0xFF).toLong() shl 8) or
                    (rawBytes[pos + 3].toInt() and 0xFF).toLong()
            val typeStr = String(rawBytes, pos + 4, 4, Charsets.ISO_8859_1)
            val isKnownBox = typeStr == "ftyp" || typeStr == "moov" || typeStr == "mdat" ||
                    typeStr == "free" || typeStr == "skip" || typeStr == "wide" ||
                    typeStr == "meta" || typeStr == "uuid" || typeStr == "edts"
            if (!isKnownBox) break

            val actualSize: Long = when {
                boxSize == 1L && pos + 16 <= rawBytes.size -> {
                    ByteBuffer.wrap(rawBytes, pos + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                }
                boxSize == 0L -> (rawBytes.size - pos).toLong()
                else -> boxSize
            }
            if (actualSize < 8L || pos + actualSize > rawBytes.size) break
            if (typeStr == "moov" || typeStr == "mdat") hasMoovOrMdat = true
            pos += actualSize.toInt()
            lastValidBoxEnd = pos
        }
        return if (hasMoovOrMdat && lastValidBoxEnd > mp4StartOffset + 512) {
            rawBytes.copyOfRange(mp4StartOffset, lastValidBoxEnd)
        } else {
            rawBytes.copyOfRange(mp4StartOffset, rawBytes.size)
        }
    }

    fun saveWatermarkedMotionPhoto(
        context: Context,
        watermarkedBitmap: Bitmap,
        metadata: PhotoMetadata,
        optics: LiquidOpticsConfig,
        logoBitmap: Bitmap? = null,
        burnWatermarkIntoVideo: Boolean = true,
        baseName: String = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
    ): Uri? {
        val deviceMfg = Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)
        val isVivoDevice = deviceMfg.contains("vivo") || deviceMfg.contains("iqoo") ||
                metadata.detectedBrand == BrandType.VIVO || metadata.detectedBrand == BrandType.IQOO
        val isOppoDevice = deviceMfg.contains("oppo") || deviceMfg.contains("oneplus") ||
                deviceMfg.contains("realme") || deviceMfg.contains("oplus") ||
                metadata.detectedBrand == BrandType.OPPO || metadata.detectedBrand == BrandType.ONEPLUS

        val timeStampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val stemName = when {
            isVivoDevice -> "IMG_$timeStampStr"
            isOppoDevice -> "IMG${timeStampStr.replace("_", "")}"
            else -> "MVIMG_${timeStampStr}_MP"
        }
        val jpgFileName = "$stemName.jpg"

        val tempJpegFile = File.createTempFile("watermark_cover_", ".jpg", context.cacheDir)
        val baseJpegBytes = try {
            tempJpegFile.outputStream().use { out ->
                watermarkedBitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
            val exif = ExifInterface(tempJpegFile.absolutePath)

            // 1. 完整继承原图相机 EXIF 属性 (拍摄时间、快门、光圈、ISO、焦段、GPS 等)
            if (metadata.sourceExifBytes != null && metadata.sourceExifBytes.size > 16) {
                try {
                    val srcExif = ExifInterface(ByteArrayInputStream(metadata.sourceExifBytes))
                    for (tag in EXIF_TAGS_TO_PRESERVE) {
                        val v = srcExif.getAttribute(tag)
                        if (!v.isNullOrEmpty()) {
                            exif.setAttribute(tag, v)
                        }
                    }
                } catch (ignored: Throwable) { }
            }

            // 2. 补充标准拍摄时间兜底 (防止 OPPO / vivo 相册因无 DateTimeOriginal 降级为普通图)
            val nowExifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrEmpty()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowExifDate)
            }
            if (exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED).isNullOrEmpty()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowExifDate)
            }
            if (exif.getAttribute(ExifInterface.TAG_DATETIME).isNullOrEmpty()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, nowExifDate)
            }

            // 3. 根据当前运行手机系统与品牌设置相册校验所需的 EXIF Make 与 OPPO UserComment="Oplus_8388608"
            val effectiveMake = when {
                deviceMfg.contains("vivo") || deviceMfg.contains("iqoo") -> "vivo"
                deviceMfg.contains("oneplus") -> "OnePlus"
                deviceMfg.contains("oppo") || deviceMfg.contains("realme") || deviceMfg.contains("oplus") -> "OPPO"
                metadata.detectedBrand == BrandType.OPPO -> "OPPO"
                metadata.detectedBrand == BrandType.ONEPLUS -> "OnePlus"
                metadata.detectedBrand == BrandType.VIVO || metadata.detectedBrand == BrandType.IQOO -> "vivo"
                else -> metadata.make.ifEmpty { "Xiaomi" }
            }
            exif.setAttribute(ExifInterface.TAG_MAKE, effectiveMake)
            exif.setAttribute(ExifInterface.TAG_MODEL, metadata.model)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Oplus_8388608")
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, watermarkedBitmap.width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, watermarkedBitmap.height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, watermarkedBitmap.width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, watermarkedBitmap.height.toString())
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
            tempJpegFile.readBytes()
        } catch (t: Throwable) {
            val baos = ByteArrayOutputStream()
            watermarkedBitmap.compress(Bitmap.CompressFormat.JPEG, 96, baos)
            baos.toByteArray()
        } finally {
            tempJpegFile.delete()
        }

        val rawMp4 = metadata.embeddedMp4Bytes
        val processedMp4: ByteArray = if (rawMp4 != null && rawMp4.size > 512) {
            if (burnWatermarkIntoVideo) {
                VideoFrameWatermarker.processEmbeddedMp4WithWatermark(context, rawMp4, metadata, optics, logoBitmap)
            } else {
                rawMp4
            }
        } else {
            VideoFrameWatermarker.generateStaticLiveMp4FromBitmap(context, watermarkedBitmap)
        }

        val safeTimestampUs = if (metadata.presentationTimestampUs in 1L..3_000_000L) {
            metadata.presentationTimestampUs
        } else {
            500_000L
        }
        val finalFileBytes = if (processedMp4.size > 256) {
            injectUniversalMotionXmpAndAppendMp4(baseJpegBytes, processedMp4, safeTimestampUs)
        } else {
            baseJpegBytes
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, jpgFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val outUri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            null
        } ?: try {
            val fallbackValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, jpgFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fallbackValues)
        } catch (t: Throwable) {
            null
        } ?: throw IllegalStateException("无法创建相册输出文件")

        resolver.openOutputStream(outUri)?.use { out ->
            out.write(finalFileBytes)
            out.flush()
        }
        try {
            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(outUri, doneValues, null, null)
        } catch (ignored: Throwable) { }

        // 针对 vivo / iQOO (OriginOS) 同步写出同名配对 .mp4 并触发双文件媒体库扫描
        if (isVivoDevice && processedMp4.size > 256) {
            savePairedMp4ForOriginOs(context, stemName, processedMp4)
        }

        try {
            val dcimCameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            val jpgFile = File(dcimCameraDir, jpgFileName)
            val mp4File = File(dcimCameraDir, "$stemName.mp4")
            if (isVivoDevice && mp4File.exists()) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(jpgFile.absolutePath, mp4File.absolutePath),
                    arrayOf("image/jpeg", "video/mp4"),
                    null
                )
            } else {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(jpgFile.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
            }
        } catch (ignored: Throwable) { }

        return outUri
    }

    private fun savePairedMp4ForOriginOs(context: Context, stemName: String, mp4Bytes: ByteArray) {
        try {
            val resolver = context.contentResolver
            val vValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$stemName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val vUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vValues) ?: return
            resolver.openOutputStream(vUri)?.use { out ->
                out.write(mp4Bytes)
                out.flush()
            }
            vValues.clear()
            vValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(vUri, vValues, null, null)
        } catch (ignored: Throwable) { }
    }

    private fun findInsertionOffsetAfterExifApp1(jpegBytes: ByteArray): Int {
        if (jpegBytes.size < 4 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) return 2
        var pos = 2
        while (pos + 4 < jpegBytes.size) {
            if (jpegBytes[pos] != 0xFF.toByte()) break
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
            if (segLen < 2 || pos + 2 + segLen > jpegBytes.size) break
            if (marker == 0xE0 || (marker == 0xE1 && segLen >= 8 &&
                    jpegBytes[pos + 4] == 'E'.code.toByte() &&
                    jpegBytes[pos + 5] == 'x'.code.toByte() &&
                    jpegBytes[pos + 6] == 'i'.code.toByte() &&
                    jpegBytes[pos + 7] == 'f'.code.toByte())
            ) {
                pos += 2 + segLen
            } else {
                break
            }
        }
        return pos
    }

    private fun buildOppoMpfApp2Segment(primaryJpegLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(74)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.put(0xFF.toByte())
        buf.put(0xE2.toByte())
        buf.putShort(72.toShort())
        buf.put('M'.code.toByte())
        buf.put('P'.code.toByte())
        buf.put('F'.code.toByte())
        buf.put(0.toByte())

        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x4949.toShort())
        buf.putShort(0x002A.toShort())
        buf.putInt(8)
        buf.putShort(3.toShort())

        buf.putShort(0xB000.toShort())
        buf.putShort(7.toShort())
        buf.putInt(4)
        buf.put('0'.code.toByte())
        buf.put('1'.code.toByte())
        buf.put('0'.code.toByte())
        buf.put('0'.code.toByte())

        buf.putShort(0xB001.toShort())
        buf.putShort(4.toShort())
        buf.putInt(1)
        buf.putInt(1)

        buf.putShort(0xB002.toShort())
        buf.putShort(7.toShort())
        buf.putInt(16)
        buf.putInt(50)

        buf.putInt(0)
        buf.putInt(0x00030000)
        buf.putInt(primaryJpegLen)
        buf.putInt(0)
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        return buf.array()
    }

    /**
     * 注入严格通过 Java SAXParser / Adobe XMPMetaFactory 命名空间校验的跨平台实况 XMP 元数据：
     * - 修正了此前同时绑定 xmlns:vcamera 与 xmlns:vivo 到同一 URI 导致的 XML duplicate attribute 致命异常
     * - 完整包含 Google GCamera/Container、小米 MicroVideo、OPPO OpCamera (OLivePhotoVersion=2) 与 vivo VCamera (VMotionPhotoVersion=1)
     */
    private fun injectUniversalMotionXmpAndAppendMp4(
        jpegBytes: ByteArray,
        mp4Bytes: ByteArray,
        timestampUs: Long
    ): ByteArray {
        val videoLen = mp4Bytes.size
        val xmpXml = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" " +
            "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
            "xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\" " +
            "xmlns:VCamera=\"http://ns.vivo.com/photos/1.0/camera/\" " +
            "xmlns:MiCamera=\"http://ns.xiaomi.com/photos/1.0/camera/\" " +
            "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
            "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" " +
            "GCamera:MicroVideo=\"1\" " +
            "GCamera:MicroVideoVersion=\"1\" " +
            "GCamera:MicroVideoOffset=\"$videoLen\" " +
            "GCamera:MicroVideoPresentationTimestampUs=\"$timestampUs\" " +
            "GCamera:MotionPhoto=\"1\" " +
            "GCamera:MotionPhotoVersion=\"1\" " +
            "GCamera:MotionPhotoPresentationTimestampUs=\"$timestampUs\" " +
            "OpCamera:MotionPhotoOwner=\"oplus\" " +
            "OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"$timestampUs\" " +
            "OpCamera:OLivePhotoVersion=\"2\" " +
            "OpCamera:MotionPhotoFeatureFlag=\"1\" " +
            "OpCamera:VideoLength=\"$videoLen\" " +
            "VCamera:VMotionPhotoVersion=\"1\" " +
            "VCamera:VMotionPhotoSource=\"1\" " +
            "VCamera:LivePhoto=\"1\" " +
            "VCamera:VideoOffset=\"$videoLen\">" +
            "<Container:Directory>" +
            "<rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>" +
            "</rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLen\" Item:Padding=\"0\"/>" +
            "</rdf:li>" +
            "</rdf:Seq>" +
            "</Container:Directory>" +
            "</rdf:Description>" +
            "</rdf:RDF>" +
            "</x:xmpmeta>"

        val nsBytes = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val xmlBytes = xmpXml.toByteArray(Charsets.UTF_8)
        val xmpSegLen = nsBytes.size + xmlBytes.size + 2

        val insertOffset = findInsertionOffsetAfterExifApp1(jpegBytes)
        val primaryJpegTotalLen = jpegBytes.size + (2 + xmpSegLen) + 74
        val mpfApp2Bytes = buildOppoMpfApp2Segment(primaryJpegTotalLen)

        val out = ByteArrayOutputStream(primaryJpegTotalLen + mp4Bytes.size)
        out.write(jpegBytes, 0, insertOffset)
        out.write(0xFF)
        out.write(0xE1)
        out.write((xmpSegLen shr 8) and 0xFF)
        out.write(xmpSegLen and 0xFF)
        out.write(nsBytes)
        out.write(xmlBytes)
        out.write(mpfApp2Bytes)
        out.write(jpegBytes, insertOffset, jpegBytes.size - insertOffset)
        out.write(mp4Bytes)
        return out.toByteArray()
    }

    /**
     * 定位内嵌 MP4 视频流起点：
     * - 兼容小米 MicroVideoOffset、Google Container Item:Length、OPPO VideoLength (含 OPPO 尾部 336 字节签名偏移容差！) 与 vivo VideoOffset
     */
    private fun findEmbeddedMp4Offset(bytes: ByteArray, headerStr: String): Int {
        val candidateOffsets = ArrayList<Int>()
        val offsetRegex = Regex("(?:MicroVideoOffset|VideoLength|VideoOffset)[=\"'>\\s]+(\\d+)")
        for (m in offsetRegex.findAll(headerStr)) {
            val tailLen = m.groupValues[1].toIntOrNull() ?: continue
            if (tailLen in 512 until bytes.size) {
                candidateOffsets.add(bytes.size - tailLen)
            }
        }
        val itemLenRegex = Regex("Item:Length=\"(\\d+)\"")
        for (m in itemLenRegex.findAll(headerStr)) {
            val tailLen = m.groupValues[1].toIntOrNull() ?: continue
            if (tailLen in 512 until bytes.size) {
                candidateOffsets.add(bytes.size - tailLen)
            }
        }

        // 先检查精确位置及前后 1024 字节窗口（兼容 OPPO VideoLength 与文件尾相差 336 字节的情况）
        for (cand in candidateOffsets) {
            if (isFtypAt(bytes, cand)) return cand
            val winStart = max(256, cand - 1024)
            val winEnd = min(bytes.size - 16, cand + 1024)
            for (p in winStart..winEnd) {
                if (isFtypAt(bytes, p)) return p
            }
        }

        // 兜底全局扫描最后一个合法的 MP4 ftyp 盒子
        var lastValidFtyp = -1
        for (i in 256 until bytes.size - 12) {
            if (isFtypAt(bytes, i)) {
                val boxSize = ((bytes[i].toInt() and 0xFF) shl 24) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                        (bytes[i + 3].toInt() and 0xFF)
                if (boxSize in 16..128) {
                    lastValidFtyp = i
                }
            }
        }
        return lastValidFtyp
    }

    private fun isFtypAt(bytes: ByteArray, pos: Int): Boolean {
        if (pos < 0 || pos + 12 >= bytes.size) return false
        return bytes[pos + 4] == FTYP_SIG[0] &&
               bytes[pos + 5] == FTYP_SIG[1] &&
               bytes[pos + 6] == FTYP_SIG[2] &&
               bytes[pos + 7] == FTYP_SIG[3]
    }

    private fun extractIso6709FromMp4(mp4Bytes: ByteArray): String? {
        val scanLen = minOf(mp4Bytes.size, 262144)
        val str = String(mp4Bytes, 0, scanLen, Charsets.ISO_8859_1)
        val match = Regex("([+-]\\d{2}\\.\\d{3,8})([+-]\\d{3}\\.\\d{3,8})").find(str) ?: return null
        val lat = match.groupValues[1].toFloatOrNull() ?: return null
        val lon = match.groupValues[2].toFloatOrNull() ?: return null
        if (abs(lat) < 0.0001f && abs(lon) < 0.0001f) return null
        return "${formatDms(lat, isLat = true)} ${formatDms(lon, isLat = false)}"
    }

    private fun formatDms(coord: Float, isLat: Boolean): String {
        val dir = if (isLat) (if (coord >= 0) "N" else "S") else (if (coord >= 0) "E" else "W")
        val absVal = abs(coord.toDouble())
        var deg = absVal.toInt()
        val minFull = (absVal - deg) * 60.0
        var min = minFull.toInt()
        var sec = (minFull - min) * 60.0
        if (sec >= 59.95) { sec = 0.0; min += 1 }
        if (min >= 60) { min = 0; deg += 1 }
        return String.format("%d°%d'%.1f\"%s", deg, min, sec, dir)
    }

    fun decodeCustomLogoBitmap(context: Context, uri: Uri): Bitmap? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        } ?: try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
            }
        } catch (t: Throwable) {
            null
        } ?: return null

        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return Bitmap.createScaledBitmap(rawBmp, minOf(rawBmp.width, 512), minOf(rawBmp.height, 512), true)
    }
}
