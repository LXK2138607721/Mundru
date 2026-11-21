package com.lumina.cleaner

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lumina.cleaner.domain.album.activeLibraryCount
import com.lumina.cleaner.domain.album.visibleImageIds
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
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
import com.lumina.cleaner.domain.sampling.hasKnownFileSize
import com.lumina.cleaner.ui.album.AlbumBottomSheet
import com.lumina.cleaner.ui.album.MonthBottomSheet
import com.lumina.cleaner.ui.deck.CardStack
import com.lumina.cleaner.ui.deck.CardSwipeAction
import com.lumina.cleaner.ui.deck.DeckShaderWarmup
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
import com.lumina.cleaner.core.database.toPhotoItem
import com.lumina.cleaner.ui.viewer.PhotoViewerOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PENDING_DELETE_URIS = "mundru_pending_delete_uris"
private const val MAX_DELETE_REQUEST = 2000

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
    private val photoWrites get() = (application as MundruApp).photoWrites
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
    private var pendingDeleteUris: List<String>? = null
    private var deletionBusy = false
    private var inventoryRevision by mutableIntStateOf(0)
    private var permissionWarmup: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionWarmup?.cancel()
        permissionWarmup = null
        updatePermissionStatus()
        inventoryRevision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        pendingDeleteUris = savedInstanceState?.getStringArrayList(PENDING_DELETE_URIS)
        deletionBusy = pendingDeleteUris != null
        deletePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val uris = pendingDeleteUris.orEmpty()
            pendingDeleteUris = null
            lifecycleScopeLaunch {
                try {
                    if (result.resultCode != Activity.RESULT_OK) {
                        showDeleteMessage("已取消删除")
                    } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        val removed = withContext(Dispatchers.IO) {
                            uris.filter { uri ->
                                try {
                                    contentResolver.delete(Uri.parse(uri), null, null) > 0
                                } catch (_: RuntimeException) {
                                    false
                                }
                            }
                        }
                        finishPhysicalDelete(removed)
                    } else {
                        finishPhysicalDelete(uris)
                    }
                } finally {
                    deletionBusy = false
                }
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
        updatePermissionStatus()
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
        val firstAccess = !hasPermission
        permissionLauncher.launch(perms)
        // The system permission window is in a different process. Use that time instead of
        // competing with first-time indexing after the user grants access.
        if (firstAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissionWarmup == null) {
            val app = applicationContext
            val density = resources.displayMetrics.density
            permissionWarmup = lifecycleScope.launch(Dispatchers.Default) {
                delay(200)
                DeckShaderWarmup.prepare(app, density) { isActive }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingDeleteUris?.let { outState.putStringArrayList(PENDING_DELETE_URIS, ArrayList(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun showDeleteMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun removeTrashRows(uris: List<String>) {
        if (uris.isEmpty()) return
        val committed = photoWrites.enqueue {
            database.withTransaction {
                uris.chunked(900).forEach { database.photoDao().deleteFromTrashByUris(it) }
            }
        }
        committed.join()
    }

    private suspend fun finishPhysicalDelete(uris: List<String>) {
        removeTrashRows(uris)
        if (uris.isNotEmpty()) inventoryRevision++
        showDeleteMessage(
            if (uris.isEmpty()) "未删除照片，已保留回收站记录"
            else "已从设备删除 ${uris.size} 张照片"
        )
    }

    private fun confirmSystemDelete(uris: List<String>, sender: IntentSender) {
        pendingDeleteUris = uris
        try {
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            pendingDeleteUris = null
            throw e
        }
    }

    private fun requestSystemDelete(uris: List<Uri>) {
        if (uris.isEmpty() || deletionBusy) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            showDeleteMessage("授予存储写入权限后，请重新确认删除")
            return
        }

        deletionBusy = true
        val canVerifyMissing = hasPermission && !isPartialAccess
        lifecycleScopeLaunch {
            try {
                val targets = uris.distinct()
                val requested = targets.take(MAX_DELETE_REQUEST)
                val (available, missing) = withContext(Dispatchers.IO) {
                    val available = mutableListOf<Uri>()
                    val missing = mutableListOf<String>()
                    for (uri in requested) {
                        val present = try {
                            contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                                ?.use { it.moveToFirst() }
                        } catch (_: SecurityException) {
                            null
                        }
                        if (present == true) available.add(uri)
                        else if (present == false && canVerifyMissing) missing.add(uri.toString())
                    }
                    available to missing
                }

                removeTrashRows(missing)
                if (missing.isNotEmpty()) inventoryRevision++
                val skipped = targets.size - available.size - missing.size
                if (available.isEmpty()) {
                    showDeleteMessage(if (skipped > 0) "部分照片无法访问，未移除其回收站记录" else "已清理失效条目")
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (skipped > 0) showDeleteMessage("有 $skipped 张未纳入本次删除，已保留")
                    val request = MediaStore.createDeleteRequest(contentResolver, available)
                    confirmSystemDelete(available.map(Uri::toString), request.intentSender)
                } else {
                    val removed = mutableListOf<String>()
                    var consent: Pair<String, IntentSender>? = null
                    withContext(Dispatchers.IO) {
                        for (uri in available) {
                            try {
                                if (contentResolver.delete(uri, null, null) > 0) removed.add(uri.toString())
                            } catch (e: SecurityException) {
                                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                                    consent = uri.toString() to e.userAction.actionIntent.intentSender
                                    break
                                }
                            } catch (_: RuntimeException) {
                                // Keep the entry when the provider did not confirm deletion.
                            }
                        }
                    }
                    if (consent != null) {
                        removeTrashRows(removed)
                        if (removed.isNotEmpty()) inventoryRevision++
                        if (available.size - removed.size > 1) showDeleteMessage("此系统需逐张授权，其余照片已保留")
                        consent?.let { (uri, sender) -> confirmSystemDelete(listOf(uri), sender) }
                    } else {
                        finishPhysicalDelete(removed)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                showDeleteMessage("删除未完成，请重试。未确认删除的照片仍保留在回收站。")
            } finally {
                if (pendingDeleteUris == null) deletionBusy = false
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
        val photoDatabase = remember { database }
        val photoDao = remember { photoDatabase.photoDao() }

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
        var librarySnapshot by remember { mutableStateOf<List<PhotoItem>?>(null) }
        val allLibraryPhotos = librarySnapshot.orEmpty()
        var isLoading by remember { mutableStateOf(true) }

        // 专清模式目标
        var selectedAlbumBucket by remember { mutableStateOf<String?>(null) }
        var selectedMonthBucket by remember { mutableStateOf<MonthBucket?>(null) }

        // 回收站与设置界面状态
        var activeScreen by remember { mutableStateOf(ActiveScreen.MAIN) }
        val trashItemsFlow = remember { photoDao.getTrashItems() }.collectAsState(initial = emptyList())
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
        var isBatchInCooldown by remember { mutableStateOf(false) }

        // 历史操作栈（用于右滑上一张与回溯撤回）
        val actionHistory = remember { mutableStateListOf<Pair<PhotoItem, CardSwipeAction>>() }
        var viewingPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var viewingZoomFocus by remember { mutableStateOf<Offset?>(null) }
        var reloadBatchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        fun persistPhotoAction(write: suspend () -> Unit) {
            photoWrites.enqueue(write)
        }

        LaunchedEffect(activeScreen) {
            if (activeScreen == ActiveScreen.SETTINGS) {
                photoWrites.awaitPendingWrites()
                totalReviewedCount = withContext(Dispatchers.IO) { photoDao.getTotalReviewedCount() ?: 0L }
            }
        }

        // 系统返回拦截：设置中心、回收站全屏（大图查看由 PhotoViewerOverlay 内部平滑收拢动效接管）
        BackHandler(enabled = activeScreen != ActiveScreen.MAIN) {
            activeScreen = ActiveScreen.MAIN
        }

        // Serialize scan results with accepted review writes, including those from a previous Activity.
        suspend fun scanVisiblePhotos(): List<PhotoItem> =
            try {
                withContext(Dispatchers.IO) { scanMediaStoreFast() }
            } catch (_: SecurityException) {
                updatePermissionStatus()
                emptyList()
            }

        suspend fun syncPhotoLibrary(photos: List<PhotoItem>) {
            val synchronized = photoWrites.enqueue {
                photoDatabase.withTransaction {
                    val existingMap = photoDao.getAllPhotosSync().associateBy { it.id }
                    val changed = mutableListOf<PhotoEntity>()
                    for (photo in photos) {
                        val existing = existingMap[photo.id]
                        val entity = PhotoEntity(
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
                            isHdr = existing?.isHdr == true || photo.isHdr,
                            viewCount = existing?.viewCount ?: 0,
                            lastViewedAt = existing?.lastViewedAt ?: 0L
                        )
                        if (entity != existing) changed.add(entity)
                    }
                    if (changed.isNotEmpty()) photoDao.insertOrUpdateAll(changed)
                }
            }
            synchronized.join()
        }

        // 重新加载批次（带取消机制与全模式 24h 冷却防重复算法）
        fun reloadBatch(mode: SamplingMode, refreshLibrary: Boolean = false) {
            reloadBatchJob?.cancel()
            val batchSize = currentBatchSize
            val albumBucket = selectedAlbumBucket
            val monthBucket = selectedMonthBucket
            isLoading = true
            reloadBatchJob = coroutineScope.launch {
                val cached = librarySnapshot
                var shaderWarmup: Job? = null
                val libraryPhotos = if (refreshLibrary || cached == null) {
                    val scanned = scanVisiblePhotos()
                    if (cached == null && scanned.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val density = resources.displayMetrics.density
                        val app = applicationContext
                        shaderWarmup = launch(Dispatchers.Default) {
                            DeckShaderWarmup.prepare(app, density) { isActive }
                        }
                    }
                    syncPhotoLibrary(scanned)
                    librarySnapshot = scanned
                    scanned
                } else cached
                if (libraryPhotos.isEmpty()) {
                    currentBatch = emptyList()
                    currentIndex = 0
                    actionHistory.clear()
                    isBatchInCooldown = false
                    isLoading = false
                    return@launch
                }
                photoWrites.awaitPendingWrites()
                val (activePhotos, viewHistoryMap) = withContext(Dispatchers.IO) {
                    val activeEntities = photoDao.getAllActivePhotosSync()
                    val entityMap = activeEntities.associateBy { it.id }
                    val historyMap = activeEntities.associate { it.id to it.lastViewedAt }
                    // Read parsed metadata from Room, not the stale startup snapshot.
                    val filtered = libraryPhotos.mapNotNull { photo ->
                        entityMap[photo.id]?.let { cached ->
                            photo.copy(
                                isLivePhoto = cached.isLivePhoto,
                                motionVideoOffset = cached.motionVideoOffset,
                                motionVideoLength = cached.motionVideoLength,
                                isHdr = cached.isHdr || photo.isHdr
                            )
                        }
                    }
                    filtered to historyMap
                }

                val batch = samplingEngine.generateBatch(
                    mode = mode,
                    allPhotos = activePhotos,
                    batchSize = batchSize,
                    viewHistoryMap = viewHistoryMap,
                    targetAlbumBucket = albumBucket,
                    targetMonthBucket = monthBucket
                )

                // 异步轻量检测实况照片与 HDR，否定结果记为 -1L 杜绝重复扫描（Fix P1-1）
                val enrichedBatch = withContext(Dispatchers.IO) {
                    batch.map { photo ->
                        if (!photo.isLivePhoto && photo.motionVideoLength == 0L) {
                            val mediaMeta = MotionPhotoParser.parse(this@MainActivity, photo.uri)
                            val finalLength = if (mediaMeta.isMotionPhoto) mediaMeta.videoLength else -1L
                            val isHdr = mediaMeta.isHdr || photo.isHdr
                            photoDao.updateMotionAndHdrMetadata(
                                id = photo.id,
                                isLivePhoto = mediaMeta.isMotionPhoto,
                                offset = mediaMeta.videoOffset,
                                length = finalLength,
                                isHdr = isHdr
                            )
                            photo.copy(
                                isLivePhoto = mediaMeta.isMotionPhoto,
                                motionVideoOffset = mediaMeta.videoOffset,
                                motionVideoLength = finalLength,
                                isHdr = isHdr
                            )
                        } else photo
                    }
                }

                val hasCandidatesForMode = withContext(Dispatchers.Default) {
                    when (mode) {
                        SamplingMode.ALBUM_CLEANUP -> activePhotos.any { albumBucket == null || it.bucketName == albumBucket }
                        SamplingMode.MONTH_CLEANUP -> monthBucket?.let { mb ->
                            activePhotos.any {
                                val t = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                                t in mb.startMillis until mb.endMillis
                            }
                        } ?: activePhotos.isNotEmpty()
                        SamplingMode.TIME_CAPSULE -> activePhotos.any {
                            val t = if (it.dateTaken > 0) it.dateTaken else it.dateModified
                            t > 0L && (System.currentTimeMillis() - t) >= 30L * 24 * 3600 * 1000L
                        }
                        SamplingMode.RANDOM_WALK -> activePhotos.isNotEmpty()
                        SamplingMode.LARGE_PHOTO_CLEANUP -> activePhotos.any { it.hasKnownFileSize }
                    }
                }
                isBatchInCooldown = enrichedBatch.isEmpty() && hasCandidatesForMode

                currentBatch = enrichedBatch
                currentIndex = 0
                actionHistory.clear()
                shaderWarmup?.cancel()
                isLoading = false
            }
        }

        LaunchedEffect(inventoryRevision) {
            reloadBatch(currentMode, refreshLibrary = true)
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
                    var liveIds by remember { mutableStateOf<Set<Long>?>(null) }
                    LaunchedEffect(Unit) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            contentResolver.visibleImageIds().collect { liveIds = it }
                        }
                    }
                    val visibleIds = liveIds ?: remember(allLibraryPhotos) { allLibraryPhotos.mapTo(HashSet()) { it.id } }
                    val totalPhotosCount = remember(visibleIds, trashItems) {
                        activeLibraryCount(visibleIds, trashItems.map { it.photoId })
                    }
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
                        onResolvePhotoItem = { photoId ->
                            withContext(Dispatchers.IO) {
                                photoDao.getPhotoById(photoId)?.toPhotoItem()
                            }
                        },
                        onRestore = { photoIds ->
                            persistPhotoAction {
                                val now = System.currentTimeMillis()
                                photoDatabase.withTransaction {
                                    photoIds.chunked(900).forEach { ids ->
                                        photoDao.restoreFromTrashBatch(ids)
                                        photoDao.markViewedBatch(ids, now)
                                    }
                                }
                            }
                        },
                        onPermanentDelete = ::requestSystemDelete,
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
                                                reloadBatch(currentMode, refreshLibrary = true)
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
                                                            requestSystemDelete(trashItems.map { Uri.parse(it.uriString) })
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
                                                        reloadBatch(currentMode, refreshLibrary = true)
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
                                        showFileSize = currentMode == SamplingMode.LARGE_PHOTO_CLEANUP,
                                        onClickPhoto = { photo ->
                                            HapticFeedback.click(this@MainActivity)
                                            viewingZoomFocus = null
                                            viewingPhoto = photo
                                        },
                                        onDoubleClickPhoto = { photo, focus ->
                                            HapticFeedback.click(this@MainActivity)
                                            viewingZoomFocus = focus
                                            viewingPhoto = photo
                                        },
                                        onSwipeLeft = { photo ->
                                            actionHistory.add(photo to CardSwipeAction.KEEP)
                                            currentIndex++
                                            persistPhotoAction {
                                                photoDao.markViewed(photo.id, System.currentTimeMillis())
                                            }
                                        },
                                        onSwipeRight = {
                                            if (currentIndex > 0) {
                                                currentIndex--
                                                val last = actionHistory.removeLastOrNull()
                                                if (last != null && last.second == CardSwipeAction.DELETE) {
                                                    persistPhotoAction {
                                                        val now = System.currentTimeMillis()
                                                        photoDatabase.withTransaction {
                                                            photoDao.restoreFromTrash(last.first.id)
                                                            photoDao.markViewed(last.first.id, now)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onSwipeUp = { photo ->
                                            actionHistory.add(photo to CardSwipeAction.DELETE)
                                            currentIndex++
                                            persistPhotoAction {
                                                photoDatabase.withTransaction {
                                                    photoDao.markViewed(photo.id, System.currentTimeMillis())
                                                    photoDao.moveToTrash(
                                                        LocalTrashEntity(
                                                            photoId = photo.id,
                                                            uriString = photo.uri.toString(),
                                                            size = photo.size,
                                                            deletedAt = System.currentTimeMillis()
                                                        )
                                                    )
                                                }
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
                                            reloadBatch(newMode, refreshLibrary = newMode == SamplingMode.LARGE_PHOTO_CLEANUP)
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
                initialZoomFocus = viewingZoomFocus,
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val itemUri = ContentUris.withAppendedId(uri, id)

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
                        bucketName = cursor.getString(bucketCol)
                    )
                )
            }
        }
        return list
    }
}
