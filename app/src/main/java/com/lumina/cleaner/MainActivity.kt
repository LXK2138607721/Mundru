package com.lumina.cleaner

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil.Coil
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lumina.cleaner.domain.album.ActiveLibraryCountCache
import com.lumina.cleaner.domain.album.DeleteExecutionStrategy
import com.lumina.cleaner.domain.album.MediaDeletePolicy
import com.lumina.cleaner.domain.album.SettingsVisibleIdsState
import com.lumina.cleaner.domain.album.visibleImageIds
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.lumina.cleaner.core.database.LocalTrashEntity
import com.lumina.cleaner.core.database.MotionHdrUpdate
import com.lumina.cleaner.core.database.MundruDatabase
import com.lumina.cleaner.core.database.PhotoEntity
import com.lumina.cleaner.core.model.MonthBucket
import com.lumina.cleaner.core.model.PhotoDisplayAspectCache
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.model.SamplingMode
import com.lumina.cleaner.core.model.resolveDisplayDimensions
import com.lumina.cleaner.domain.album.AlbumInfo
import com.lumina.cleaner.domain.album.AlbumManager
import com.lumina.cleaner.domain.motion.MotionPhotoParser
import com.lumina.cleaner.domain.sampling.PerceptualHash
import com.lumina.cleaner.domain.sampling.SamplingEngine
import com.lumina.cleaner.domain.sampling.hasKnownFileSize
import com.lumina.cleaner.ui.album.AlbumBottomSheet
import com.lumina.cleaner.ui.album.MonthBottomSheet
import com.lumina.cleaner.ui.deck.CardStack
import com.lumina.cleaner.ui.deck.DeckIdleWorkPolicy
import com.lumina.cleaner.ui.deck.DeckSessionState
import com.lumina.cleaner.ui.deck.DeckShaderWarmup
import com.lumina.cleaner.ui.deck.buildDeckCardImageRequest
import com.lumina.cleaner.ui.deck.deckCardAspectRatio
import com.lumina.cleaner.ui.deck.hasFastThumbOnDisk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.core.util.HapticIntensity
import com.lumina.cleaner.core.util.formatFileSize
import com.lumina.cleaner.ui.deck.ModePill
import com.lumina.cleaner.ui.deck.ModeSelectionOverlay
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import com.lumina.cleaner.ui.glass.LiquidTrashPill
import com.lumina.cleaner.ui.glass.LiquidVortexPortalRing
import com.lumina.cleaner.ui.glass.MundruBottomSheet
import com.lumina.cleaner.ui.glass.liquidBackdropSource
import com.lumina.cleaner.ui.glass.liquidVortexEffect
import com.lumina.cleaner.ui.glass.rememberLiquidBackdropState
import com.lumina.cleaner.ui.glass.rememberLiquidVortexState
import com.lumina.cleaner.ui.onboarding.OnboardingScreen
import com.lumina.cleaner.ui.settings.SettingsGlassButton
import com.lumina.cleaner.ui.settings.SettingsIconBadge
import com.lumina.cleaner.ui.settings.SettingsScreen
import com.lumina.cleaner.ui.theme.MundruTheme
import com.lumina.cleaner.ui.theme.AppThemeMode
import com.lumina.cleaner.ui.theme.stableStatusBarsPadding
import androidx.compose.ui.res.painterResource
import com.lumina.cleaner.R
import com.lumina.cleaner.ui.trash.RecycleBinScreen
import com.lumina.cleaner.ui.trash.TrashDeleteConfirmSheet
import com.lumina.cleaner.core.database.toPhotoItem
import com.lumina.cleaner.ui.viewer.PhotoViewerOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val reviewCoalescer get() = (application as MundruApp).reviewCoalescer
    private val samplingEngine = SamplingEngine()
    private val albumManager by lazy { AlbumManager(this) }

    private var hasPermission by mutableStateOf(false)
    private var isPartialAccess by mutableStateOf(false)
    private var canManageMedia by mutableStateOf(false)
    private var hasManageMediaSettingsEntry by mutableStateOf(false)
    private var isBackgroundBlurEnabled: Boolean = true
    private var appliedSecurityFlagsState: Boolean? = null
    private var suppressPrivacyCurtainForModal: Boolean = false
    private var isAppPaused by mutableStateOf(false)

    private fun canDirectDeleteMedia(): Boolean =
        MediaDeletePolicy.resolveStrategy(
            sdkInt = Build.VERSION.SDK_INT,
            canManageMedia = canManageMedia,
            isPartialAccess = isPartialAccess
        ) == DeleteExecutionStrategy.DIRECT_MANAGE_MEDIA

    private fun applySecurityFlags(enabled: Boolean) {
        if (appliedSecurityFlagsState == enabled) return
        appliedSecurityFlagsState = enabled
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!enabled)
        }
    }

    // Android 11+ Scoped Storage 系统批量彻底删除 Launcher
    private lateinit var deletePermissionLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingDeleteUris: List<String>? = null
    private var pendingPreDeletedUris: List<String> = emptyList()
    private var pendingDeleteCallback: ((Set<String>) -> Unit)? = null
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
            val preDeleted = pendingPreDeletedUris
            val callback = pendingDeleteCallback
            pendingDeleteUris = null
            pendingPreDeletedUris = emptyList()
            pendingDeleteCallback = null
            lifecycleScopeLaunch {
                try {
                    if (result.resultCode != Activity.RESULT_OK) {
                        if (preDeleted.isNotEmpty()) {
                            removeTrashRows(preDeleted)
                            inventoryRevision++
                        }
                        showDeleteMessage("已取消删除")
                        callback?.invoke(preDeleted.toSet())
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
                        val allDeleted = (preDeleted + removed).distinct()
                        finishPhysicalDelete(allDeleted)
                        callback?.invoke(allDeleted.toSet())
                    } else {
                        val allDeleted = (preDeleted + uris).distinct()
                        finishPhysicalDelete(allDeleted)
                        callback?.invoke(allDeleted.toSet())
                    }
                } finally {
                    deletionBusy = false
                }
            }
        }

        updatePermissionStatus()
        hasManageMediaSettingsEntry = resolveManageMediaIntent() != null

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
        val initialWindowBg = if (isInitialDark) 0xFF0C0D10.toInt() else 0xFFF7F7F5.toInt()
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
                var hasPromptedManageMedia by remember {
                    mutableStateOf(prefs.getBoolean("has_prompted_manage_media", false))
                }
                var showManageMediaPrompt by remember { mutableStateOf(false) }

                LaunchedEffect(
                    hasCompletedOnboarding,
                    hasPermission,
                    isPartialAccess,
                    canManageMedia,
                    hasPromptedManageMedia,
                    hasManageMediaSettingsEntry
                ) {
                    if (MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                            sdkInt = Build.VERSION.SDK_INT,
                            hasCompletedOnboarding = hasCompletedOnboarding,
                            hasFullPhotoPermission = hasPermission && !isPartialAccess,
                            canManageMedia = canManageMedia,
                            hasPromptedBefore = hasPromptedManageMedia,
                            hasSystemSettingsEntry = hasManageMediaSettingsEntry
                        )
                    ) {
                        delay(380L)
                        if (!canManageMedia && !hasPromptedManageMedia) {
                            showManageMediaPrompt = true
                        }
                    }
                }

                when {
                    !hasCompletedOnboarding -> {
                        OnboardingScreen(
                            hasPermission = hasPermission,
                            isReplayMode = false,
                            onComplete = {
                                hasCompletedOnboarding = true
                                showTutorial = false
                                prefs.edit().putBoolean("has_completed_onboarding", true).apply()
                                if (!hasPermission) {
                                    requestRequiredPermissions()
                                }
                            }
                        )
                    }
                    hasPermission -> {
                        // 重温手势指南时保持 MainContent 常驻底层，退回即可无缝回到设置页且不重载牌堆
                        MainContent(
                            currentThemeMode = themeMode,
                            onThemeModeChange = { newMode ->
                                themeMode = newMode
                                prefs.edit().putInt("theme_mode", newMode.code).apply()
                            },
                            onShowTutorial = { showTutorial = true }
                        )
                        if (showTutorial) {
                            BackHandler {
                                showTutorial = false
                            }
                            OnboardingScreen(
                                hasPermission = true,
                                isReplayMode = true,
                                onComplete = { showTutorial = false }
                            )
                        }
                    }
                    else -> {
                        PermissionScreen(onRequest = { requestRequiredPermissions() })
                    }
                }

                // 首次启动一次性「媒体管理」权限引导浮层（仅弹出一次，往后重开或升级均不再打扰）
                if (showManageMediaPrompt && hasCompletedOnboarding && hasPermission && !showTutorial) {
                    val isDarkPrompt = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val markPrompted = {
                        hasPromptedManageMedia = true
                        showManageMediaPrompt = false
                        prefs.edit().putBoolean("has_prompted_manage_media", true).apply()
                    }
                    ManageMediaPermissionSheet(
                        isDark = isDarkPrompt,
                        onDismiss = markPrompted,
                        onGrant = {
                            markPrompted()
                            openManageMediaSettings()
                        }
                    )
                }

                // 后台防窥遮罩层（白天纯白 / 黑夜纯黑，与系统多任务背景色完全统一，零割裂感）
                if (isAppPaused && isBackgroundBlurEnabled) {
                    val isDarkCurtain = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val curtainBg = if (isDarkCurtain) Color(0xFF0C0D10) else Color.White
                    val curtainFg = if (isDarkCurtain) Color(0xFFF4F4F6) else Color(0xFF141518)
                    val curtainSub = if (isDarkCurtain) Color(0xFF8E909A) else Color(0xFF6B6D75)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = curtainBg
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_mundru_logo),
                                    contentDescription = null,
                                    tint = curtainFg,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Mundru 隐私保护中",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = curtainSub
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
        // 拉起系统透明媒体删图授权（如已授 MANAGE_MEDIA 时 100ms 自动放行的 PermissionActivity）仅触发 onPause，
        // 绝不瞬间覆盖全屏「Mundru 隐私保护中」白/黑遮罩导致回收站页面闪动；真正退至后台时由 onStop 兜底保护。
        if (!suppressPrivacyCurtainForModal && pendingDeleteUris == null) {
            isAppPaused = true
        }
        applySecurityFlags(isBackgroundBlurEnabled)
    }

    override fun onStop() {
        super.onStop()
        isAppPaused = true
        // 退后台前立刻将缓冲中的快速左滑审阅落入应用级写队列，保证跨进程/重建零丢失
        reviewCoalescer.flushNow()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        suppressPrivacyCurtainForModal = false
        isAppPaused = false
        applySecurityFlags(isBackgroundBlurEnabled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 仅同步系统级 FLAG_SECURE 窗口标记；不在 onWindowFocusChanged(false) 盲目触发全屏 UI 遮罩，
        // 避免应用内弹层/对话框夺取窗口焦点时误闪全屏「隐私保护中」白屏遮罩。
        applySecurityFlags(isBackgroundBlurEnabled)
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
        canManageMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { MediaStore.canManageMedia(this) }.getOrDefault(false)
        } else {
            false
        }
    }

    private fun resolveManageMediaIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val scoped = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).apply {
            data = Uri.parse("package:$packageName")
        }
        if (scoped.resolveActivity(packageManager) != null) return scoped
        val general = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
        if (general.resolveActivity(packageManager) != null) return general
        return null
    }

    private fun openManageMediaSettings() {
        val intent = resolveManageMediaIntent()
        if (intent == null) {
            showDeleteMessage("当前系统未开放媒体管理权限页")
            return
        }
        runCatching { startActivity(intent) }
            .onFailure { showDeleteMessage("无法打开媒体管理权限设置") }
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
        suppressPrivacyCurtainForModal = true
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
        suppressPrivacyCurtainForModal = true
        try {
            val options = ActivityOptionsCompat.makeCustomAnimation(this, 0, 0)
            deletePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build(), options)
        } catch (e: Exception) {
            pendingDeleteUris = null
            suppressPrivacyCurtainForModal = false
            throw e
        }
    }

    private fun requestSystemDelete(
        uris: List<Uri>,
        deferSystemConsentMs: Long = 0L,
        onCompleted: ((Set<String>) -> Unit)? = null
    ) {
        if (uris.isEmpty() || deletionBusy) {
            onCompleted?.invoke(emptySet())
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            showDeleteMessage("授予存储写入权限后，请重新确认删除")
            onCompleted?.invoke(emptySet())
            return
        }

        deletionBusy = true
        val canVerifyMissing = hasPermission && !isPartialAccess
        val startedAtMs = SystemClock.elapsedRealtime()
        lifecycleScopeLaunch {
            var launchedSystemIntent = false
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

                suspend fun awaitReflowWindowIfNeeded() {
                    if (deferSystemConsentMs > 0L) {
                        val remaining = deferSystemConsentMs - (SystemClock.elapsedRealtime() - startedAtMs)
                        if (remaining > 0L) delay(remaining)
                    }
                }

                val skipped = targets.size - available.size - missing.size
                val strategy = MediaDeletePolicy.resolveStrategy(
                    sdkInt = Build.VERSION.SDK_INT,
                    canManageMedia = canManageMedia,
                    isPartialAccess = isPartialAccess
                )
                if (available.isEmpty()) {
                    awaitReflowWindowIfNeeded()
                    removeTrashRows(missing)
                    if (missing.isNotEmpty()) inventoryRevision++
                    showDeleteMessage(if (skipped > 0) "部分照片无法访问，未移除其回收站记录" else "已清理失效条目")
                    onCompleted?.invoke(missing.toSet())
                } else if (strategy == DeleteExecutionStrategy.DIRECT_MANAGE_MEDIA) {
                    if (skipped > 0) showDeleteMessage("有 $skipped 张未纳入本次删除，已保留")
                    val (removed, needsConsent, preparedSender) = withContext(Dispatchers.IO) {
                        val deleted = mutableListOf<String>()
                        val fallback = mutableListOf<Uri>()
                        for (uri in available) {
                            try {
                                if (contentResolver.delete(uri, null, null) > 0) {
                                    deleted.add(uri.toString())
                                } else {
                                    fallback.add(uri)
                                }
                            } catch (_: SecurityException) {
                                fallback.add(uri)
                            } catch (_: RuntimeException) {
                                // 保留未确认删除的记录，避免误清回收站条目
                            }
                        }
                        val sender = if (fallback.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            MediaStore.createDeleteRequest(contentResolver, fallback).intentSender
                        } else null
                        Triple(deleted, fallback, sender)
                    }
                    val preDeleted = (missing + removed).distinct()
                    // 等待桌面级 120fps 网格补位动效完全落定后再拉起静默系统放行，彻底杜绝中途抢占焦点与截断动画
                    awaitReflowWindowIfNeeded()
                    if (needsConsent.isEmpty() || preparedSender == null) {
                        finishPhysicalDelete(preDeleted)
                        onCompleted?.invoke(preDeleted.toSet())
                    } else {
                        updatePermissionStatus()
                        pendingPreDeletedUris = preDeleted
                        pendingDeleteCallback = onCompleted
                        launchedSystemIntent = true
                        confirmSystemDelete(needsConsent.map(Uri::toString), preparedSender)
                    }
                } else if (strategy == DeleteExecutionStrategy.SYSTEM_CONSENT_BATCH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (skipped > 0) showDeleteMessage("有 $skipped 张未纳入本次删除，已保留")
                    val request = withContext(Dispatchers.IO) {
                        MediaStore.createDeleteRequest(contentResolver, available)
                    }
                    pendingPreDeletedUris = missing
                    pendingDeleteCallback = onCompleted
                    launchedSystemIntent = true
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
                    val preDeleted = (missing + removed).distinct()
                    if (consent != null) {
                        if (available.size - removed.size > 1) showDeleteMessage("此系统需逐张授权，其余照片已保留")
                        consent?.let { (uri, sender) ->
                            pendingPreDeletedUris = preDeleted
                            pendingDeleteCallback = onCompleted
                            launchedSystemIntent = true
                            confirmSystemDelete(listOf(uri), sender)
                        }
                    } else {
                        awaitReflowWindowIfNeeded()
                        finishPhysicalDelete(preDeleted)
                        onCompleted?.invoke(preDeleted.toSet())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                showDeleteMessage("删除未完成，请重试。未确认删除的照片仍保留在回收站。")
                if (!launchedSystemIntent) {
                    onCompleted?.invoke(emptySet())
                }
            } finally {
                if (pendingDeleteUris == null) {
                    pendingPreDeletedUris = emptyList()
                    pendingDeleteCallback = null
                    deletionBusy = false
                }
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
        var sessionState by remember { mutableStateOf(DeckSessionState()) }
        var librarySnapshot by remember { mutableStateOf<List<PhotoItem>?>(null) }
        var librarySnapshotIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        val allLibraryPhotos = librarySnapshot.orEmpty()
        var isLoading by remember { mutableStateOf(true) }

        // 专清模式目标
        var selectedAlbumBucket by remember { mutableStateOf<String?>(null) }
        var selectedMonthBucket by remember { mutableStateOf<MonthBucket?>(null) }

        // 回收站与设置界面状态
        var activeScreen by remember { mutableStateOf(ActiveScreen.MAIN) }
        var settingsVisibleIdsState by remember { mutableStateOf(SettingsVisibleIdsState()) }
        val trashItemsFlow = remember { photoDao.getTrashItems() }.collectAsState(initial = emptyList())
        val trashItems = trashItemsFlow.value
        val trashPhotoIds = remember(trashItems) { trashItems.map { it.photoId } }
        val trashTotalBytes = remember(trashItems) { trashItems.sumOf { it.size } }

        // 图集专清弹层状态（随相册内存快照即时预聚合，< 1ms，点开零扫库卡顿）
        var showAlbumSheet by remember { mutableStateOf(false) }
        var availableAlbums by remember { mutableStateOf<List<AlbumInfo>>(emptyList()) }
        val cachedAlbums = remember(allLibraryPhotos) {
            AlbumManager.buildAlbumsFromPhotos(allLibraryPhotos)
        }

        // 模式选择液态玻璃弹层状态
        var showModeSelector by remember { mutableStateOf(false) }
        var pendingSheetJob by remember { mutableStateOf<Job?>(null) }
        var lastLibraryScanElapsedMs by remember { mutableLongStateOf(0L) }
        var prewarmLargePhotoIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var prewarmLargePhotoJob by remember { mutableStateOf<Job?>(null) }
        // 顶部柔和氛围流光状态提升至主舞台级，确保刷新/切模式奇点瞬间与第一张卡片同帧切齐，绝不慢半拍
        var stableAmbientPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        val lastAmbientSwitchMsRef = remember { longArrayOf(0L) }

        // 月份选择弹层状态（整型键预聚合，< 1ms）
        var showMonthSheet by remember { mutableStateOf(false) }
        var showBatchEmptySheet by remember { mutableStateOf(false) }
        var availableMonths by remember { mutableStateOf<List<MonthBucket>>(emptyList()) }
        val cachedMonths = remember(allLibraryPhotos) {
            samplingEngine.extractMonthBuckets(allLibraryPhotos)
        }

        // 统计数据状态
        var totalReviewedCount by remember { mutableLongStateOf(0L) }
        var isBatchInCooldown by remember { mutableStateOf(false) }

        // 沉浸大图共享几何源坐标与缩放焦点
        var viewingPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var viewingZoomFocus by remember { mutableStateOf<Offset?>(null) }
        var viewingSourceBounds by remember { mutableStateOf<Rect?>(null) }
        var reloadBatchJob by remember { mutableStateOf<Job?>(null) }
        var enrichBatchJob by remember { mutableStateOf<Job?>(null) }
        var dHashIndexJob by remember { mutableStateOf<Job?>(null) }
        var backgroundLibrarySyncJob by remember { mutableStateOf<Job?>(null) }

        // AGSL 液态光学漩涡换批转场控制器 + 顶部刷新按钮 Q 弹自旋角度
        val vortexState = rememberLiquidVortexState()
        val refreshIconRotation = remember { Animatable(0f) }

        // 三段式上滑回收与顶部液态玻璃垃圾桶实时联动状态（Draw-Phase 零重组驱动，非状态坐标引用杜绝视差平移逐帧重组）
        val trashTargetRef = remember { arrayOfNulls<Offset>(1) }
        val trashPullState = remember { mutableFloatStateOf(0f) }
        val trashThresholdState = remember { mutableStateOf(false) }
        var trashImpactTrigger by remember { mutableIntStateOf(0) }

        // 非状态高频交互时间戳：滑卡与换批瞬间刷新，零 Compose 重组，供后台 IO 任务判断是否处于空闲窗口
        val lastDeckInteractionMsRef = remember { longArrayOf(SystemClock.elapsedRealtime()) }
        fun markDeckInteraction() {
            lastDeckInteractionMsRef[0] = SystemClock.elapsedRealtime()
        }

        fun persistPhotoAction(write: suspend () -> Unit) {
            // 任何强顺序数据库动作（回收/恢复/同步/批量写入）前先刷出缓冲中的左滑审阅，严格维持 FIFO 顺序
            reviewCoalescer.flushNow()
            photoWrites.enqueue(write)
        }

        LaunchedEffect(activeScreen) {
            if (activeScreen == ActiveScreen.SETTINGS) {
                reviewCoalescer.flushNow()
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
            reviewCoalescer.flushNow()
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
                            lastViewedAt = existing?.lastViewedAt ?: 0L,
                            dHash = existing?.dHash
                        )
                        if (entity != existing) changed.add(entity)
                    }
                    if (changed.isNotEmpty()) photoDao.insertOrUpdateAll(changed)
                }
            }
            synchronized.join()
        }

        // 空闲门控等待器：快速连滑、漩涡转场或切出主界面时自动退让，彻底消除后台 IO/LOS 分配与滑卡争抢
        suspend fun waitForDeckIdle(requiredIdleMs: Long) {
            while (true) {
                if (vortexState.isActive || activeScreen != ActiveScreen.MAIN) {
                    delay(180L)
                    continue
                }
                val remain = DeckIdleWorkPolicy.remainingIdleDelayMs(
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    lastInteractionElapsedMs = lastDeckInteractionMsRef[0],
                    requiredIdleMs = requiredIdleMs
                )
                if (remain <= 0L) return
                delay(remain.coerceAtMost(300L))
            }
        }

        // 后台空闲门控补齐实况/HDR 角标与 dHash：
        // 1. 连滑期间零 FUSE 扫描、零 512KB LOS 分配、零单条 SQLite 事务洪峰；
        // 2. 停留在单张照片 >=450ms 时优先补齐当前顶卡；停滑 >=1.4s 后复用单块 64KB 缓冲扫描余下照片并单事务批量落库；
        // 3. 停滑 >=2.8s 后温和计算最多 8 张时间邻居 dHash 并单事务批量落库。
        fun launchBackgroundEnrichment(
            batch: List<PhotoItem>,
            activePhotos: List<PhotoItem>,
            dHashMap: Map<Long, Long>
        ) {
            enrichBatchJob?.cancel()
            enrichBatchJob = coroutineScope.launch(Dispatchers.IO) {
                val scratch = MotionPhotoParser.allocateScratchBuffer()
                val parsedIds = HashSet<Long>(batch.size * 2)

                // 阶段 A：用户停留在当前首图 >=450ms 时优先识别当前顶卡角标（兼顾快滑零开销与慢看即时出标）
                waitForDeckIdle(DeckIdleWorkPolicy.FRONT_METADATA_IDLE_MS)
                if (!isActive) return@launch
                val currentFront = withContext(Dispatchers.Main) { sessionState.currentPhoto }
                if (currentFront != null && !currentFront.isLivePhoto && currentFront.motionVideoLength == 0L) {
                    val mediaMeta = MotionPhotoParser.parse(
                        context = this@MainActivity,
                        uri = currentFront.uri,
                        displayName = currentFront.displayName,
                        scratchBuffer = scratch
                    )
                    parsedIds.add(currentFront.id)
                    val finalLength = if (mediaMeta.isMotionPhoto) mediaMeta.videoLength else -1L
                    val isHdr = mediaMeta.isHdr || currentFront.isHdr
                    val frontPatch = MotionHdrUpdate(
                        id = currentFront.id,
                        isLivePhoto = mediaMeta.isMotionPhoto,
                        offset = mediaMeta.videoOffset,
                        length = finalLength,
                        isHdr = isHdr
                    )
                    photoWrites.enqueue { photoDao.updateMotionAndHdrBatch(listOf(frontPatch)) }
                    if (mediaMeta.isMotionPhoto || isHdr) {
                        val enriched = currentFront.copy(
                            isLivePhoto = mediaMeta.isMotionPhoto,
                            motionVideoOffset = mediaMeta.videoOffset,
                            motionVideoLength = finalLength,
                            isHdr = isHdr
                        )
                        withContext(Dispatchers.Main) {
                            sessionState = sessionState.updatePhotoMetadata(enriched)
                        }
                    }
                }

                // 阶段 B：仅在完全停止连续滑卡 >=1.4s 后，逐张温和补齐本批剩余未解析照片，单事务批量落库
                val pendingPatches = ArrayList<MotionHdrUpdate>(batch.size)
                val uiEnriched = ArrayList<PhotoItem>()
                try {
                    for (photo in batch) {
                        if (!isActive) break
                        if (photo.id in parsedIds || photo.isLivePhoto || photo.motionVideoLength != 0L) continue
                        waitForDeckIdle(DeckIdleWorkPolicy.BATCH_METADATA_IDLE_MS)
                        if (!isActive) break

                        val mediaMeta = MotionPhotoParser.parse(
                            context = this@MainActivity,
                            uri = photo.uri,
                            displayName = photo.displayName,
                            scratchBuffer = scratch
                        )
                        val finalLength = if (mediaMeta.isMotionPhoto) mediaMeta.videoLength else -1L
                        val isHdr = mediaMeta.isHdr || photo.isHdr
                        pendingPatches.add(
                            MotionHdrUpdate(
                                id = photo.id,
                                isLivePhoto = mediaMeta.isMotionPhoto,
                                offset = mediaMeta.videoOffset,
                                length = finalLength,
                                isHdr = isHdr
                            )
                        )
                        if (mediaMeta.isMotionPhoto || isHdr) {
                            uiEnriched.add(
                                photo.copy(
                                    isLivePhoto = mediaMeta.isMotionPhoto,
                                    motionVideoOffset = mediaMeta.videoOffset,
                                    motionVideoLength = finalLength,
                                    isHdr = isHdr
                                )
                            )
                        }
                    }
                } finally {
                    if (pendingPatches.isNotEmpty()) {
                        val snapshot = ArrayList(pendingPatches)
                        photoWrites.enqueue { photoDao.updateMotionAndHdrBatch(snapshot) }
                    }
                }
                if (isActive && uiEnriched.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        var updated = sessionState
                        for (item in uiEnriched) {
                            updated = updated.updatePhotoMetadata(item)
                        }
                        sessionState = updated
                    }
                }
            }

            dHashIndexJob?.cancel()
            dHashIndexJob = coroutineScope.launch(Dispatchers.IO) {
                waitForDeckIdle(DeckIdleWorkPolicy.DHASH_INDEX_IDLE_MS)
                if (!isActive) return@launch
                val candidates = PerceptualHash.findTimeNeighborCandidates(
                    photos = activePhotos,
                    existingDHashes = dHashMap,
                    maxCandidates = DeckIdleWorkPolicy.DHASH_MAX_PER_IDLE_BURST
                )
                if (candidates.isEmpty()) return@launch
                val computed = ArrayList<Pair<Long, Long>>(candidates.size)
                try {
                    for (candidate in candidates) {
                        if (!isActive) break
                        waitForDeckIdle(DeckIdleWorkPolicy.DHASH_INDEX_IDLE_MS)
                        if (!isActive) break
                        val hash = PerceptualHash.computeFromUri(contentResolver, candidate.uri)
                        if (hash != null) {
                            computed.add(candidate.id to hash)
                        }
                        delay(DeckIdleWorkPolicy.DHASH_STEP_PAUSE_MS)
                    }
                } finally {
                    if (computed.isNotEmpty()) {
                        val snapshot = ArrayList(computed)
                        photoWrites.enqueue { photoDao.updateDHashBatch(snapshot) }
                    }
                }
            }
        }

        // 后台静默刷新 MediaStore 增量库（点刷新换批时零等待，同时保持相册库存实时新鲜）
        fun syncLibrarySilentlyInBackground() {
            backgroundLibrarySyncJob?.cancel()
            backgroundLibrarySyncJob = coroutineScope.launch(Dispatchers.IO) {
                val scanned = scanVisiblePhotos()
                if (!isActive) return@launch
                if (scanned.isNotEmpty()) {
                    syncPhotoLibrary(scanned)
                    val scannedIds = scanned.mapTo(HashSet(scanned.size)) { it.id }
                    withContext(Dispatchers.Main) {
                        librarySnapshot = scanned
                        librarySnapshotIds = scannedIds
                        lastLibraryScanElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        }

        // 极速抽样核心（~15-35ms 级）：零 MediaStore 阻塞；按模式与缩略缓存命中情况并行保障第 1、2 张可见卡驻留显存，
        // 彻底消除冷启切「大图专清」时第 2 张卡先黑框再慢变模糊，且超时仅停止等待而不强杀即将完成的解码。
        suspend fun computeNextBatchFast(
            mode: SamplingMode,
            batchSize: Int,
            albumBucket: String?,
            monthBucket: MonthBucket?,
            forceBlockingScan: Boolean,
            isVortexTransition: Boolean
        ): Triple<List<PhotoItem>, Boolean, Pair<List<PhotoItem>, Map<Long, Long>>> {
            val cached = librarySnapshot
            val libraryPhotos = if (forceBlockingScan || cached == null) {
                val scanned = scanVisiblePhotos()
                syncPhotoLibrary(scanned)
                val scannedIds = withContext(Dispatchers.IO) {
                    scanned.mapTo(HashSet(scanned.size)) { it.id }
                }
                librarySnapshot = scanned
                librarySnapshotIds = scannedIds
                lastLibraryScanElapsedMs = SystemClock.elapsedRealtime()
                scanned
            } else cached

            if (libraryPhotos.isEmpty()) {
                return Triple(emptyList(), false, emptyList<PhotoItem>() to emptyMap())
            }

            reviewCoalescer.flushNow()
            photoWrites.awaitPendingWrites()
            val loadedData = withContext(Dispatchers.IO) {
                val activeEntities = photoDao.getAllActivePhotosSync()
                val entityMap = activeEntities.associateBy { it.id }
                val historyMap = activeEntities.associate { it.id to it.lastViewedAt }
                val countMap = activeEntities.associate { it.id to it.viewCount }
                val hashMap = HashMap<Long, Long>(activeEntities.size / 4 + 16)
                for (entity in activeEntities) {
                    entity.dHash?.let { hashMap[entity.id] = it }
                }
                val filtered = libraryPhotos.mapNotNull { photo ->
                    entityMap[photo.id]?.let { cachedEntity ->
                        photo.copy(
                            isLivePhoto = cachedEntity.isLivePhoto,
                            motionVideoOffset = cachedEntity.motionVideoOffset,
                            motionVideoLength = cachedEntity.motionVideoLength,
                            isHdr = cachedEntity.isHdr || photo.isHdr
                        )
                    }
                }
                Triple(filtered, historyMap to countMap, hashMap)
            }

            val activePhotos = loadedData.first
            val viewHistoryMap = loadedData.second.first
            val viewCountMap = loadedData.second.second
            val dHashMap = loadedData.third

            val batch = withContext(Dispatchers.Default) {
                samplingEngine.generateBatch(
                    mode = mode,
                    allPhotos = activePhotos,
                    batchSize = batchSize,
                    viewHistoryMap = viewHistoryMap,
                    targetAlbumBucket = albumBucket,
                    targetMonthBucket = monthBucket,
                    viewCountMap = viewCountMap,
                    dHashMap = dHashMap
                )
            }

            // 首二张可见卡片预解码编排：
            // 1. 「大图专清」或首次冷启动：并行预解 batch[0] 与 batch[1]（均受 2 路并发池约束，超时仅止等不中断），
            //    确保首帧落地时第 1 张清晰画面、顶部模糊氛围背景与第 2 张柔和模糊景深卡三者同步到位，杜绝先黑卡再变模糊；
            // 2. 其余模式漩涡刷新：batch[0] 必保、batch[1] 命中 2.5ms 私有缩略缓存或非重型 PNG 时并行随解，batch[2] 轻图顺手预热。
            val imageLoader = Coil.imageLoader(this@MainActivity)
            val deckTargetWidthPx = (resources.displayMetrics.widthPixels - (48f * resources.displayMetrics.density).roundToInt())
                .coerceAtLeast(360)

            fun checkFastThumbDiskHit(photo: PhotoItem): Boolean {
                val ratio = deckCardAspectRatio(PhotoDisplayAspectCache.resolve(photo))
                val safeW = deckTargetWidthPx.coerceIn(360, 1280)
                val safeH = (safeW / ratio).roundToInt().coerceIn(240, 2100)
                return hasFastThumbOnDisk(
                    context = this@MainActivity,
                    photoId = photo.id,
                    dateModified = photo.dateModified,
                    sizeBytes = photo.size,
                    displayName = photo.displayName,
                    widthPx = safeW,
                    heightPx = safeH
                )
            }

            if (batch.isNotEmpty()) {
                val front = batch[0]
                val second = batch.getOrNull(1)
                val third = batch.getOrNull(2)

                val prewarmJob = prewarmLargePhotoJob
                val prewarmRelevant = prewarmJob != null && prewarmJob.isActive &&
                    (front.id in prewarmLargePhotoIds || (second != null && second.id in prewarmLargePhotoIds))
                if (prewarmRelevant) {
                    // 复用菜单展开期已起跑的预热协程，绝不中途取消白费已解码进度
                    withTimeoutOrNull(DeckIdleWorkPolicy.FRONT_PREWARM_TIMEOUT_MS) { prewarmJob.join() }
                } else {
                    prewarmJob?.cancel()
                }

                val frontKey = coil.memory.MemoryCache.Key(com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(front.id))
                val frontAlreadyCached = imageLoader.memoryCache?.get(frontKey) != null

                val secondKey = second?.let {
                    coil.memory.MemoryCache.Key(com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(it.id))
                }
                val secondAlreadyCached = secondKey != null && imageLoader.memoryCache?.get(secondKey) != null
                val secondTimeoutMs = if (second != null && !secondAlreadyCached) {
                    val hasFastThumb = withContext(Dispatchers.IO) { checkFastThumbDiskHit(second) }
                    DeckIdleWorkPolicy.secondCardPrewarmTimeoutMs(
                        mode = mode,
                        sizeBytes = second.size,
                        displayName = second.displayName,
                        hasFastThumbOnDisk = hasFastThumb,
                        isVortexTransition = isVortexTransition
                    )
                } else 0L

                val thirdKey = third?.let {
                    coil.memory.MemoryCache.Key(com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(it.id))
                }
                val thirdAlreadyCached = thirdKey != null && imageLoader.memoryCache?.get(thirdKey) != null
                val thirdTimeoutMs = if (third != null && !thirdAlreadyCached) {
                    val hasFastThumb = withContext(Dispatchers.IO) { checkFastThumbDiskHit(third) }
                    DeckIdleWorkPolicy.thirdCardPrewarmTimeoutMs(
                        mode = mode,
                        sizeBytes = third.size,
                        displayName = third.displayName,
                        hasFastThumbOnDisk = hasFastThumb,
                        isVortexTransition = isVortexTransition
                    )
                } else 0L

                // 全 5 种模式三层可见卡 (batch[0..2]) 同步并发预解码（超时仅止等、绝不强杀正在完成的解码）：
                // 常规照片 ~45~80ms 即可在 195ms 漩涡吸入期内全部落盘，彻底根治任意模式互切时先黑卡再慢变模糊。
                val frontTask = if (!frontAlreadyCached) {
                    coroutineScope.launch(Dispatchers.IO) {
                        imageLoader.execute(buildDeckCardImageRequest(this@MainActivity, front, deckTargetWidthPx))
                    }
                } else null
                val secondTask = if (second != null && secondTimeoutMs > 0L) {
                    coroutineScope.launch(Dispatchers.IO) {
                        imageLoader.execute(buildDeckCardImageRequest(this@MainActivity, second, deckTargetWidthPx))
                    }
                } else null
                val thirdTask = if (third != null && thirdTimeoutMs > 0L) {
                    coroutineScope.launch(Dispatchers.IO) {
                        imageLoader.execute(buildDeckCardImageRequest(this@MainActivity, third, deckTargetWidthPx))
                    }
                } else null

                coroutineScope {
                    val waitFront = frontTask?.let { task ->
                        launch { withTimeoutOrNull(DeckIdleWorkPolicy.FRONT_PREWARM_TIMEOUT_MS) { task.join() } }
                    }
                    val waitSecond = secondTask?.let { task ->
                        launch { withTimeoutOrNull(secondTimeoutMs) { task.join() } }
                    }
                    val waitThird = thirdTask?.let { task ->
                        launch { withTimeoutOrNull(thirdTimeoutMs) { task.join() } }
                    }
                    waitFront?.join()
                    waitSecond?.join()
                    waitThird?.join()
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
            val inCooldown = batch.isEmpty() && hasCandidatesForMode
            return Triple(batch, inCooldown, activePhotos to dHashMap)
        }

        // 重新加载批次：立即取消上一批次后台 IO 任务避免争抢 CPU/锁，再启用 AGSL 液态漩涡无缝换批
        fun reloadBatch(
            mode: SamplingMode,
            refreshLibrary: Boolean = false,
            useVortex: Boolean = librarySnapshot != null && !isLoading
        ) {
            markDeckInteraction()
            reloadBatchJob?.cancel()
            enrichBatchJob?.cancel()
            dHashIndexJob?.cancel()
            backgroundLibrarySyncJob?.cancel()
            val batchSize = currentBatchSize
            val albumBucket = selectedAlbumBucket
            val monthBucket = selectedMonthBucket

            if (useVortex && librarySnapshot != null) {
                // 零转圈极速换批：旧舞台旋卷吸入期间并发完成 ~25ms 抽样与首图独占预解码
                reloadBatchJob = coroutineScope.launch {
                    var preparedActive: List<PhotoItem> = emptyList()
                    var preparedHashes: Map<Long, Long> = emptyMap()
                    var preparedBatch: List<PhotoItem> = emptyList()

                    vortexState.runTransition(
                        onStartHaptic = { HapticFeedback.tick(this@MainActivity) },
                        onEmergeHaptic = { HapticFeedback.click(this@MainActivity) },
                        prepareNextBatch = {
                            val (batch, inCooldown, extras) = computeNextBatchFast(
                                mode = mode,
                                batchSize = batchSize,
                                albumBucket = albumBucket,
                                monthBucket = monthBucket,
                                forceBlockingScan = false,
                                isVortexTransition = true
                            )
                            preparedBatch = batch
                            preparedActive = extras.first
                            preparedHashes = extras.second
                            return@runTransition {
                                isBatchInCooldown = inCooldown
                                sessionState = DeckSessionState.startBatch(batch)
                                // 奇点同步切齐顶部模糊氛围背景（此时首图已驻留显存），与 295ms 漩涡旋展同拍渐显，彻底消除背景慢半拍
                                batch.firstOrNull()?.let { newFront ->
                                    lastAmbientSwitchMsRef[0] = 0L
                                    stableAmbientPhoto = newFront
                                }
                                isLoading = false
                            }
                        }
                    )

                    launchBackgroundEnrichment(preparedBatch, preparedActive, preparedHashes)
                    if (refreshLibrary) {
                        syncLibrarySilentlyInBackground()
                    }
                }
            } else {
                // 首次冷启动建库路径：同样移除了 15 张串行 MotionPhotoParser 阻塞，提速 ~800ms
                isLoading = true
                reloadBatchJob = coroutineScope.launch {
                    val cached = librarySnapshot
                    var shaderWarmup: Job? = null
                    if (cached == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val density = resources.displayMetrics.density
                        val app = applicationContext
                        shaderWarmup = launch(Dispatchers.Default) {
                            DeckShaderWarmup.prepare(app, density) { isActive }
                        }
                    }

                    val (batch, inCooldown, extras) = computeNextBatchFast(
                        mode = mode,
                        batchSize = batchSize,
                        albumBucket = albumBucket,
                        monthBucket = monthBucket,
                        forceBlockingScan = refreshLibrary || cached == null,
                        isVortexTransition = false
                    )
                    isBatchInCooldown = inCooldown
                    sessionState = DeckSessionState.startBatch(batch)
                    batch.firstOrNull()?.let { newFront ->
                        lastAmbientSwitchMsRef[0] = 0L
                        stableAmbientPhoto = newFront
                    }
                    shaderWarmup?.cancel()
                    isLoading = false

                    launchBackgroundEnrichment(batch, extras.first, extras.second)
                }
            }
        }

        var hasCompletedInitialLoad by remember { mutableStateOf(false) }
        LaunchedEffect(inventoryRevision) {
            if (!hasCompletedInitialLoad) {
                hasCompletedInitialLoad = true
                reloadBatch(currentMode, refreshLibrary = true)
            } else if (activeScreen == ActiveScreen.RECYCLE_BIN) {
                // 在回收站彻底删除后，绝不在底层主舞台触发全屏 AGSL 漩涡或强制重组导致回收站页面闪动；
                // 等待网格补位动效丝滑落定后，静默在 IO 线程刷新图库快照即可。
                delay(460L)
                syncLibrarySilentlyInBackground()
            } else if (activeScreen == ActiveScreen.MAIN && sessionState.isBatchComplete) {
                syncLibrarySilentlyInBackground()
            } else {
                reloadBatch(currentMode, refreshLibrary = true, useVortex = false)
            }
        }

        // 每进程冷启首屏落地后静默预热一次 AGSL 漩涡着色器管线（离屏 32×32 合成图，零 UI 阻塞），消除首次切模式同步编译卡顿
        LaunchedEffect(isLoading) {
            if (!isLoading && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val app = applicationContext
                withContext(Dispatchers.Default) {
                    delay(120L)
                    DeckShaderWarmup.prepareVortexShader(app)
                }
            }
        }

        // 展开模式菜单期间（手指正瞄准「大图专清」的 ~300-600ms 黄金窗口），静默串联预解码前 3 张最大体积未冷却照片进显存，
        // 确保冷启动切入「大图专清」时第 1 张清晰画面与第 2、3 张模糊景深卡同屏秒出、零黑卡过渡。
        LaunchedEffect(showModeSelector, allLibraryPhotos, currentMode) {
            if (!showModeSelector || currentMode == SamplingMode.LARGE_PHOTO_CLEANUP || allLibraryPhotos.isEmpty()) return@LaunchedEffect
            val deckTargetWidthPx = (resources.displayMetrics.widthPixels - (48f * resources.displayMetrics.density).roundToInt())
                .coerceAtLeast(360)
            val imageLoader = Coil.imageLoader(this@MainActivity)
            prewarmLargePhotoJob?.cancel()
            prewarmLargePhotoJob = coroutineScope.launch(Dispatchers.IO) {
                val activeEntities = photoDao.getAllActivePhotosSync()
                val activeIds = activeEntities.mapTo(HashSet(activeEntities.size)) { it.id }
                val historyMap = activeEntities.associate { it.id to it.lastViewedAt }
                val countMap = activeEntities.associate { it.id to it.viewCount }
                val activePhotos = allLibraryPhotos.filter { it.id in activeIds }
                val topCandidates = samplingEngine.generateBatch(
                    mode = SamplingMode.LARGE_PHOTO_CLEANUP,
                    allPhotos = activePhotos,
                    batchSize = DeckIdleWorkPolicy.MENU_LARGE_PREWARM_COUNT,
                    viewHistoryMap = historyMap,
                    viewCountMap = countMap
                )
                if (topCandidates.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    prewarmLargePhotoIds = topCandidates.mapTo(HashSet(topCandidates.size)) { it.id }
                }
                // 先并行为第 1、2 张大卡解码（精准契合 2 路解码池），再顺手预热第 3 张
                val firstTwo = topCandidates.take(2).filter { candidate ->
                    val key = coil.memory.MemoryCache.Key(com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(candidate.id))
                    imageLoader.memoryCache?.get(key) == null
                }
                if (firstTwo.isNotEmpty()) {
                    coroutineScope {
                        firstTwo.forEach { candidate ->
                            launch(Dispatchers.IO) {
                                imageLoader.execute(
                                    buildDeckCardImageRequest(this@MainActivity, candidate, deckTargetWidthPx)
                                )
                            }
                        }
                    }
                }
                val third = topCandidates.getOrNull(2)
                if (isActive && third != null) {
                    val key = coil.memory.MemoryCache.Key(com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(third.id))
                    if (imageLoader.memoryCache?.get(key) == null) {
                        imageLoader.execute(
                            buildDeckCardImageRequest(this@MainActivity, third, deckTargetWidthPx)
                        )
                    }
                }
            }
        }

        // 滚动前瞻单并发预解码（按批次常驻 + snapshotFlow.conflate）：
        // 绝不因 currentIndex 前进而中途打断正在进行的 BitmapFactory.decodeStream（彻底杜绝 InterruptedIOException 与重复解码），
        // 每次顺滑解完当前前瞻图入显存后再自动取最新索引推进，覆盖整批第 2~15 张零卡顿。
        val lookaheadBatchHeadId = sessionState.visibleDeck.firstOrNull()?.id
        LaunchedEffect(lookaheadBatchHeadId, isLoading, activeScreen) {
            if (isLoading || activeScreen != ActiveScreen.MAIN || lookaheadBatchHeadId == null) {
                return@LaunchedEffect
            }
            val imageLoader = Coil.imageLoader(this@MainActivity)
            val deckTargetWidthPx = (resources.displayMetrics.widthPixels - (48f * resources.displayMetrics.density).roundToInt())
                .coerceAtLeast(360)
            snapshotFlow {
                if (vortexState.isActive || sessionState.isBatchComplete) -1 else sessionState.currentIndex
            }
                .distinctUntilChanged()
                .conflate()
                .collect { idx ->
                    if (idx < 0) return@collect
                    val deck = sessionState.visibleDeck
                    val targetIndices = DeckIdleWorkPolicy.selectLookaheadIndices(
                        deckSize = deck.size,
                        currentIndex = idx,
                        isCachedAt = { i ->
                            val key = coil.memory.MemoryCache.Key(
                                com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(deck[i].id)
                            )
                            imageLoader.memoryCache?.get(key) != null
                        }
                    )
                    if (targetIndices.isEmpty()) return@collect
                    withContext(Dispatchers.IO) {
                        for (i in targetIndices) {
                            if (!isActive) break
                            val photo = deck[i]
                            val key = coil.memory.MemoryCache.Key(
                                com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(photo.id)
                            )
                            if (imageLoader.memoryCache?.get(key) == null) {
                                imageLoader.execute(
                                    buildDeckCardImageRequest(this@MainActivity, photo, deckTargetWidthPx)
                                )
                            }
                        }
                    }
                }
        }

        // 首屏静置后温和单纤程预热「大图专清」重型 PNG 进私有极速缩略缓存 (deck_thumbs_v1)，
        // 严格受空闲门控约束：一旦用户开始快速连滑立即退让，绝不与滑卡争抢 CPU / IO。
        LaunchedEffect(allLibraryPhotos, isLoading) {
            if (isLoading || allLibraryPhotos.isEmpty()) return@LaunchedEffect
            delay(900L)
            val deckTargetWidthPx = (resources.displayMetrics.widthPixels - (48f * resources.displayMetrics.density).roundToInt())
                .coerceAtLeast(360)
            val imageLoader = Coil.imageLoader(this@MainActivity)
            withContext(Dispatchers.IO) {
                val topHeavy = allLibraryPhotos
                    .asSequence()
                    .filter { it.hasKnownFileSize && it.size >= 2_500_000L && it.displayName.endsWith(".png", ignoreCase = true) }
                    .sortedByDescending { it.size }
                    .take(15)
                    .toList()
                for (photo in topHeavy) {
                    if (!isActive) break
                    waitForDeckIdle(DeckIdleWorkPolicy.HEAVY_PNG_PREWARM_IDLE_MS)
                    if (!isActive) break
                    val key = coil.memory.MemoryCache.Key(
                        com.lumina.cleaner.ui.deck.deckCardMemoryCacheKey(photo.id)
                    )
                    if (imageLoader.memoryCache?.get(key) == null) {
                        imageLoader.execute(
                            buildDeckCardImageRequest(this@MainActivity, photo, deckTargetWidthPx)
                        )
                        delay(80L)
                    }
                }
            }
        }

        // 全屏界面双向同频原生推拉转场（100% 绘制期零重组 + 静默常驻预热）：
        // 1. 主舞台常驻底层不卸载、不跳过绘制（静态 0 渲染帧、零 DisplayList 重录、零 HDR/广色域窗口切换变色）；
        // 2. 设置页与回收站在冷启首屏落地后静默离屏预热并常驻，隐藏期冻结统计/列表快照（主舞台滑卡零重组）并剥离无障碍语义，
        //    点击瞬间从 0.0% 无顿挫同频滑入，退场 0 延迟同频滑出，彻底消灭每次打开 60ms 重建卡顿与每帧重组掉帧。
        var settingsMounted by remember { mutableStateOf(false) }
        var binMounted by remember { mutableStateOf(false) }
        LaunchedEffect(isLoading) {
            if (!isLoading) {
                delay(650L)
                settingsMounted = true
                delay(350L)
                binMounted = true
            }
        }
        LaunchedEffect(activeScreen) {
            if (activeScreen == ActiveScreen.SETTINGS) settingsMounted = true
            if (activeScreen == ActiveScreen.RECYCLE_BIN) binMounted = true
        }

        val navEase = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
        val mainParallaxState = animateFloatAsState(
            targetValue = when (activeScreen) {
                ActiveScreen.SETTINGS -> 0.24f
                ActiveScreen.RECYCLE_BIN -> -0.24f
                ActiveScreen.MAIN -> 0f
            },
            animationSpec = tween(durationMillis = 290, easing = navEase),
            label = "main_parallax"
        )
        val settingsSlideState = animateFloatAsState(
            targetValue = if (activeScreen == ActiveScreen.SETTINGS) 1f else 0f,
            animationSpec = tween(durationMillis = 290, easing = navEase),
            label = "settings_slide"
        )
        val binSlideState = animateFloatAsState(
            targetValue = if (activeScreen == ActiveScreen.RECYCLE_BIN) 1f else 0f,
            animationSpec = tween(durationMillis = 290, easing = navEase),
            label = "bin_slide"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 常驻底层：主舞台（静止时 0 帧，进出全程纯 GPU 位移零重组、0 变色；被覆盖时屏蔽底层语义防误触读屏）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = size.width * mainParallaxState.value
                    }
                    .then(
                        if (activeScreen != ActiveScreen.MAIN) {
                            Modifier.clearAndSetSemantics { }
                        } else Modifier
                    )
            ) {
                run {
                    val isDarkTheme = when (currentThemeMode) {
                        AppThemeMode.DARK -> true
                        AppThemeMode.LIGHT -> false
                        AppThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                    }
                    val backdropState = rememberLiquidBackdropState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // 第 1 层：液态玻璃实时采样底层舞台（实时相片氛围流光 + 三层纵深卡堆，作为玻璃兄弟底层，0 递归）
                        // 漩涡换批期间暂停全屏离屏复录，避免顶部 5~6 枚玻璃胶囊重复执行全屏 AGSL 漩涡着色器；旋展结束立刻恢复实时采样
                        val bgColor = MaterialTheme.colorScheme.background
                        val ambientCrossfadeProgress = remember { Animatable(1f) }
                        var ambientSyncNonce by remember { mutableFloatStateOf(0f) }
                        val isAmbientAnimatingState = remember { mutableStateOf(false) }
                        val horizontalOnlySwipeState = remember { mutableStateOf(false) }
                        val ambientBlurEffect = remember {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                android.graphics.RenderEffect.createBlurEffect(
                                    64f, 64f, android.graphics.Shader.TileMode.CLAMP
                                ).asComposeRenderEffect()
                            } else null
                        }
                        val currentPhoto = sessionState.currentPhoto
                        LaunchedEffect(currentPhoto?.id, vortexState.isActive) {
                            if (currentPhoto != null && (!vortexState.isActive || stableAmbientPhoto == null)) {
                                if (stableAmbientPhoto?.id == currentPhoto.id) {
                                    // 漩涡奇点阶段已同步切换完氛围图；旋展收束瞬间递增 1 次信号刷新顶栏液态玻璃采样即归零静息
                                    lastAmbientSwitchMsRef[0] = SystemClock.elapsedRealtime()
                                    if (!vortexState.isActive) {
                                        ambientSyncNonce += 1f
                                    }
                                    return@LaunchedEffect
                                }
                                val now = SystemClock.elapsedRealtime()
                                val prevSwitch = lastAmbientSwitchMsRef[0]
                                val elapsed = if (prevSwitch > 0L) now - prevSwitch else Long.MAX_VALUE
                                lastAmbientSwitchMsRef[0] = now
                                // 慢速浏览 0ms 瞬切流光；极速连滑 (<240ms) 自动防抖 180ms，彻底杜绝 Crossfade 叠加 3~4 层全宽模糊
                                if (stableAmbientPhoto != null && DeckIdleWorkPolicy.shouldDebounceAmbientUpdate(elapsed)) {
                                    delay(DeckIdleWorkPolicy.RAPID_AMBIENT_DEBOUNCE_MS)
                                }
                                if (stableAmbientPhoto?.id != currentPhoto.id) {
                                    stableAmbientPhoto = currentPhoto
                                    isAmbientAnimatingState.value = true
                                    try {
                                        ambientCrossfadeProgress.snapTo(0f)
                                        ambientCrossfadeProgress.animateTo(
                                            targetValue = 1f,
                                            animationSpec = tween(durationMillis = 260)
                                        )
                                    } finally {
                                        isAmbientAnimatingState.value = false
                                    }
                                }
                            }
                        }
                        val auraTargetWidthPx = remember {
                            (resources.displayMetrics.widthPixels - (48f * resources.displayMetrics.density).roundToInt())
                                .coerceAtLeast(360)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .liquidBackdropSource(
                                    state = backdropState,
                                    enabled = !vortexState.isActive,
                                    pauseRecording = {
                                        DeckIdleWorkPolicy.shouldPauseBackdropRecording(
                                            isHorizontalSwipeOnly = horizontalOnlySwipeState.value,
                                            isTrashPullActive = trashPullState.floatValue > 0.005f,
                                            isAmbientCrossfading = isAmbientAnimatingState.value,
                                            isModeMenuOpen = showModeSelector,
                                            isVortexActive = vortexState.isActive
                                        )
                                    }
                                )
                                .background(bgColor)
                        ) {
                            // 跟随当前主图色彩实时流转的柔和漫射氛围光晕；直接复用主卡片 deck_card 显存键与缓存 RenderEffect（零每帧分配）
                            val ambientPhoto = stableAmbientPhoto
                            if (ambientPhoto != null && !isLoading) {
                                Crossfade(
                                    targetState = ambientPhoto,
                                    animationSpec = tween(durationMillis = 260),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(340.dp)
                                        .align(Alignment.TopCenter)
                                        .clipToBounds(),
                                    label = "ambient_glass_aura"
                                ) { photo ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clipToBounds()
                                    ) {
                                        val auraRequest = remember(photo.id, photo.uri, auraTargetWidthPx) {
                                            buildDeckCardImageRequest(this@MainActivity, photo, auraTargetWidthPx)
                                        }
                                        AsyncImage(
                                            model = auraRequest,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    scaleX = 1.18f
                                                    scaleY = 1.18f
                                                    alpha = if (isDarkTheme) 0.50f else 0.36f
                                                    renderEffect = ambientBlurEffect
                                                }
                                        )
                                        // 底部柔和羽化融入主背景，在 86% 处即完全衰减为纯背景色，彻底杜绝任何硬边缘
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colorStops = arrayOf(
                                                            0.00f to bgColor.copy(alpha = if (isDarkTheme) 0.14f else 0.16f),
                                                            0.42f to bgColor.copy(alpha = if (isDarkTheme) 0.58f else 0.60f),
                                                            0.84f to bgColor,
                                                            1.00f to bgColor
                                                        )
                                                    )
                                                )
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .stableStatusBarsPadding()
                                    .navigationBarsPadding()
                            ) {
                                // 为顶部悬浮液态玻璃操作栏与微型玻璃进度胶囊留出舒展空间
                                Spacer(modifier = Modifier.height(86.dp))

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

                                // 卡片主舞台（完整保留原版三层可见模糊景深栈 + 第四层无缝显露 + AGSL 液态漩涡无转圈换批）
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .liquidVortexEffect(vortexState, isDarkTheme),
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
                                        } else if (sessionState.isBatchComplete) {
                                            LaunchedEffect(Unit) {
                                                HapticFeedback.doublePulse(this@MainActivity)
                                            }
                                            // 批次完成结算卡片（点击开启新批次直接通过 AGSL 液态漩涡无转圈换入下一批）
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
                                                                reloadBatch(currentMode, refreshLibrary = false, useVortex = true)
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                            shape = RoundedCornerShape(12.dp)
                                                        ) {
                                                            Text("开启新批次")
                                                        }

                                                        if (trashItems.isNotEmpty()) {
                                                            Button(
                                                                onClick = {
                                                                    HapticFeedback.tick(this@MainActivity)
                                                                    if (canDirectDeleteMedia()) {
                                                                        showBatchEmptySheet = true
                                                                    } else {
                                                                        HapticFeedback.heavy(this@MainActivity)
                                                                        requestSystemDelete(trashItems.map { Uri.parse(it.uriString) })
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
                                        } else if (sessionState.visibleDeck.isEmpty()) {
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
                                                            "所有照片已妥善保留并进入阶梯冷却期，稍后会重新出现；你可以切换其他模式继续整理回忆"
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
                                                                reloadBatch(currentMode, refreshLibrary = true, useVortex = true)
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
                                                photos = sessionState.visibleDeck,
                                                currentIndex = sessionState.currentIndex,
                                                showFileSize = currentMode == SamplingMode.LARGE_PHOTO_CLEANUP,
                                                trashTargetInRootProvider = { trashTargetRef[0] },
                                                trashPullState = trashPullState,
                                                trashThresholdState = trashThresholdState,
                                                onTrashArcImpact = { trashImpactTrigger++ },
                                                backdropState = backdropState,
                                                horizontalOnlySwipeState = horizontalOnlySwipeState,
                                                ambientMotionSignal = { ambientCrossfadeProgress.value + ambientSyncNonce },
                                                vortexBloomProgress = { vortexState.bloomProgress.value },
                                                isVortexActive = vortexState.isActive,
                                                onClickPhoto = { photo, bounds ->
                                                    markDeckInteraction()
                                                    HapticFeedback.click(this@MainActivity)
                                                    viewingSourceBounds = bounds
                                                    viewingZoomFocus = null
                                                    viewingPhoto = photo
                                                },
                                                onDoubleClickPhoto = { photo, focus, bounds ->
                                                    markDeckInteraction()
                                                    HapticFeedback.click(this@MainActivity)
                                                    viewingSourceBounds = bounds
                                                    viewingZoomFocus = focus
                                                    viewingPhoto = photo
                                                },
                                                onSwipeLeft = { photo ->
                                                    markDeckInteraction()
                                                    sessionState = sessionState.keepCurrent()
                                                    reviewCoalescer.recordReview(photo.id, System.currentTimeMillis())
                                                },
                                                onSwipeRight = {
                                                    markDeckInteraction()
                                                    // 右滑仅返回上一张保留照片，绝不复活已进入回收站的照片
                                                    sessionState = sessionState.returnToPrevious()
                                                },
                                                onSwipeUp = { photo ->
                                                    markDeckInteraction()
                                                    sessionState = sessionState.trashPhoto(photo)
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

                                    // 漩涡高潮期浮现的极简液态水晶奇点光环（静止时自动不挂载，0 绘制开销）
                                    LiquidVortexPortalRing(
                                        state = vortexState,
                                        accentColor = MaterialTheme.colorScheme.primary,
                                        isDark = isDarkTheme
                                    )
                                }

                                // 底部微留白，释放纯净全屏手势视野
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        // 第 1.5 层：居中悬浮真·液态玻璃微型进度胶囊与专清副标签（实时折射氛围流光，展开菜单时自然位于菜单下方）
                        if (!isLoading && (selectedAlbumBucket != null || selectedMonthBucket != null || (sessionState.initialBatchCount > 0 && !sessionState.isBatchComplete))) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .stableStatusBarsPadding()
                                    .padding(top = 60.dp, start = 20.dp, end = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedAlbumBucket != null || selectedMonthBucket != null) {
                                    LiquidGlassSurface(
                                        backdrop = backdropState,
                                        isDark = isDarkTheme,
                                        cornerRadius = 14.dp,
                                        blurRadius = 14.dp,
                                        refractionStrengthDp = 9.dp,
                                        elevation = 4.dp,
                                        pressBounce = false
                                    ) {
                                        Text(
                                            text = selectedAlbumBucket?.let { "图集: $it" }
                                                ?: "月份: ${selectedMonthBucket?.displayName}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                if (sessionState.initialBatchCount > 0 && !sessionState.isBatchComplete) {
                                    LiquidGlassSurface(
                                        backdrop = backdropState,
                                        isDark = isDarkTheme,
                                        cornerRadius = 14.dp,
                                        blurRadius = 14.dp,
                                        refractionStrengthDp = 9.dp,
                                        elevation = 4.dp,
                                        pressBounce = false
                                    ) {
                                        Text(
                                            text = "${(sessionState.reviewedCount + 1).coerceAtMost(sessionState.initialBatchCount)} / ${sessionState.initialBatchCount}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 第 2 层：真·液态玻璃模式选择浮层（零黑色遮罩、零全屏模糊，静态一次预烘焙 120fps 开合）
                        ModeSelectionOverlay(
                            visible = showModeSelector,
                            currentMode = currentMode,
                            backdrop = backdropState,
                            isDark = isDarkTheme,
                            backdropCacheKey = if (showModeSelector) sessionState.currentPhoto?.id else null,
                            onSelectMode = { newMode ->
                                showModeSelector = false
                                pendingSheetJob?.cancel()
                                when (newMode) {
                                    SamplingMode.ALBUM_CLEANUP -> {
                                        pendingSheetJob = coroutineScope.launch {
                                            val albums = if (cachedAlbums.isNotEmpty()) {
                                                cachedAlbums
                                            } else {
                                                albumManager.getAvailableAlbums()
                                            }
                                            availableAlbums = albums
                                            // 等待顶部液态玻璃下拉菜单利落收起后再平滑升起图集抽屉，彻底消除双层遮罩碰撞与断层感
                                            delay(115L)
                                            showAlbumSheet = true
                                        }
                                    }
                                    SamplingMode.MONTH_CLEANUP -> {
                                        pendingSheetJob = coroutineScope.launch {
                                            availableMonths = cachedMonths
                                            delay(115L)
                                            showMonthSheet = true
                                        }
                                    }
                                    else -> {
                                        currentMode = newMode
                                        selectedAlbumBucket = null
                                        selectedMonthBucket = null
                                        val isLibraryStale = SystemClock.elapsedRealtime() - lastLibraryScanElapsedMs > 60_000L
                                        reloadBatch(
                                            newMode,
                                            refreshLibrary = newMode == SamplingMode.LARGE_PHOTO_CLEANUP && isLibraryStale
                                        )
                                    }
                                }
                            },
                            onDismiss = { showModeSelector = false }
                        )

                        // 第 3 层：顶部悬浮真·液态玻璃操作栏（始终位于最前层，再次点击 ModePill 原位即可丝滑收起菜单）
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .stableStatusBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧设置液态玻璃圆钮
                            LiquidGlassSurface(
                                backdrop = backdropState,
                                isDark = isDarkTheme,
                                cornerRadius = 20.dp,
                                blurRadius = 16.dp,
                                refractionStrengthDp = 11.dp,
                                onClick = {
                                    showModeSelector = false
                                    HapticFeedback.click(this@MainActivity)
                                    settingsMounted = true
                                    settingsVisibleIdsState = settingsVisibleIdsState.onEnterSettings()
                                    activeScreen = ActiveScreen.SETTINGS
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 居中真·液态玻璃模式胶囊（原位点击切换展开/丝滑回弹收起）
                            ModePill(
                                currentMode = currentMode,
                                onClick = {
                                    HapticFeedback.click(this@MainActivity)
                                    showModeSelector = !showModeSelector
                                },
                                backdrop = backdropState,
                                isDark = isDarkTheme,
                                isExpanded = showModeSelector
                            )

                            // 右侧刷新圆钮 + 三段式物理联动液态玻璃回收站胶囊
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LiquidGlassSurface(
                                    backdrop = backdropState,
                                    isDark = isDarkTheme,
                                    cornerRadius = 20.dp,
                                    blurRadius = 16.dp,
                                    refractionStrengthDp = 11.dp,
                                    onClick = {
                                        showModeSelector = false
                                        coroutineScope.launch {
                                            refreshIconRotation.animateTo(
                                                targetValue = refreshIconRotation.value + 360f,
                                                animationSpec = tween(
                                                    durationMillis = 460,
                                                    easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                                                )
                                            )
                                        }
                                        reloadBatch(currentMode, refreshLibrary = true, useVortex = true)
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = "Refresh",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .graphicsLayer { rotationZ = refreshIconRotation.value }
                                    )
                                }

                                LiquidTrashPill(
                                    trashCount = trashItems.size,
                                    backdrop = backdropState,
                                    isDark = isDarkTheme,
                                    trashPullState = trashPullState,
                                    trashThresholdState = trashThresholdState,
                                    impactTrigger = trashImpactTrigger,
                                    onCenterPositioned = { trashTargetRef[0] = it },
                                    onClick = {
                                        showModeSelector = false
                                        HapticFeedback.click(this@MainActivity)
                                        binMounted = true
                                        activeScreen = ActiveScreen.RECYCLE_BIN
                                    }
                                )
                            }
                        }

                        // 图集专清选择弹层
                        if (showAlbumSheet) {
                            AlbumBottomSheet(
                                albums = availableAlbums,
                                selectedAlbum = selectedAlbumBucket,
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
                                selectedMonth = selectedMonthBucket,
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

                        // 主界面批次完成卡「清空回收站」深浅双色微光玻璃确认浮层（彻底替代割裂的系统白框“拒绝/允许”）
                        if (showBatchEmptySheet) {
                            if (trashItems.isEmpty()) {
                                showBatchEmptySheet = false
                            } else {
                                val batchTrashSize = remember(trashTotalBytes) { formatFileSize(trashTotalBytes) }
                                TrashDeleteConfirmSheet(
                                    title = "清空回收站全部 ${trashItems.size} 张照片？",
                                    subtitle = "永久释放 $batchTrashSize，删除后不可恢复",
                                    confirmText = "彻底删除",
                                    isDark = isDarkTheme,
                                    onDismiss = { showBatchEmptySheet = false },
                                    onConfirm = {
                                        showBatchEmptySheet = false
                                        HapticFeedback.heavy(this@MainActivity)
                                        requestSystemDelete(trashItems.map { Uri.parse(it.uriString) })
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 顶层左侧滑入：设置页（常驻离屏预热 + 隐藏期参数快照冻结 + 严格可见 ID 交集扣减 + 绘制期零重组推拉）
            if (settingsMounted) {
                val isSettingsActive = activeScreen == ActiveScreen.SETTINGS
                LaunchedEffect(isSettingsActive, settingsVisibleIdsState.visitToken) {
                    if (!isSettingsActive) {
                        settingsVisibleIdsState = settingsVisibleIdsState.onExitSettings()
                        return@LaunchedEffect
                    }
                    val token = settingsVisibleIdsState.visitToken
                    delay(360L)
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        contentResolver.visibleImageIds().collect { ids ->
                            settingsVisibleIdsState = settingsVisibleIdsState.onLiveIdsLoaded(token, ids)
                        }
                    }
                }
                val activeCountCache = remember { ActiveLibraryCountCache() }
                var reviewedSnapshot by remember { mutableLongStateOf(0L) }
                var trashBytesSnapshot by remember { mutableLongStateOf(0L) }
                var totalPhotosSnapshot by remember { mutableIntStateOf(0) }
                if (isSettingsActive) {
                    reviewedSnapshot = totalReviewedCount
                    trashBytesSnapshot = trashTotalBytes
                    totalPhotosSnapshot = activeCountCache.getOrCompute(
                        visibleIds = settingsVisibleIdsState.effectiveVisibleIds(librarySnapshotIds),
                        trashIds = trashPhotoIds
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = settingsSlideState.value
                            translationX = -size.width * (1f - p)
                            alpha = if (p > 0.001f) 1f else 0f
                            clip = true
                        }
                        .then(
                            if (isSettingsActive) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {}
                                )
                            } else {
                                Modifier.clearAndSetSemantics { }
                            }
                        )
                ) {
                    SettingsScreen(
                        currentBatchSize = currentBatchSize,
                        onBatchSizeChange = { newSize ->
                            currentBatchSize = newSize
                            prefs.edit().putInt("batch_size", newSize).apply()
                            reloadBatch(currentMode, useVortex = false)
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
                        showManageMediaEntry = hasManageMediaSettingsEntry,
                        canManageMedia = canManageMedia,
                        onOpenManageMediaSettings = ::openManageMediaSettings,
                        currentThemeMode = currentThemeMode,
                        onThemeModeChange = onThemeModeChange,
                        totalReviewed = reviewedSnapshot,
                        trashBytes = trashBytesSnapshot,
                        totalPhotos = totalPhotosSnapshot,
                        onShowTutorial = {
                            onShowTutorial()
                        },
                        onBack = { activeScreen = ActiveScreen.MAIN },
                        isActive = isSettingsActive
                    )
                }
            }

            // 顶层右侧滑入：相册回收站（常驻离屏预热 + 隐藏期列表快照冻结 + 绘制期零重组推拉）
            if (binMounted) {
                val isBinActive = activeScreen == ActiveScreen.RECYCLE_BIN
                var binTrashSnapshot by remember { mutableStateOf<List<LocalTrashEntity>>(emptyList()) }
                if (isBinActive) {
                    binTrashSnapshot = trashItems
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = binSlideState.value
                            translationX = size.width * (1f - p)
                            alpha = if (p > 0.001f) 1f else 0f
                            clip = true
                        }
                        .then(
                            if (isBinActive) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {}
                                )
                            } else {
                                Modifier.clearAndSetSemantics { }
                            }
                        )
                ) {
                    RecycleBinScreen(
                        trashItems = binTrashSnapshot,
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
                        onPermanentDelete = { uris, deferMs, onCompleted ->
                            requestSystemDelete(
                                uris = uris,
                                deferSystemConsentMs = deferMs,
                                onCompleted = onCompleted
                            )
                        },
                        onBack = { activeScreen = ActiveScreen.MAIN },
                        isActive = isBinActive,
                        canDirectDelete = canDirectDeleteMedia()
                    )
                }
            }
        }

        // 沉浸式系统相册级高保真原图查看器（Frame-0 直显 + 卡片共享几何弹簧展开收拢）
        viewingPhoto?.let { photo ->
            PhotoViewerOverlay(
                photo = photo,
                initialZoomFocus = viewingZoomFocus,
                sourceBounds = viewingSourceBounds,
                onDismiss = {
                    viewingPhoto = null
                    viewingSourceBounds = null
                }
            )
        }
    }

    @Composable
    private fun ManageMediaPermissionSheet(
        isDark: Boolean,
        onDismiss: () -> Unit,
        onGrant: () -> Unit
    ) {
        MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SettingsIconBadge(
                    icon = Icons.Rounded.VerifiedUser,
                    tint = MaterialTheme.colorScheme.primary,
                    isDark = isDark,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "开启免弹窗彻底删除",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在系统设置中授予 Mundru「媒体管理」后，彻底删除照片不再弹出系统白色确认框；仅需开启一次，后续重开或升级应用均无需再次授权。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsGlassButton(
                        text = "暂不开启",
                        isPrimary = false,
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.tick(this@MainActivity)
                            dismissSmoothly(null)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SettingsGlassButton(
                        text = "前往开启",
                        isPrimary = true,
                        isDark = isDark,
                        onClick = {
                            HapticFeedback.click(this@MainActivity)
                            dismissSmoothly { onGrant() }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }

    @Composable
    private fun PermissionScreen(onRequest: () -> Unit) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isDark) Color(0xFF1A1B21) else Color.White)
                        .border(
                            width = 0.8.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(22.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mundru_logo),
                        contentDescription = "Mundru",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(44.dp)
                    )
                }
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = "需要本地相册权限",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Mundru 坚持 0 网络权限、纯本地离线运行。\n仅需本地照片读取权限即可开始整理回忆。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "授予相册权限",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
            MediaStore.Images.Media.ORIENTATION,
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
            val orientCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val itemUri = ContentUris.withAppendedId(uri, id)
                val rawWidth = cursor.getInt(wCol)
                val rawHeight = cursor.getInt(hCol)
                val orientation = if (orientCol >= 0 && !cursor.isNull(orientCol)) {
                    cursor.getInt(orientCol)
                } else {
                    0
                }
                val (displayWidth, displayHeight) = resolveDisplayDimensions(rawWidth, rawHeight, orientation)

                list.add(
                    PhotoItem(
                        id = id,
                        uri = itemUri,
                        displayName = cursor.getString(nameCol) ?: "IMG_$id",
                        dateModified = cursor.getLong(modCol) * 1000L,
                        dateTaken = cursor.getLong(takenCol),
                        size = cursor.getLong(sizeCol),
                        width = displayWidth,
                        height = displayHeight,
                        bucketName = cursor.getString(bucketCol)
                    )
                )
            }
        }
        return list
    }
}
