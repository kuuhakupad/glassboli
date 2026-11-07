package com.xiaomi.liquidwatermark

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

enum class GalleryCategory(val title: String) {
    ALL_PHOTOS("全部照片"),
    VIDEOS("视频"),
    LIVE_PHOTOS("实况照片")
}

data class LocalMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val filePath: String?,
    val dateAdded: Long,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val isLivePhoto: Boolean,
    val durationMs: Long = 0L,
    val pairedVideoPath: String? = null,
    val pairedVideoUri: Uri? = null
)

object LocalGalleryScanner {

    /**
     * 直接扫描手机 MediaStore 媒体库（不经过系统 Photo Picker 中转，100% 保留实况照片尾部 MP4 视频流与同目录配对视频）
     */
    fun scanAllMedia(context: Context): List<LocalMediaItem> {
        val resolver = context.contentResolver
        val videosByBaseName = HashMap<String, Pair<String?, Uri>>()
        val videoItems = ArrayList<LocalMediaItem>()

        // 1. 先扫描所有视频 (MediaStore.Video.Media)
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        )
        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 400) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "VID_$id.mp4"
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val date = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val dur = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    val baseKey = buildParentAndBaseNameKey(path, name)
                    if (baseKey.isNotEmpty()) {
                        videosByBaseName[baseKey] = path to contentUri
                    }

                    videoItems.add(
                        LocalMediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            filePath = path,
                            dateAdded = date,
                            sizeBytes = size,
                            isVideo = true,
                            isLivePhoto = false,
                            durationMs = dur
                        )
                    )
                    count++
                }
            }
        } catch (ignored: Throwable) { }

        // 2. 扫描所有图片 (MediaStore.Images.Media)，并精准识别哪些是「实况照片」(小米 / OPPO / 一加 / vivo 单文件或双文件配对实况)
        val imageItems = ArrayList<LocalMediaItem>()
        val imgProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imgProjection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                var count = 0
                while (cursor.moveToNext() && count < 600) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_$id.jpg"
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val date = cursor.getLong(dateCol)
                    val size = cursor.getLong(sizeCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    // 检查是否有同名配对视频 (vivo OriginOS / iQOO 常见实况存储方式)
                    val baseKey = buildParentAndBaseNameKey(path, name)
                    var pairedVidPath: String? = null
                    var pairedVidUri: Uri? = null
                    if (baseKey.isNotEmpty() && videosByBaseName.containsKey(baseKey)) {
                        val pair = videosByBaseName[baseKey]!!
                        pairedVidPath = pair.first
                        pairedVidUri = pair.second
                    } else if (path != null) {
                        val f = File(path)
                        val stem = f.nameWithoutExtension
                        val parent = f.parentFile
                        if (parent != null) {
                            val candidateMp4 = File(parent, "$stem.mp4")
                            val candidateMov = File(parent, "$stem.mov")
                            if (candidateMp4.exists() && candidateMp4.length() > 1024) {
                                pairedVidPath = candidateMp4.absolutePath
                            } else if (candidateMov.exists() && candidateMov.length() > 1024) {
                                pairedVidPath = candidateMov.absolutePath
                            }
                        }
                    }

                    val isFastLive = pairedVidPath != null ||
                        pairedVidUri != null ||
                        name.startsWith("MVIMG", ignoreCase = true) ||
                        name.contains("_MP", ignoreCase = true) ||
                        name.contains("LIVE", ignoreCase = true) ||
                        isFileMotionPhotoFast(path, size)

                    imageItems.add(
                        LocalMediaItem(
                            id = id,
                            uri = contentUri,
                            displayName = name,
                            filePath = path,
                            dateAdded = date,
                            sizeBytes = size,
                            isVideo = false,
                            isLivePhoto = isFastLive,
                            pairedVideoPath = pairedVidPath,
                            pairedVideoUri = pairedVidUri
                        )
                    )
                    count++
                }
            }
        } catch (ignored: Throwable) { }

        return (imageItems + videoItems).sortedByDescending { it.dateAdded }
    }

    private fun buildParentAndBaseNameKey(filePath: String?, displayName: String): String {
        val stem = displayName.substringBeforeLast('.')
        if (stem.isEmpty()) return ""
        val parentDir = filePath?.substringBeforeLast('/', "") ?: ""
        return "$parentDir/$stem".lowercase()
    }

    /**
     * 毫秒级快速判断本地 JPEG 文件是否为内嵌视频流的实况照片（无需读取整张大图，仅检查头部 64KB XMP 标记或尾部 MP4 ftyp 签名）
     */
    private fun isFileMotionPhotoFast(filePath: String?, fileSize: Long): Boolean {
        if (filePath.isNullOrEmpty() || fileSize < 65536L) return false
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // 1. 读取头部 64KB 检查 XMP 实况标记 (Xiaomi MicroVideo / Google MotionPhoto / OPPO OLivePhoto / vivo LivePhoto)
                val headLen = minOf(file.length().toInt(), 262144)
                val headBuf = ByteArray(headLen)
                raf.readFully(headBuf)
                val headStr = String(headBuf, Charsets.ISO_8859_1)
                if (headStr.contains("MicroVideo") ||
                    headStr.contains("MotionPhoto") ||
                    headStr.contains("OLivePhoto") ||
                    headStr.contains("OpCamera") ||
                    headStr.contains("VCamera") || headStr.contains("VMotionPhoto") || headStr.contains("vcamera") ||
                    headStr.contains("LivePhoto")
                ) {
                    return@use true
                }

                // 2. 如果头部 XMP 较大，快速扫描文件后半部是否含有 MP4 `ftyp` 盒子签名
                if (file.length() > 300_000L) {
                    val tailReadSize = minOf((file.length() - 50_000L).toInt(), 3_500_000)
                    val startPos = maxOf(50_000L, file.length() - tailReadSize)
                    raf.seek(startPos)
                    val tailBuf = ByteArray(minOf(tailReadSize, 524288))
                    val readBytes = raf.read(tailBuf)
                    for (i in 0 until readBytes - 8) {
                        if (tailBuf[i + 4] == 'f'.code.toByte() &&
                            tailBuf[i + 5] == 't'.code.toByte() &&
                            tailBuf[i + 6] == 'y'.code.toByte() &&
                            tailBuf[i + 7] == 'p'.code.toByte()
                        ) {
                            return@use true
                        }
                    }
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun loadThumbnail(context: Context, item: LocalMediaItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
            } else {
                if (item.isVideo) {
                    val retriever = MediaMetadataRetriever()
                    if (!item.filePath.isNullOrEmpty()) {
                        retriever.setDataSource(item.filePath)
                    } else {
                        retriever.setDataSource(context, item.uri)
                    }
                    val frame = retriever.getFrameAtTime(0L)
                    retriever.release()
                    frame
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver,
                        item.id,
                        MediaStore.Images.Thumbnails.MINI_KIND,
                        null
                    )
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}

@Composable
fun InAppGalleryPickerDialog(
    isDark: Boolean,
    strings: UiStrings,
    onDismiss: () -> Unit,
    onSelectMedia: (LocalMediaItem) -> Unit,
    onFallbackSystemFilePicker: () -> Unit
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL_PHOTOS) }
    var allMedia by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        val scanned = withContext(Dispatchers.IO) {
            LocalGalleryScanner.scanAllMedia(context)
        }
        allMedia = scanned
        isLoading = false
    }

    val filteredList = remember(allMedia, selectedCategory) {
        when (selectedCategory) {
            GalleryCategory.ALL_PHOTOS -> allMedia.filter { !it.isVideo }
            GalleryCategory.VIDEOS -> allMedia.filter { it.isVideo }
            GalleryCategory.LIVE_PHOTOS -> allMedia.filter { it.isLivePhoto }
        }
    }

    val bgColor = if (isDark) Color(0xFF0B0D13) else Color(0xFFF2F4F8)
    val cardColor = if (isDark) Color(0xFF181B24) else Color(0xFFFFFFFF)
    val subCardColor = if (isDark) Color(0xFF222632) else Color(0xFFEDF0F6)
    val textMain = if (isDark) Color.White else Color(0xFF14171F)
    val textSub = if (isDark) Color(0xFF8A93A6) else Color(0xFF646E82)
    val miuixBlue = Color(0xFF3482FF)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部栏：返回按钮 + 标题 + 备用文件管理入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(subCardColor)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("←", color = textMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(
                            text = strings.galleryTitle,
                            color = textMain,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.gallerySubtitle,
                            color = textSub,
                            fontSize = 11.sp
                        )
                    }
                }

                TextButton(onClick = onFallbackSystemFilePicker) {
                    Text(strings.systemFileBrowserBtn, color = miuixBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 三大分类切换栏：全部照片 | 视频 | 实况照片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardColor, RoundedCornerShape(16.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val counts = mapOf(
                    GalleryCategory.ALL_PHOTOS to allMedia.count { !it.isVideo },
                    GalleryCategory.VIDEOS to allMedia.count { it.isVideo },
                    GalleryCategory.LIVE_PHOTOS to allMedia.count { it.isLivePhoto }
                )

                for (cat in GalleryCategory.values()) {
                    val active = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) miuixBlue else Color.Transparent)
                            .clickable { selectedCategory = cat },
                        contentAlignment = Alignment.Center
                    ) {
                        val catTitle = when (cat) {
                            GalleryCategory.ALL_PHOTOS -> strings.tabAllPhotos
                            GalleryCategory.VIDEOS -> strings.tabVideos
                            GalleryCategory.LIVE_PHOTOS -> strings.tabLivePhotos
                        }
                        Text(
                            text = "$catTitle (${counts[cat] ?: 0})",
                            color = if (active) Color.White else textSub,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 媒体网格列表
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = miuixBlue)
                        Text(strings.scanningMediaText, color = textSub, fontSize = 13.sp)
                    }
                }
            } else if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cardColor, RoundedCornerShape(20.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = when (selectedCategory) {
                                GalleryCategory.LIVE_PHOTOS -> strings.emptyLivePhotosText
                                GalleryCategory.VIDEOS -> strings.emptyVideosText
                                GalleryCategory.ALL_PHOTOS -> strings.emptyAllPhotosText
                            },
                            color = textMain,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = strings.emptyMediaHintText,
                            color = textSub,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        count = filteredList.size,
                        key = { idx -> "${filteredList[idx].isVideo}_${filteredList[idx].id}" }
                    ) { idx ->
                        val item = filteredList[idx]
                        MediaGridCell(
                            item = item,
                            subCardColor = subCardColor,
                            onClick = { onSelectMedia(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaGridCell(
    item: LocalMediaItem,
    subCardColor: Color,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var thumbBmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            val loaded = LocalGalleryScanner.loadThumbnail(context, item)
            withContext(Dispatchers.Main) {
                thumbBmp = loaded
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(subCardColor)
            .clickable { onClick() }
    ) {
        if (thumbBmp != null) {
            Image(
                bitmap = thumbBmp!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 左上角：仅保留极简实况同心圆图标（无文字、无背景遮罩框、底部无文件名遮罩）
        if (item.isLivePhoto) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .size(18.dp)
            ) {
                val r = size.minDimension / 2f
                // 柔和暗影衬底线条，确保在纯白照片上也清晰可见，但无任何背景框遮罩
                drawCircle(
                    color = Color(0x55000000),
                    radius = r * 0.86f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4.dp.toPx())
                )
                // 外圈实况环
                drawCircle(
                    color = Color.White,
                    radius = r * 0.86f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                )
                // 中心实况圆点
                drawCircle(
                    color = Color.White,
                    radius = r * 0.36f
                )
            }
        } else if (item.isVideo) {
            Text(
                text = "▶",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
            )
        }
    }
}
