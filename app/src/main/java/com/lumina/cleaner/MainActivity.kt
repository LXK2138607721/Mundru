package com.lumina.cleaner

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lumina.cleaner.core.database.LocalTrashEntity
import com.lumina.cleaner.core.database.MundruDatabase
import com.lumina.cleaner.core.database.PhotoEntity
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.model.SamplingMode
import com.lumina.cleaner.domain.album.AlbumInfo
import com.lumina.cleaner.domain.album.AlbumManager
import com.lumina.cleaner.domain.motion.MotionPhotoParser
import com.lumina.cleaner.domain.sampling.SamplingEngine
import com.lumina.cleaner.ui.album.AlbumBottomSheet
import com.lumina.cleaner.ui.album.MonthBottomSheet
import com.lumina.cleaner.ui.deck.CardStack
import com.lumina.cleaner.ui.deck.CardSwipeAction
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.core.util.HapticIntensity
import com.lumina.cleaner.ui.deck.ModePill
import com.lumina.cleaner.ui.deck.ModeSelectionOverlay
import com.lumina.cleaner.ui.onboarding.OnboardingScreen
import com.lumina.cleaner.ui.settings.SettingsScreen
import com.lumina.cleaner.ui.theme.MundruTheme
import com.lumina.cleaner.ui.theme.AppThemeMode
import androidx.compose.ui.res.painterResource
import com.lumina.cleaner.R
import com.lumina.cleaner.ui.trash.RecycleBinScreen
import com.lumina.cleaner.ui.viewer.PhotoViewerOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ActiveScreen {
    MAIN,
    SETTINGS,
    RECYCLE_BIN
}

/**
 * Mundru 核心主界面 (MainActivity)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 交互哲学：用最极简的手势与视觉，赋能相册回忆的沉淀与擦除。
 * 纯手势沉浸式相册极简整理中心（左滑保留下一张、右滑无缝回溯上一张、上滑标记丢进回收站）
 * 集成 Android 11+ Scoped Storage 系统批量物理删除、零网络纯离线隐私保护
 */
class MainActivity : ComponentActivity() {

    private val database by lazy { MundruDatabase.getInstance(this) }
    private val samplingEngine = SamplingEngine()
    private val albumManager by lazy { AlbumManager(this) }

    private var hasPermission by mutableStateOf(false)
    private var isPartialAccess by mutableStateOf(false)
    private var isBackgroundBlurEnabled: Boolean = true
    private var isAppPaused by mutableStateOf(false)

    private fun applySecurityFlags(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Android 11+ Scoped Storage 系统批量彻底删除 Launcher
    private lateinit var deletePermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingDeletePhotoIds: List<Long>? = null
    private var pendingDeleteUris: List<Uri>? = null
    private var onDeleteCompleteCallback: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 注册系统删除结果回调（Fix Defect A：双重主键绑定与 URI 兜底，绝无幽灵残留条目）
        deletePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val ids = pendingDeletePhotoIds ?: emptyList()
                val uris = pendingDeleteUris ?: emptyList()
                lifecycleScopeLaunch {
                    withContext(Dispatchers.IO) {
                        if (ids.isNotEmpty()) {
                            database.photoDao().deleteFromTrash(ids)
                        }
                        val uriStrings = uris.map { it.toString() }
                        if (uriStrings.isNotEmpty()) {
                            database.photoDao().deleteFromTrashByUris(uriStrings)
                        }
                    }
                    val deletedCount = maxOf(ids.size, uris.size)
                    Toast.makeText(this@MainActivity, "已成功从设备彻底删除 $deletedCount 张照片", Toast.LENGTH_SHORT).show()
                    onDeleteCompleteCallback?.invoke()
                    pendingDeletePhotoIds = null
                    pendingDeleteUris = null
                    onDeleteCompleteCallback = null
                }
            } else {
                Toast.makeText(this@MainActivity, "取消删除", Toast.LENGTH_SHORT).show()
                pendingDeletePhotoIds = null
                pendingDeleteUris = null
                onDeleteCompleteCallback = null
            }
        }

        updatePermissionStatus()

        val prefs = getSharedPreferences("mundru_prefs", Context.MODE_PRIVATE)
        isBackgroundBlurEnabled = prefs.getBoolean("background_blur_enabled", true)
        applySecurityFlags(isBackgroundBlurEnabled)
        val initialThemeMode = AppThemeMode.fromCode(prefs.getInt("theme_mode", AppThemeMode.FOLLOW_SYSTEM.code))
        val hasCompletedOnboardingInit = prefs.getBoolean("has_completed_onboarding", false)

        // 窗口级防闪白预着色：在首帧渲染前即刻对齐当前保存的主题背景色，根治暗色模式冷启动与转场闪白
        val isInitialDark = when (initialThemeMode) {
            AppThemeMode.FOLLOW_SYSTEM -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
        }
        val initialWindowBg = if (isInitialDark) 0xFF0E0E12.toInt() else 0xFFF9F9FB.toInt()
        window.setBackgroundDrawable(ColorDrawable(initialWindowBg))
        window.decorView.setBackgroundColor(initialWindowBg)

        // 首启先展示引导页，引导完成后再触发权限弹窗 (Fix G2)
        if (hasCompletedOnboardingInit && !hasPermission) {
            requestRequiredPermissions()
        }

        setContent {
            var themeMode by remember {
                mutableStateOf(initialThemeMode)
            }

            MundruTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                var hasCompletedOnboarding by remember {
                    mutableStateOf(prefs.getBoolean("has_completed_onboarding", false))
                }
                var showTutorial by remember { mutableStateOf(false) }

                BackHandler(enabled = showTutorial) {
                    showTutorial = false
                }

                when {
                    !hasCompletedOnboarding || showTutorial -> {
                        OnboardingScreen(
                            onComplete = {
                                val isFirstTime = !hasCompletedOnboarding
                                hasCompletedOnboarding = true
                                showTutorial = false
                                prefs.edit().putBoolean("has_completed_onboarding", true).apply()
                                if (isFirstTime && !hasPermission) {
                                    requestRequiredPermissions()
                                }
                            }
                        )
                    }
                    hasPermission -> {
                        MainContent(
                            currentThemeMode = themeMode,
                            onThemeModeChange = { newMode ->
                                themeMode = newMode
                                prefs.edit().putInt("theme_mode", newMode.code).apply()
                            },
                            onShowTutorial = { showTutorial = true }
                        )
                    }
                    else -> {
                        PermissionScreen(onRequest = { requestRequiredPermissions() })
                    }
                }

                // 后台防窥遮罩层 (系统级 FLAG_SECURE + UI 级双保险)
                if (isAppPaused && isBackgroundBlurEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_mundru_logo),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Mundru 隐私保护中",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }
                }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isAppPaused = true
        applySecurityFlags(isBackgroundBlurEnabled)
    }

    override fun onResume() {
        super.onResume()
        isAppPaused = false
        applySecurityFlags(isBackgroundBlurEnabled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 用户手势呼出最近任务面板失去焦点时，瞬时激活防窥遮罩
        if (!hasFocus) {
            isAppPaused = true
            applySecurityFlags(isBackgroundBlurEnabled)
        } else {
            isAppPaused = false
            applySecurityFlags(isBackgroundBlurEnabled)
        }
    }

    private fun lifecycleScopeLaunch(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val full = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            val partial = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            hasPermission = full || partial
            isPartialAccess = partial && !full
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            isPartialAccess = false
        } else {
            hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            isPartialAccess = false
        }
    }

    private fun requestRequiredPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    /**
     * 调用系统 Scoped Storage 批量删除
     */
    fun requestSystemDelete(photoIds: List<Long>, uris: List<Uri>, onComplete: () -> Unit) {
        if (uris.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
                pendingDeletePhotoIds = photoIds
                pendingDeleteUris = uris
                onDeleteCompleteCallback = onComplete
                deletePermissionLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                Toast.makeText(this, "发起删除请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Android 10 及以下直接使用 ContentResolver 删除
            lifecycleScopeLaunch {
                withContext(Dispatchers.IO) {
                    uris.forEach { contentResolver.delete(it, null, null) }
                    if (photoIds.isNotEmpty()) {
                        database.photoDao().deleteFromTrash(photoIds)
                    }
                    val uriStrings = uris.map { it.toString() }
                    database.photoDao().deleteFromTrashByUris(uriStrings)
                }
                Toast.makeText(this@MainActivity, "已彻底删除 ${uris.size} 张照片", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }

    @Composable
    private fun MainContent(
        currentThemeMode: AppThemeMode,
        onThemeModeChange: (AppThemeMode) -> Unit,
        onShowTutorial: () -> Unit
    ) {
        val prefs = remember { getSharedPreferences("mundru_prefs", Context.MODE_PRIVATE) }
        val coroutineScope = rememberCoroutineScope()

        var currentBatchSize by remember {
            mutableIntStateOf(prefs.getInt("batch_size", 15))
        }

        var hapticIntensityLevel by remember {
            mutableIntStateOf(prefs.getInt("haptic_intensity", HapticIntensity.MEDIUM.level))
        }
        var swipeHapticsEnabled by remember {
            mutableStateOf(prefs.getBoolean("swipe_haptics_enabled", true))
        }
        var backgroundBlurEnabled by remember {
            mutableStateOf(prefs.getBoolean("background_blur_enabled", true))
        }

        LaunchedEffect(hapticIntensityLevel, swipeHapticsEnabled) {
            HapticFeedback.updateSettings(hapticIntensityLevel, swipeHapticsEnabled)
        }

        var currentMode by remember { mutableStateOf(SamplingMode.RANDOM_WALK) }
        var currentBatch by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
        var currentIndex by remember { mutableIntStateOf(0) }
        var allLibraryPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        // 专清模式目标
        var selectedAlbumBucket by remember { mutableStateOf<String?>(null) }
        var selectedMonthBucket by remember { mutableStateOf<MonthBucket?>(null) }

        // 回收站与设置界面状态
        var activeScreen by remember { mutableStateOf(ActiveScreen.MAIN) }
        val trashItemsFlow = remember { database.photoDao().getTrashItems() }.collectAsState(initial = emptyList())
        val trashItems = trashItemsFlow.value

        // 图集专清弹层状态
        var showAlbumSheet by remember { mutableStateOf(false) }
        var availableAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }

        // 模式选择全屏动效弹层状态
        var showModeSelector by remember { mutableStateOf(false) }

        // 月份选择弹层状态
        var showMonthSheet by remember { mutableStateOf(false) }
        var availableMonths by remember { mutableStateOf<List<MonthBucket>>(emptyList()) }

        // 统计数据状态
        var totalReviewedCount by remember { mutableLongStateOf(0L) }
        var totalPhotosCount by remember { mutableIntStateOf(0) }
        var isBatchInCooldown by remember { mutableStateOf(false) }

        // 历史操作栈（用于右滑上一张与回溯撤回）
        val actionHistory = remember { mutableStateListOf<Pair<PhotoItem, CardSwipeAction>>() }
        var viewingPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var reloadBatchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        // 系统返回拦截：设置中心、回收站全屏（大图查看由 PhotoViewerOverlay 内部平滑收拢动效接管）
        BackHandler(enabled = activeScreen != ActiveScreen.MAIN) {
            activeScreen = ActiveScreen.MAIN
        }

        // 重新加载批次（带取消机制与全模式 24h 冷却防重复算法）
        fun reloadBatch(mode: SamplingMode) {
            reloadBatchJob?.cancel()
            reloadBatchJob = coroutineScope.launch {
                isLoading = true
                if (allLibraryPhotos.isEmpty()) {
                    currentBatch = emptyList()
                    currentIndex = 0
                    actionHistory.clear()
                    isBatchInCooldown = false
                    isLoading = false
                    return@launch
                }
                val (activePhotos, viewHistoryMap) = withContext(Dispatchers.IO) {
                    val activeEntities = database.photoDao().getAllActivePhotosSync()
                    val entityMap = activeEntities.associateBy { it.id }
                    val historyMap = activeEntities.associate { it.id to it.lastViewedAt }
                    val filtered = allLibraryPhotos.filter { it.id in entityMap }
                    filtered to historyMap
                }

                val batch = samplingEngine.generateBatch(
                    context = this@MainActivity,
                    mode = mode,
                    allPhotos = activePhotos,
                    batchSize = currentBatchSize,
                    viewHistoryMap = viewHistoryMap,
                    targetAlbumBucket = selectedAlbumBucket,
                    targetMonthBucket = selectedMonthBucket
                )

                // 异步轻量检测实况照片与 HDR，否定结果记为 -1L 杜绝重复扫描（Fix P1-1）
                val enrichedBatch = withContext(Dispatchers.IO) {
                    batch.map { photo ->
                        if (!photo.isLivePhoto && photo.motionVideoLength == 0L) {
                            val mediaMeta = MotionPhotoParser.parse(this@MainActivity, photo.uri)
                            val finalLength = if (mediaMeta.isMotionPhoto) mediaMeta.videoLength else -1L
                            database.photoDao().updateMotionAndHdrMetadata(
                                id = photo.id,
                                isLivePhoto = mediaMeta.isMotionPhoto,
                                offset = mediaMeta.videoOffset,
                                length = finalLength,
                                isHdr = mediaMeta.isHdr
                            )
                            photo.copy(
                                isLivePhoto = mediaMeta.isMotionPhoto,
                                motionVideoOffset = mediaMeta.videoOffset,
                                motionVideoLength = finalLength,
                                isHdr = mediaMeta.isHdr
                            )
                        } else photo
                    }
                }

                val hasCandidatesForMode = when (mode) {
                    SamplingMode.ALBUM_CLEANUP -> activePhotos.any { it.bucketName == selectedAlbumBucket }
                    SamplingMode.MONTH_CLEANUP -> selectedMonthBucket?.let { mb ->
                        activePhotos.any {
                            val t = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                            t in mb.startMillis..mb.endMillis
                        }
                    } ?: false
                    SamplingMode.TIME_CAPSULE -> activePhotos.any {
                        val t = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                        (System.currentTimeMillis() - t) >= 30L * 24 * 3600 * 1000L
                    }
                    SamplingMode.RANDOM_WALK -> activePhotos.isNotEmpty()
                }
                isBatchInCooldown = enrichedBatch.isEmpty() && hasCandidatesForMode

                currentBatch = enrichedBatch
                currentIndex = 0
                actionHistory.clear()
                isLoading = false
            }
        }

        // 初始化加载相册（Fix Defect D：读取旧行合并，绝不覆盖清空 viewCount、lastViewedAt 与实况/HDR 元数据）
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val photos = scanMediaStoreFast()
                val existingMap = database.photoDao().getAllPhotosSync().associateBy { it.id }

                val entities = photos.map { photo ->
                    val existing = existingMap[photo.id]
                    PhotoEntity(
                        id = photo.id,
                        uriString = photo.uri.toString(),
                        displayName = photo.displayName,
                        dateModified = photo.dateModified,
                        dateTaken = photo.dateTaken,
                        size = photo.size,
                        width = photo.width,
                        height = photo.height,
                        bucketName = photo.bucketName,
                        relativePath = null,
                        isLivePhoto = existing?.isLivePhoto ?: false,
                        motionVideoOffset = existing?.motionVideoOffset ?: 0L,
                        motionVideoLength = existing?.motionVideoLength ?: 0L,
                        isHdr = existing?.isHdr ?: photo.isHdr,
                        viewCount = existing?.viewCount ?: 0,
                        lastViewedAt = existing?.lastViewedAt ?: 0L
                    )
                }
                database.photoDao().insertOrUpdateAll(entities)

                // 将数据库中已缓存的实况与 HDR 数据合并回内存列表
                allLibraryPhotos = photos.map { p ->
                    val existing = existingMap[p.id]
                    if (existing != null) {
                        p.copy(
                            isLivePhoto = existing.isLivePhoto,
                            motionVideoOffset = existing.motionVideoOffset,
                            motionVideoLength = existing.motionVideoLength,
                            isHdr = existing.isHdr || p.isHdr
                        )
                    } else p
                }

                totalReviewedCount = database.photoDao().getTotalReviewedCount() ?: 0L
                totalPhotosCount = database.photoDao().getActivePhotoCount()
            }
            reloadBatch(currentMode)
        }

        // 全屏界面双向物理转场与主舞台（全覆盖当前主题背景，彻底消灭转场缝隙露白）
        AnimatedContent(
            targetState = activeScreen,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            transitionSpec = {
                when (targetState) {
                    ActiveScreen.SETTINGS -> {
                        (slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(animationSpec = tween(220)))
                            .togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { it / 4 },
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                ) + fadeOut(animationSpec = tween(180))
                            )
                    }
                    ActiveScreen.RECYCLE_BIN -> {
                        (slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(animationSpec = tween(220)))
                            .togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { -it / 4 },
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                ) + fadeOut(animationSpec = tween(180))
                            )
                    }
                    ActiveScreen.MAIN -> {
                        if (initialState == ActiveScreen.SETTINGS) {
                            (slideInHorizontally(
                                initialOffsetX = { -it / 4 },
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            ) + fadeIn(animationSpec = tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { -it },
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    ) + fadeOut(animationSpec = tween(180))
                                ).apply {
                                    targetContentZIndex = -1f
                                }
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { it / 4 },
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                            ) + fadeIn(animationSpec = tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { it },
                                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                    ) + fadeOut(animationSpec = tween(180))
                                ).apply {
                                    targetContentZIndex = -1f
                                }
                        }
                    }
                }
            },
            label = "active_screen_transition"
        ) { screen ->
            when (screen) {
                ActiveScreen.SETTINGS -> {
                    SettingsScreen(
                        currentBatchSize = currentBatchSize,
                        onBatchSizeChange = { newSize ->
                            currentBatchSize = newSize
                            prefs.edit().putInt("batch_size", newSize).apply()
                            reloadBatch(currentMode)
                        },
                        hapticIntensityLevel = hapticIntensityLevel,
                        onHapticIntensityChange = { level ->
                            hapticIntensityLevel = level
                            prefs.edit().putInt("haptic_intensity", level).apply()
                            HapticFeedback.updateSettings(level, swipeHapticsEnabled)
                        },
                        swipeHapticsEnabled = swipeHapticsEnabled,
                        onSwipeHapticsEnabledChange = { swipeEnabled ->
                            swipeHapticsEnabled = swipeEnabled
                            prefs.edit().putBoolean("swipe_haptics_enabled", swipeEnabled).apply()
                            HapticFeedback.updateSettings(hapticIntensityLevel, swipeEnabled)
                        },
                        backgroundBlurEnabled = backgroundBlurEnabled,
                        onBackgroundBlurEnabledChange = { enabled ->
                            backgroundBlurEnabled = enabled
                            isBackgroundBlurEnabled = enabled
                            applySecurityFlags(enabled)
                            prefs.edit().putBoolean("background_blur_enabled", enabled).apply()
                        },
                        currentThemeMode = currentThemeMode,
                        onThemeModeChange = onThemeModeChange,
                        totalReviewed = totalReviewedCount,
                        trashBytes = trashItems.sumOf { it.size },
                        totalPhotos = totalPhotosCount,
                        onShowTutorial = {
                            activeScreen = ActiveScreen.MAIN
                            onShowTutorial()
                        },
                        onBack = { activeScreen = ActiveScreen.MAIN }
                    )
                }
                ActiveScreen.RECYCLE_BIN -> {
                    RecycleBinScreen(
                        trashItems = trashItems,
                        onRestore = { photoIds ->
                            coroutineScope.launch(Dispatchers.IO) {
                                photoIds.forEach { database.photoDao().restoreFromTrash(it) }
                            }
                        },
                        onPermanentDelete = { photoIds, uris ->
                            requestSystemDelete(photoIds, uris) {
                                reloadBatch(currentMode)
                            }
                        },
                        onBack = { activeScreen = ActiveScreen.MAIN }
                    )
                }
                ActiveScreen.MAIN -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 部分照片权限提示条 (Android 14)
                            AnimatedVisibility(visible = isPartialAccess) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "当前仅授权了部分照片访问",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "选择更多",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { requestRequiredPermissions() }
                                    )
                                }
                            }

                            // 顶部状态与模式栏 (统一高对比圆环按键，彻底消灭暗色模式看不清缺陷)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 设置与统计入口 (高对比圆形底座 + onSurface 明亮图标)
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            HapticFeedback.click(this@MainActivity)
                                            activeScreen = ActiveScreen.SETTINGS
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = "Settings",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // 居中模式悬浮胶囊
                                ModePill(
                                    currentMode = currentMode,
                                    onClick = {
                                        HapticFeedback.click(this@MainActivity)
                                        showModeSelector = true
                                    },
                                    isExpanded = showModeSelector
                                )

                                // 刷新与回收站入口 (高对比圆形底座 + onSurface 明亮图标)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                HapticFeedback.click(this@MainActivity)
                                                reloadBatch(currentMode)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = "Refresh",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                HapticFeedback.click(this@MainActivity)
                                                activeScreen = ActiveScreen.RECYCLE_BIN
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = "Trash",
                                                tint = if (trashItems.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 专清模式副标签指示器
                            if (selectedAlbumBucket != null || selectedMonthBucket != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                    ) {
                                        Text(
                                            text = selectedAlbumBucket?.let { "图集: $it" } ?: "月份: ${selectedMonthBucket?.displayName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            // 批次进度微指标
                            if (currentBatch.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${(currentIndex + 1).coerceAtMost(currentBatch.size)} / ${currentBatch.size}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 卡片主舞台
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.dp
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "正在唤醒相册记忆...",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (currentBatch.isNotEmpty() && currentIndex >= currentBatch.size) {
                                    LaunchedEffect(Unit) {
                                        if (currentBatch.isNotEmpty()) {
                                            HapticFeedback.doublePulse(this@MainActivity)
                                        }
                                    }
                                    // 批次完成结算卡片
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shadowElevation = 8.dp,
                                        modifier = Modifier.padding(28.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(28.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Celebration,
                                                contentDescription = "Celebration",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(46.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text("这一批已整理完毕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "回收站暂存 ${trashItems.size} 张待彻底清除",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(modifier = Modifier.height(20.dp))

                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Button(
                                                    onClick = {
                                                        HapticFeedback.click(this@MainActivity)
                                                        reloadBatch(currentMode)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text("开启新批次")
                                                }

                                                if (trashItems.isNotEmpty()) {
                                                    Button(
                                                        onClick = {
                                                            HapticFeedback.heavy(this@MainActivity)
                                                            val ids = trashItems.map { it.photoId }
                                                            val uris = trashItems.map { Uri.parse(it.uriString) }
                                                            requestSystemDelete(ids, uris) {
                                                                reloadBatch(currentMode)
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Text("清空回收站")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else if (currentBatch.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shadowElevation = 4.dp,
                                        modifier = Modifier.padding(28.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(28.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = if (isBatchInCooldown) Icons.Rounded.History else Icons.Rounded.CheckCircle,
                                                contentDescription = if (isBatchInCooldown) "Cooling" else "Empty",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(46.dp)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = if (isBatchInCooldown) "这批照片刚刚整理过" else "相册非常整洁",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = if (isBatchInCooldown)
                                                    "所有照片已妥善保留并进入冷却期，稍后会重新出现；你可以切换其他模式继续整理回忆"
                                                else
                                                    "当前模式下暂无可整理的照片",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                lineHeight = 18.sp,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(20.dp))

                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                if (isBatchInCooldown) {
                                                    Button(
                                                        onClick = {
                                                            HapticFeedback.click(this@MainActivity)
                                                            showModeSelector = true
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                        shape = RoundedCornerShape(12.dp)
                                                    ) {
                                                        Text("切换模式")
                                                    }
                                                }
                                                Button(
                                                    onClick = {
                                                        HapticFeedback.click(this@MainActivity)
                                                        reloadBatch(currentMode)
                                                    },
                                                    colors = if (isBatchInCooldown)
                                                        ButtonDefaults.buttonColors(
                                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                            contentColor = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    else
                                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text("重新扫描")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    CardStack(
                                        photos = currentBatch,
                                        currentIndex = currentIndex,
                                        onClickPhoto = { photo ->
                                            HapticFeedback.click(this@MainActivity)
                                            viewingPhoto = photo
                                        },
                                        onSwipeLeft = { photo ->
                                            actionHistory.add(photo to CardSwipeAction.KEEP)
                                            currentIndex++
                                            coroutineScope.launch(Dispatchers.IO) {
                                                database.photoDao().markViewed(photo.id, System.currentTimeMillis())
                                                totalReviewedCount = database.photoDao().getTotalReviewedCount() ?: 0L
                                            }
                                        },
                                        onSwipeRight = {
                                            if (currentIndex > 0) {
                                                currentIndex--
                                                val last = actionHistory.removeLastOrNull()
                                                if (last != null && last.second == CardSwipeAction.DELETE) {
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        database.photoDao().restoreFromTrash(last.first.id)
                                                    }
                                                }
                                            }
                                        },
                                        onSwipeUp = { photo ->
                                            actionHistory.add(photo to CardSwipeAction.DELETE)
                                            currentIndex++
                                            coroutineScope.launch(Dispatchers.IO) {
                                                database.photoDao().markViewed(photo.id, System.currentTimeMillis())
                                                database.photoDao().moveToTrash(
                                                    LocalTrashEntity(
                                                        photoId = photo.id,
                                                        uriString = photo.uri.toString(),
                                                        size = photo.size,
                                                        deletedAt = System.currentTimeMillis()
                                                    )
                                                )
                                                totalReviewedCount = database.photoDao().getTotalReviewedCount() ?: 0L
                                            }
                                        }
                                    )
                                }
                            }

                            // 底部微留白，彻底移除三大按键，释放纯净全屏手势视野
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // 图集专清选择弹层
                        if (showAlbumSheet) {
                            AlbumBottomSheet(
                                albums = availableAlbums,
                                onSelectAlbum = { albumName ->
                                    showAlbumSheet = false
                                    currentMode = SamplingMode.ALBUM_CLEANUP
                                    selectedAlbumBucket = albumName
                                    selectedMonthBucket = null
                                    reloadBatch(SamplingMode.ALBUM_CLEANUP)
                                },
                                onDismiss = {
                                    showAlbumSheet = false
                                }
                            )
                        }

                        // 月份专清选择弹层
                        if (showMonthSheet) {
                            MonthBottomSheet(
                                months = availableMonths,
                                onSelectMonth = { monthBucket ->
                                    showMonthSheet = false
                                    currentMode = SamplingMode.MONTH_CLEANUP
                                    selectedMonthBucket = monthBucket
                                    selectedAlbumBucket = null
                                    reloadBatch(SamplingMode.MONTH_CLEANUP)
                                },
                                onDismiss = { showMonthSheet = false }
                            )
                        }

                        // 模式选择全屏动效页面（从按钮物理位置一直放大弹出）
                        if (showModeSelector) {
                            ModeSelectionOverlay(
                                currentMode = currentMode,
                                onSelectMode = { newMode ->
                                    showModeSelector = false
                                    when (newMode) {
                                        SamplingMode.ALBUM_CLEANUP -> {
                                            coroutineScope.launch {
                                                availableAlbums = albumManager.getAvailableAlbums()
                                                showAlbumSheet = true
                                            }
                                        }
                                        SamplingMode.MONTH_CLEANUP -> {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val months = samplingEngine.extractMonthBuckets(allLibraryPhotos)
                                                withContext(Dispatchers.Main) {
                                                    availableMonths = months
                                                    showMonthSheet = true
                                                }
                                            }
                                        }
                                        else -> {
                                            currentMode = newMode
                                            selectedAlbumBucket = null
                                            selectedMonthBucket = null
                                            reloadBatch(newMode)
                                        }
                                    }
                                },
                                onDismiss = { showModeSelector = false }
                            )
                        }
                    }
                }
            }
        }

        // 沉浸式高保真原图查看器
        viewingPhoto?.let { photo ->
            PhotoViewerOverlay(
                photo = photo,
                onDismiss = { viewingPhoto = null }
            )
        }
    }

    @Composable
    private fun PermissionScreen(onRequest: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "相册整理权限",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Mundru 为 100% 本地纯离线相册整理工具，绝不联网。需要照片读取权限来展示和整理回忆。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.size(24.dp))
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("授予权限", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
    }

    private fun scanMediaStoreFast(): List<PhotoItem> {
        val list = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val ctCol = cursor.getColumnIndex("color_transfer")
            val hdrCol = cursor.getColumnIndex("is_hdr")

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val itemUri = ContentUris.withAppendedId(uri, id)
                var isHdr = false
                if (ctCol >= 0 && cursor.getInt(ctCol) >= 6) isHdr = true
                if (hdrCol >= 0 && cursor.getInt(hdrCol) > 0) isHdr = true

                list.add(
                    PhotoItem(
                        id = id,
                        uri = itemUri,
                        displayName = cursor.getString(nameCol) ?: "IMG_$id",
                        dateModified = cursor.getLong(modCol) * 1000L,
                        dateTaken = cursor.getLong(takenCol),
                        size = cursor.getLong(sizeCol),
                        width = cursor.getInt(wCol),
                        height = cursor.getInt(hCol),
                        bucketName = cursor.getString(bucketCol),
                        isLivePhoto = false,
                        isHdr = isHdr
                    )
                )
            }
        }
        return list
    }
}
