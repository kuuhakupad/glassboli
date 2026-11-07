package com.xiaomi.liquidwatermark

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class WatermarkStylePreset {
    LIQUID_GLASS, GAUSSIAN_BLUR
}

class MainActivity : ComponentActivity() {

    private var sharedUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        extractUriFromIntent(intent)?.let { sharedUriState.value = it }

        setContent {
            val systemDark = isSystemInDarkTheme()
            var manualDarkOverride by remember { mutableStateOf<Boolean?>(null) }
            val isDark = manualDarkOverride ?: systemDark

            var appLang by remember { mutableStateOf(AppLocales.defaultLanguage()) }
            val strings = remember(appLang) { AppLocales.of(appLang) }

            val view = LocalView.current
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }

            val colorScheme = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF3482FF),
                    secondary = Color(0xFFFF6900),
                    background = Color(0xFF0B0D13),
                    surface = Color(0xFF181B24)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF3482FF),
                    secondary = Color(0xFFFF6900),
                    background = Color(0xFFF2F4F8),
                    surface = Color(0xFFFFFFFF)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                LiquidWatermarkApp(
                    isDark = isDark,
                    onToggleTheme = { manualDarkOverride = !isDark },
                    strings = strings,
                    onToggleLanguage = {
                        val nextLang = if (appLang == AppLanguage.ZH) AppLanguage.JA else AppLanguage.ZH
                        appLang = nextLang
                        Toast.makeText(this@MainActivity, AppLocales.of(nextLang).switchedLangToast, Toast.LENGTH_SHORT).show()
                    },
                    incomingSharedUri = sharedUriState.value,
                    onSharedUriConsumed = { sharedUriState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUriFromIntent(intent)?.let { sharedUriState.value = it }
    }

    private fun extractUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (streamUri != null) return streamUri
                if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    return intent.clipData!!.getItemAt(0).uri
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!list.isNullOrEmpty()) return list[0]
                if (intent.clipData != null && intent.clipData!!.itemCount > 0) {
                    return intent.clipData!!.getItemAt(0).uri
                }
            }
            Intent.ACTION_VIEW -> {
                if (intent.data != null) return intent.data
            }
        }
        return null
    }
}

fun openAuthorDouyinProfile(context: Context, toastMsg: String) {
    val douyinUrl = "https://v.douyin.com/hiobexmbb48/"
    val shareCommand = "8- 长按复制此条消息，打开抖音搜索，查看TA的更多作品。 https://v.douyin.com/hiobexmbb48/"
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("douyin_author", shareCommand))
    } catch (ignored: Throwable) { }

    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(douyinUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (ignored: Throwable) { }
}

fun openRomPermissionSettings(context: Context) {
    val pkg = context.packageName
    val manufacturer = Build.MANUFACTURER.lowercase()

    val romIntents = mutableListOf<Intent>()
    when {
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
            romIntents.add(Intent().apply {
                component = ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity")
                putExtra("packagename", pkg)
            })
            romIntents.add(Intent("permission.intent.action.softPermissionDetail").apply {
                putExtra("packagename", pkg)
            })
        }
        manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") -> {
            romIntents.add(Intent().apply {
                component = ComponentName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
                putExtra("packageName", pkg)
            })
            romIntents.add(Intent().apply {
                component = ComponentName("com.oplus.safecenter", "com.oplus.privacypermissionsentry.PermissionTopActivity")
                putExtra("packageName", pkg)
            })
        }
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
            romIntents.add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", pkg)
            })
        }
    }

    romIntents.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", pkg, null)
    })

    for (intent in romIntents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (ignored: Throwable) { }
    }
}

/**
 * 动态加载 sample_showcase 示例图：通过 getIdentifier 反射查找，即使仓库中 sample_showcase.jpg 缺失也绝不触发编译期 Unresolved reference 报错，并自动生成高质感渐变占位图！
 */
fun loadSampleShowcaseBitmap(context: Context): Bitmap {
    val resId = context.resources.getIdentifier("sample_showcase", "drawable", context.packageName)
    if (resId != 0) {
        BitmapFactory.decodeResource(context.resources, resId)?.let { return it }
    }
    val w = 960
    val h = 1280
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF1E3A8A.toInt(), 0xFF2563EB.toInt(), 0xFF60A5FA.toInt(), 0xFFF59E0B.toInt()),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return bmp
}

/**
 * 极简无国旗的语言切换小图标 (地球经纬线小图标)
 */
@Composable
fun LanguageGlobeIcon(tint: Color, modifier: Modifier = Modifier.size(15.dp)) {
    Canvas(modifier = modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val strokeW = 1.5.dp.toPx()
        drawCircle(color = tint, radius = r * 0.9f, center = c, style = Stroke(width = strokeW))
        drawOval(
            color = tint,
            topLeft = Offset(c.x - r * 0.45f, c.y - r * 0.9f),
            size = androidx.compose.ui.geometry.Size(r * 0.9f, r * 1.8f),
            style = Stroke(width = strokeW * 0.85f)
        )
        drawLine(
            color = tint,
            start = Offset(c.x - r * 0.88f, c.y),
            end = Offset(c.x + r * 0.88f, c.y),
            strokeWidth = strokeW * 0.85f
        )
    }
}

@Composable
fun LiquidWatermarkApp(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    strings: UiStrings,
    onToggleLanguage: () -> Unit,
    incomingSharedUri: Uri?,
    onSharedUriConsumed: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var metadata by remember { mutableStateOf(PhotoMetadata()) }
    var hasMediaPermission by remember { mutableStateOf(false) }

    val xiaomiLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_xiaomi) }
    val leicaLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_leica) }
    val oneplusLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_oneplus) }
    val oppoLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_oppo) }
    val vivoLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_vivo) }
    val iqooLogoBmp = remember { BitmapFactory.decodeResource(context.resources, R.drawable.logo_iqoo) }
    var customLogoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var selectedBrand by remember { mutableStateOf(BrandType.XIAOMI) }

    var activeStylePreset by remember { mutableStateOf(WatermarkStylePreset.LIQUID_GLASS) }

    fun currentLogoBitmap(brand: BrandType): Bitmap? {
        return when (brand) {
            BrandType.XIAOMI -> xiaomiLogoBmp
            BrandType.LEICA -> leicaLogoBmp
            BrandType.ONEPLUS -> oneplusLogoBmp
            BrandType.OPPO -> oppoLogoBmp
            BrandType.VIVO -> vivoLogoBmp
            BrandType.IQOO -> iqooLogoBmp
            BrandType.CUSTOM -> customLogoBmp ?: xiaomiLogoBmp
        }
    }

    var optics by remember {
        mutableStateOf(
            LiquidOpticsConfig(
                refractionPx = 20f,
                blurPx = 2f,
                specularIntensity = 0.20f,
                widthRatio = 0.98f,
                heightRatio = 0.176f,
                bottomOffsetRatio = 0.010f,
                contentShiftXRatio = 0.0f,
                contentSpreadRatio = 0.0f
            )
        )
    }
    var burnVideoWatermark by remember { mutableStateOf(true) }
    var isExporting by remember { mutableStateOf(false) }
    var showInAppGallery by remember { mutableStateOf(false) }

    fun buildRequiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) {
                list.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            }
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 29) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            list.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        return list.toTypedArray()
    }

    fun checkMediaGranted(): Boolean {
        val mediaOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return mediaOk
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMediaPermission = checkMediaGranted()
    }

    fun updatePreview(src: Bitmap?, meta: PhotoMetadata, cfg: LiquidOpticsConfig, brand: BrandType) {
        if (src == null) return
        val logoBmp = currentLogoBitmap(brand)
        scope.launch(Dispatchers.Default) {
            runCatching {
                LiquidGlassRenderer.renderWatermark(src, meta, cfg, logoBmp)
            }.onSuccess { rendered ->
                withContext(Dispatchers.Main) {
                    previewBitmap = rendered
                }
            }
        }
    }

    fun loadPhotoFromLocalMediaItem(item: LocalMediaItem) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                MotionPhotoProcessor.parseLocalMediaItem(context, item)
            }.onSuccess { (bmp, meta) ->
                withContext(Dispatchers.Main) {
                    sourceBitmap = bmp
                    metadata = meta
                    if (selectedBrand != BrandType.CUSTOM) {
                        selectedBrand = meta.detectedBrand
                    }
                    updatePreview(bmp, meta, optics, selectedBrand)
                    val typeTag = when {
                        item.isVideo -> strings.loadedFromVideoToast
                        meta.isMotionPhoto -> strings.loadedLivePhotoToast
                        else -> strings.loadedStaticPhotoToast
                    }
                    Toast.makeText(context, "$typeTag: ${item.displayName}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${strings.readErrorPrefix}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadPhotoFromUri(uri: Uri, fromShare: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                MotionPhotoProcessor.parseUri(context, uri)
            }.onSuccess { (bmp, meta) ->
                withContext(Dispatchers.Main) {
                    sourceBitmap = bmp
                    metadata = meta
                    if (selectedBrand != BrandType.CUSTOM) {
                        selectedBrand = meta.detectedBrand
                    }
                    updatePreview(bmp, meta, optics, selectedBrand)
                    val prefix = if (fromShare) strings.loadedSharedPhotoToast else strings.loadedPhotoToast
                    Toast.makeText(context, "$prefix (${meta.model})", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${strings.readErrorPrefix}: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        hasMediaPermission = checkMediaGranted()
        permLauncher.launch(buildRequiredPermissions())

        if (incomingSharedUri == null) {
            withContext(Dispatchers.IO) {
                val showcaseBmp = loadSampleShowcaseBitmap(context)
                val rendered = LiquidGlassRenderer.renderWatermark(showcaseBmp, metadata, optics, xiaomiLogoBmp)
                withContext(Dispatchers.Main) {
                    if (sourceBitmap == null) {
                        sourceBitmap = showcaseBmp
                        previewBitmap = rendered
                    }
                }
            }
        }
    }

    LaunchedEffect(incomingSharedUri) {
        if (incomingSharedUri != null) {
            loadPhotoFromUri(incomingSharedUri, fromShare = true)
            onSharedUriConsumed()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadPhotoFromUri(uri, fromShare = false)
        }
    }

    val customLogoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bmp = MotionPhotoProcessor.decodeCustomLogoBitmap(context, uri)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        customLogoBmp = bmp
                        selectedBrand = BrandType.CUSTOM
                        updatePreview(sourceBitmap, metadata, optics, BrandType.CUSTOM)
                        Toast.makeText(context, strings.customLogoLoadedToast, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, strings.customLogoErrorToast, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val pageBg = if (isDark) Color(0xFF0B0D13) else Color(0xFFF2F4F8)
    val cardColor = if (isDark) Color(0xFF181B24) else Color(0xFFFFFFFF)
    val subCardColor = if (isDark) Color(0xFF222632) else Color(0xFFEDF0F6)
    val textMain = if (isDark) Color.White else Color(0xFF14171F)
    val textSub = if (isDark) Color(0xFF8A93A6) else Color(0xFF646E82)
    val miuixBlue = Color(0xFF3482FF)
    val miuixOrange = Color(0xFFFF6900)

    if (showInAppGallery) {
        InAppGalleryPickerDialog(
            isDark = isDark,
            strings = strings,
            onDismiss = { showInAppGallery = false },
            onSelectMedia = { selectedItem ->
                showInAppGallery = false
                loadPhotoFromLocalMediaItem(selectedItem)
            },
            onFallbackSystemFilePicker = {
                showInAppGallery = false
                pickerLauncher.launch("*/*")
            }
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 顶栏：App图标 + 标题 + 深浅色胶囊 + 语言切换小图标(无国旗) + 右侧圆形绿勾/红叉权限图标
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher),
                            contentDescription = "App Icon",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { openAuthorDouyinProfile(context, strings.jumpDouyinToast) }
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = strings.appTitle,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textMain,
                                    modifier = Modifier.clickable { openAuthorDouyinProfile(context, strings.jumpDouyinToast) }
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(miuixBlue.copy(alpha = 0.16f))
                                        .clickable { onToggleTheme() }
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isDark) strings.darkThemeBtn else strings.lightThemeBtn,
                                        color = miuixBlue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(miuixOrange.copy(alpha = 0.16f))
                                        .clickable { onToggleLanguage() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    LanguageGlobeIcon(tint = miuixOrange)
                                }
                            }
                            Text(
                                text = strings.appSubtitle,
                                color = textSub,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (hasMediaPermission) Color(0xFF10B981) else Color(0xFFEF4444))
                            .clickable {
                                permLauncher.launch(buildRequiredPermissions())
                                if (!checkMediaGranted()) {
                                    openRomPermissionSettings(context)
                                } else {
                                    hasMediaPermission = true
                                    Toast.makeText(context, strings.permGrantedToast, Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hasMediaPermission) "✓" else "✕",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 实时预览画布卡片
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 340.dp, max = 520.dp)
                        .background(cardColor, RoundedCornerShape(22.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Showcase Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                }

                // 主操作双胶囊按钮
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (!checkMediaGranted()) {
                                permLauncher.launch(buildRequiredPermissions())
                            }
                            showInAppGallery = true
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = miuixBlue)
                    ) {
                        Text(strings.selectPhotoBtn, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val bmp = previewBitmap ?: return@Button
                            isExporting = true
                            Toast.makeText(context, strings.exportingStartToast, Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching {
                                    MotionPhotoProcessor.saveWatermarkedMotionPhoto(
                                        context = context,
                                        watermarkedBitmap = bmp,
                                        metadata = metadata,
                                        optics = optics,
                                        logoBitmap = currentLogoBitmap(selectedBrand),
                                        burnWatermarkIntoVideo = burnVideoWatermark
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    isExporting = false
                                    val savedUri = result.getOrNull()
                                    if (savedUri != null) {
                                        Toast.makeText(context, strings.exportSuccessToast, Toast.LENGTH_LONG).show()
                                    } else {
                                        val ex = result.exceptionOrNull()
                                        val errMsg = ex?.message ?: ex?.javaClass?.simpleName ?: "Error"
                                        Toast.makeText(context, "${strings.exportErrorPrefix}: $errMsg", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = previewBitmap != null && !isExporting,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = miuixOrange)
                    ) {
                        Text(
                            if (isExporting) strings.exportingBtn else strings.exportLiveBtn,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // 分组卡片 1：6 大品牌 Logo 预设 + 自定义上传
                Text(strings.brandSectionTitle, color = textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(22.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val row1Items = listOf(
                        Triple(BrandType.XIAOMI, strings.brandXiaomi, R.drawable.logo_xiaomi),
                        Triple(BrandType.LEICA, strings.brandLeica, R.drawable.logo_leica),
                        Triple(BrandType.ONEPLUS, strings.brandOnePlus, R.drawable.logo_oneplus),
                        Triple(BrandType.OPPO, strings.brandOppo, R.drawable.logo_oppo)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((bType, label, resId) in row1Items) {
                            val isSelected = selectedBrand == bType
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(subCardColor)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) miuixBlue else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        selectedBrand = bType
                                        updatePreview(sourceBitmap, metadata, optics, bType)
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            if (bType == BrandType.ONEPLUS || bType == BrandType.OPPO) Color(0xFF181B24) else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = resId),
                                        contentDescription = label,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                Text(label, color = textMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    val row2Items = listOf(
                        Triple(BrandType.VIVO, strings.brandVivo, R.drawable.logo_vivo),
                        Triple(BrandType.IQOO, strings.brandIqoo, R.drawable.logo_iqoo)
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((bType, label, resId) in row2Items) {
                            val isSelected = selectedBrand == bType
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(subCardColor)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) miuixBlue else Color.Transparent,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        selectedBrand = bType
                                        updatePreview(sourceBitmap, metadata, optics, bType)
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = label,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(label, color = textMain, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        val isCustom = selectedBrand == BrandType.CUSTOM
                        Row(
                            modifier = Modifier
                                .weight(2f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(subCardColor)
                                .border(
                                    width = if (isCustom) 2.dp else 1.dp,
                                    color = if (isCustom) miuixBlue else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable { customLogoPicker.launch("image/*") }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(miuixBlue.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+", color = miuixBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strings.uploadCustomLogoBtn, color = textMain, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }

                // 分组卡片 2：水印材质风格预设 (液态玻璃 vs 高斯模糊) + 光学与位置微调
                Text(strings.styleSectionTitle, color = textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isLiquid = activeStylePreset == WatermarkStylePreset.LIQUID_GLASS
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isLiquid) miuixBlue else subCardColor)
                                .clickable {
                                    activeStylePreset = WatermarkStylePreset.LIQUID_GLASS
                                    optics = optics.copy(refractionPx = 20f, blurPx = 2f, specularIntensity = 0.20f)
                                    updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                                    Toast.makeText(context, strings.presetLiquidToast, Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.presetLiquidGlass,
                                color = if (isLiquid) Color.White else textMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val isGaussian = activeStylePreset == WatermarkStylePreset.GAUSSIAN_BLUR
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isGaussian) miuixBlue else subCardColor)
                                .clickable {
                                    activeStylePreset = WatermarkStylePreset.GAUSSIAN_BLUR
                                    optics = optics.copy(refractionPx = 0f, blurPx = 12f, specularIntensity = 0.15f)
                                    updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                                    Toast.makeText(context, strings.presetGaussianToast, Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = strings.presetGaussianBlur,
                                color = if (isGaussian) Color.White else textMain,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(strings.keepVideoWatermarkLabel, color = textMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = burnVideoWatermark,
                            onCheckedChange = { burnVideoWatermark = it }
                        )
                    }

                    Text("${strings.refractionLabelPrefix}: ${optics.refractionPx.roundToInt()} px", color = textSub, fontSize = 12.sp)
                    Slider(
                        value = optics.refractionPx,
                        onValueChange = {
                            optics = optics.copy(refractionPx = it)
                            updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                        },
                        valueRange = 0f..65f
                    )

                    Text("${strings.blurLabelPrefix}: ${optics.blurPx.roundToInt()} px  |  ${strings.specularLabelPrefix}: ${(optics.specularIntensity * 100).roundToInt()}%", color = textSub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = optics.blurPx,
                            onValueChange = {
                                optics = optics.copy(blurPx = it)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = 0f..16f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = optics.specularIntensity * 100f,
                            onValueChange = {
                                optics = optics.copy(specularIntensity = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = 10f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("${strings.heightLabelPrefix}: ${String.format("%.1f", optics.heightRatio * 100)}%  |  ${strings.widthLabelPrefix}: ${(optics.widthRatio * 100).roundToInt()}%  |  ${strings.bottomOffsetLabelPrefix}: ${String.format("%.1f", optics.bottomOffsetRatio * 100)}%", color = miuixOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = optics.heightRatio * 100f,
                            onValueChange = {
                                optics = optics.copy(heightRatio = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = 11f..26f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = optics.widthRatio * 100f,
                            onValueChange = {
                                optics = optics.copy(widthRatio = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = 70f..98f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = optics.bottomOffsetRatio * 100f,
                            onValueChange = {
                                optics = optics.copy(bottomOffsetRatio = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = 0.5f..15f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "${strings.shiftXLabelPrefix}: ${String.format("%+.1f", optics.contentShiftXRatio * 100)}%  |  ${strings.spreadLabelPrefix}: ${String.format("%+.1f", optics.contentSpreadRatio * 100)}%",
                        color = miuixBlue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = optics.contentShiftXRatio * 100f,
                            onValueChange = {
                                optics = optics.copy(contentShiftXRatio = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = -15f..15f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = optics.contentSpreadRatio * 100f,
                            onValueChange = {
                                optics = optics.copy(contentSpreadRatio = it / 100f)
                                updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                            },
                            valueRange = -10f..15f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 分组卡片 3：拍摄参数识别与自定义
                Text(strings.exifSectionTitle, color = textSub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardColor, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = metadata.model,
                        onValueChange = {
                            val autoBrand = MotionPhotoProcessor.detectBrand("", it)
                            metadata = metadata.copy(model = it, detectedBrand = autoBrand)
                            if (selectedBrand != BrandType.CUSTOM) {
                                selectedBrand = autoBrand
                            }
                            updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                        },
                        label = { Text(strings.modelInputLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = metadata.dateTime,
                        onValueChange = {
                            metadata = metadata.copy(dateTime = it)
                            updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                        },
                        label = { Text(strings.dateTimeInputLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = metadata.specs,
                        onValueChange = {
                            metadata = metadata.copy(specs = it)
                            updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                        },
                        label = { Text(strings.specsInputLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = metadata.gps,
                        onValueChange = {
                            metadata = metadata.copy(gps = it)
                            updatePreview(sourceBitmap, metadata, optics, selectedBrand)
                        },
                        label = { Text(strings.gpsInputLabel) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.footerSignature,
                        color = miuixBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { openAuthorDouyinProfile(context, strings.jumpDouyinToast) }
                    )
                }
            }
        }
    }
}
