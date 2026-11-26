package com.lumina.cleaner.ui.trash

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.Parameters
import coil.size.Precision
import coil.size.Scale
import com.lumina.cleaner.core.database.LocalTrashEntity
import com.lumina.cleaner.core.model.PhotoItem
import com.lumina.cleaner.core.util.HapticFeedback
import com.lumina.cleaner.core.util.formatFileSize
import com.lumina.cleaner.ui.deck.enableFastThumbIfHeavy
import com.lumina.cleaner.ui.glass.LiquidGlassSurface
import com.lumina.cleaner.ui.glass.MundruBottomSheet
import com.lumina.cleaner.ui.glass.liquidBackdropSource
import com.lumina.cleaner.ui.glass.rememberLiquidBackdropState
import com.lumina.cleaner.ui.settings.SettingsGlassButton
import com.lumina.cleaner.ui.settings.SettingsIconBadge
import com.lumina.cleaner.ui.theme.stableStatusBarsPadding
import com.lumina.cleaner.ui.viewer.PhotoViewerOverlay
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RecycleBinConfirmAction {
    RESTORE,
    DELETE
}

private class TileReflowTrack {
    var initializedGen: Int = 0
    var startDeltaX: Float = 0f
    var startDeltaY: Float = 0f
    var enterFade: Boolean = false
    var staggerDelayMs: Long = 0L
    var zOrder: Float = 0f
    var pendingStart by mutableStateOf(false)
    var animJob: Job? = null
    val progress = Animatable(1f)
}

/**
 * Mundru 真·液态玻璃相册回收站 (RecycleBinScreen)
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦净 / 擦除」。
 *
 * v1.6.1 液态玻璃通体升级：
 * - 照片网格全屏贯穿顶底作为液态玻璃采样底层；
 * - 顶部悬浮返回圆钮、标题计数胶囊、全选胶囊与底部操作浮岛全部升级为实时折射 AGSL LiquidGlassSurface，
 *   上下滑动照片时画作色彩直接在玻璃顶栏与底栏中弯曲流动折射。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecycleBinScreen(
    trashItems: List<LocalTrashEntity>,
    onRestore: (List<Long>) -> Unit,
    onPermanentDelete: (uris: List<Uri>, deferSystemConsentMs: Long, onCompleted: (Set<String>) -> Unit) -> Unit,
    onBack: () -> Unit,
    onResolvePhotoItem: (suspend (Long) -> PhotoItem?)? = null,
    isActive: Boolean = true,
    canDirectDelete: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val binBackdrop = rememberLiquidBackdropState()
    val gridState = rememberLazyGridState()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var exitingActions by remember { mutableStateOf<Map<Long, RecycleBinConfirmAction>>(emptyMap()) }
    var optimisticallyRemovedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val displayItems = remember(trashItems, optimisticallyRemovedIds) {
        if (optimisticallyRemovedIds.isEmpty()) trashItems
        else trashItems.filterNot { it.photoId in optimisticallyRemovedIds }
    }
    val reflowMotionSignal = remember { Animatable(0f) }

    // 桌面卸载级屏幕空间坐标追踪器（100% 免疫首行删除与滚动夹紧导致的动画丢失）
    val gridContainerCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val liveTilePositions = remember { HashMap<Long, Pair<Float, Float>>(64) }
    val tileTracks = remember { HashMap<Long, TileReflowTrack>(64) }
    val tileRowPitchRef = remember { floatArrayOf(0f) }
    val gridViewportHeightRef = remember { floatArrayOf(2400f) }
    val genShiftCounter = remember { intArrayOf(0) }
    var reflowGen by remember { mutableIntStateOf(0) }
    var preReflowPositions by remember { mutableStateOf<Map<Long, Pair<Float, Float>>>(emptyMap()) }
    var removedBeforeMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    val lastObservedDbIdsRef = remember { arrayOf<List<Long>>(emptyList()) }

    fun pulseGlassReflow(durationMs: Int = 780) {
        coroutineScope.launch {
            reflowMotionSignal.snapTo(0f)
            reflowMotionSignal.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing)
            )
        }
    }

    fun snapshotVisualPositions(): Map<Long, Pair<Float, Float>> {
        val snap = HashMap<Long, Pair<Float, Float>>(liveTilePositions.size)
        for ((id, basePos) in liveTilePositions) {
            val track = tileTracks[id]
            if (track != null) {
                val p = if (track.pendingStart) 0f else track.progress.value
                val inv = (1f - p).coerceIn(0f, 1f)
                snap[id] = (basePos.first + track.startDeltaX * inv) to (basePos.second + track.startDeltaY * inv)
            } else {
                snap[id] = basePos
            }
        }
        return snap
    }

    fun triggerReflowRemoval(targetIds: Set<Long>, hideOptimistically: Boolean) {
        if (targetIds.isEmpty()) return
        val currentIds = displayItems.map { it.photoId }
        preReflowPositions = snapshotVisualPositions()
        removedBeforeMap = RecycleBinReflowPlanner.countRemovedBeforeEachSurvivor(currentIds, targetIds)
        genShiftCounter[0] = 0
        reflowGen += 1
        if (hideOptimistically) {
            optimisticallyRemovedIds = optimisticallyRemovedIds + targetIds
        }
        pulseGlassReflow(durationMs = 780)
    }

    LaunchedEffect(trashItems) {
        val liveIds = trashItems.mapTo(HashSet(trashItems.size)) { it.photoId }
        val prevDbIds = lastObservedDbIdsRef[0]
        // 兜底捕获未开启免弹窗直删路径下由系统弹窗返回触发的删除，同样赋予桌面级屏幕空间补位
        if (prevDbIds.isNotEmpty() && isActive) {
            val newlyRemovedFromDb = prevDbIds.filterTo(HashSet()) { it !in liveIds && it !in optimisticallyRemovedIds }
            if (newlyRemovedFromDb.isNotEmpty() && liveTilePositions.isNotEmpty()) {
                preReflowPositions = snapshotVisualPositions()
                removedBeforeMap = RecycleBinReflowPlanner.countRemovedBeforeEachSurvivor(prevDbIds, newlyRemovedFromDb)
                genShiftCounter[0] = 0
                reflowGen += 1
                pulseGlassReflow(durationMs = 780)
            }
        }
        lastObservedDbIdsRef[0] = trashItems.map { it.photoId }

        val filteredSelected = selectedIds.intersect(liveIds)
        if (filteredSelected.size != selectedIds.size) {
            selectedIds = filteredSelected
        }
        if (exitingActions.isNotEmpty()) {
            val filteredExiting = exitingActions.filterKeys { it in liveIds }
            if (filteredExiting.size != exitingActions.size) {
                exitingActions = filteredExiting
            }
        }
        if (optimisticallyRemovedIds.isNotEmpty()) {
            val stillInDb = optimisticallyRemovedIds.intersect(liveIds)
            if (stillInDb.size != optimisticallyRemovedIds.size) {
                optimisticallyRemovedIds = stillInDb
            }
        }
    }
    var previewPhoto by remember { mutableStateOf<PhotoItem?>(null) }
    var pendingConfirmAction by remember { mutableStateOf<RecycleBinConfirmAction?>(null) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            pendingConfirmAction = null
            previewPhoto = null
            selectedIds = emptySet()
            exitingActions = emptyMap()
            delay(310L)
            if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                gridState.scrollToItem(0)
            }
        }
    }

    DisposableEffect(binBackdrop, gridState) {
        binBackdrop.bindMotionSignal {
            gridState.firstVisibleItemIndex * 1000f +
                gridState.firstVisibleItemScrollOffset.toFloat() +
                reflowMotionSignal.value * 10f
        }
        onDispose { binBackdrop.bindMotionSignal(null) }
    }

    BackHandler(enabled = isActive, onBack = onBack)

    val totalBytes = remember(displayItems) { displayItems.sumOf { it.size } }
    val formattedSize = remember(totalBytes) { formatFileSize(totalBytes) }
    val gridSpacingPx = with(density) { 6.dp.toPx() }
    val reflowSpring = remember {
        spring<Float>(
            dampingRatio = 0.86f,
            stiffness = 225f,
            visibilityThreshold = 0.001f
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 第 1 层：全屏贯穿滚动网格（常驻不硬切卸载，杜绝清空闪白；
        // 采用自研屏幕空间坐标追踪 + 14ms 多米诺级联错峰补位引擎，彻底免疫整行删首行与滚动夹紧跳变）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidBackdropSource(binBackdrop)
                .background(MaterialTheme.colorScheme.background)
                .onGloballyPositioned { coords ->
                    gridContainerCoordsRef[0] = coords
                    if (coords.size.height > 0) {
                        gridViewportHeightRef[0] = coords.size.height.toFloat()
                    }
                }
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .stableStatusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(top = 74.dp, bottom = 104.dp, start = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(displayItems, key = { _, it -> it.photoId }) { index, item ->
                    val isSelected = item.photoId in selectedIds
                    val exitingAction = exitingActions[item.photoId]
                    val isExiting = exitingAction != null
                    val track = remember(item.photoId) {
                        tileTracks.getOrPut(item.photoId) { TileReflowTrack() }
                    }
                    var activeZOrder by remember(item.photoId) { mutableFloatStateOf(0f) }

                    DisposableEffect(item.photoId) {
                        onDispose {
                            liveTilePositions.remove(item.photoId)
                            track.animJob?.cancel()
                            track.animJob = null
                            track.pendingStart = false
                        }
                    }

                    val exitScaleState = animateFloatAsState(
                        targetValue = when (exitingAction) {
                            RecycleBinConfirmAction.DELETE -> 0.70f
                            RecycleBinConfirmAction.RESTORE -> 0.78f
                            null -> if (isSelected) 0.94f else 1f
                        },
                        animationSpec = if (isExiting) {
                            tween(
                                durationMillis = 155,
                                easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
                            )
                        } else {
                            spring(dampingRatio = 0.74f, stiffness = 480f)
                        },
                        label = "trash_tile_scale"
                    )
                    val exitAlphaState = animateFloatAsState(
                        targetValue = if (isExiting) 0f else 1f,
                        animationSpec = tween(
                            durationMillis = if (isExiting) 145 else 180,
                            easing = FastOutSlowInEasing
                        ),
                        label = "trash_tile_alpha"
                    )
                    val exitLiftState = animateFloatAsState(
                        targetValue = if (exitingAction == RecycleBinConfirmAction.RESTORE) -24f else 0f,
                        animationSpec = tween(
                            durationMillis = 155,
                            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                        ),
                        label = "trash_tile_lift"
                    )
                    val exitTiltState = animateFloatAsState(
                        targetValue = if (exitingAction == RecycleBinConfirmAction.DELETE) -2.4f else 0f,
                        animationSpec = tween(durationMillis = 155, easing = FastOutSlowInEasing),
                        label = "trash_tile_tilt"
                    )
                    val uri = remember(item.uriString) { Uri.parse(item.uriString) }
                    var loadFailed by remember(item.uriString) { mutableStateOf(false) }
                    val thumbRequest = remember(item.photoId, item.uriString, item.size) {
                        val params = Parameters.Builder()
                            .enableFastThumbIfHeavy(
                                photoId = item.photoId,
                                dateModified = item.deletedAt,
                                sizeBytes = item.size,
                                displayName = item.uriString,
                                widthPx = 320,
                                heightPx = 320
                            )
                            .build()
                        ImageRequest.Builder(context)
                            .data(uri)
                            .size(320, 320)
                            .scale(Scale.FILL)
                            .precision(Precision.INEXACT)
                            .memoryCacheKey("trash_thumb_${item.photoId}")
                            .parameters(params)
                            .crossfade(false)
                            .build()
                    }
                    val tileShape = remember { RoundedCornerShape(12.dp) }

                    Box(
                        modifier = Modifier
                            .zIndex(activeZOrder)
                            .aspectRatio(1f)
                            .onGloballyPositioned { coords ->
                                val parent = gridContainerCoordsRef[0]
                                if (parent != null && parent.isAttached && coords.isAttached) {
                                    val rel = parent.localPositionOf(coords, Offset.Zero)
                                    val posPair = rel.x to rel.y
                                    if (coords.size.height > 0) {
                                        tileRowPitchRef[0] = coords.size.height.toFloat() + gridSpacingPx
                                    }
                                    val gen = reflowGen
                                    if (gen > 0 && track.initializedGen != gen) {
                                        track.initializedGen = gen
                                        val oldPos = preReflowPositions[item.photoId]
                                        val removedBefore = removedBeforeMap[item.photoId] ?: 0
                                        val plan = RecycleBinReflowPlanner.planForTile(
                                            newIndex = index,
                                            oldPosPx = oldPos,
                                            newPosPx = posPair,
                                            rowPitchPx = tileRowPitchRef[0],
                                            removedBeforeNewCount = removedBefore,
                                            columns = 3,
                                            shiftRank = genShiftCounter[0],
                                            visibleViewportHeightPx = gridViewportHeightRef[0]
                                        )
                                        if (plan.hasMotion) {
                                            genShiftCounter[0] += 1
                                            track.startDeltaX = plan.deltaX
                                            track.startDeltaY = plan.deltaY
                                            track.enterFade = plan.isEnteringFromBottom
                                            track.staggerDelayMs = plan.staggerDelayMs
                                            track.zOrder = plan.zOrder
                                            track.pendingStart = true
                                            track.animJob?.cancel()
                                            track.animJob = coroutineScope.launch {
                                                activeZOrder = plan.zOrder
                                                track.progress.snapTo(0f)
                                                track.pendingStart = false
                                                if (plan.staggerDelayMs > 0L) {
                                                    delay(plan.staggerDelayMs)
                                                }
                                                track.progress.animateTo(
                                                    targetValue = 1f,
                                                    animationSpec = reflowSpring
                                                )
                                                activeZOrder = 0f
                                            }
                                        }
                                    }
                                    liveTilePositions[item.photoId] = posPair
                                }
                            }
                            .graphicsLayer {
                                // 无条件读取 track.progress.value 与 track.pendingStart，确保首帧即以旧坐标绘制、零 1 帧闪跳
                                val animatedProgress = track.progress.value
                                val isPending = track.pendingStart
                                val p = if (isPending) 0f else animatedProgress
                                val inv = (1f - p).coerceIn(0f, 1f)
                                val reflowX = track.startDeltaX * inv
                                val reflowY = track.startDeltaY * inv
                                val airScale = if (inv > 0.001f && !track.enterFade) {
                                    1f - 0.036f * sin(p * PI).toFloat()
                                } else if (track.enterFade && inv > 0.001f) {
                                    0.94f + 0.06f * p
                                } else {
                                    1f
                                }
                                val enterAlpha = if (track.enterFade && inv > 0.001f) {
                                    (0.28f + 0.72f * p).coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                val s = exitScaleState.value * airScale
                                scaleX = s
                                scaleY = s
                                alpha = exitAlphaState.value * enterAlpha
                                translationX = reflowX
                                translationY = reflowY + exitLiftState.value * this.density
                                rotationZ = exitTiltState.value
                            }
                            .clip(tileShape)
                            .then(
                                if (isSelected && !isExiting) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = tileShape
                                    )
                                } else Modifier
                            )
                            .combinedClickable(
                                enabled = !isExiting,
                                onClick = {
                                    HapticFeedback.click(context)
                                    coroutineScope.launch {
                                        val resolved = onResolvePhotoItem?.invoke(item.photoId)
                                        previewPhoto = resolved ?: item.toPhotoItem()
                                    }
                                },
                                onLongClick = {
                                    HapticFeedback.click(context)
                                    selectedIds = if (isSelected) selectedIds - item.photoId else selectedIds + item.photoId
                                }
                            )
                    ) {
                        if (!loadFailed) {
                            AsyncImage(
                                model = thumbRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onError = { loadFailed = true },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.BrokenImage,
                                        contentDescription = "无法读取",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "无法读取原图",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 右上角独立 40dp 勾选触控热区
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(40.dp)
                                .clickable(enabled = !isExiting) {
                                    HapticFeedback.tick(context)
                                    selectedIds = if (isSelected) selectedIds - item.photoId else selectedIds + item.photoId
                                },
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Box(modifier = Modifier.padding(6.dp)) {
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.35f),
                                        modifier = Modifier.size(20.dp)
                                    ) { }
                                }
                            }
                        }
                    }
                }
            }

            // 柔和渐显空态覆盖层（最后一张消失后平滑浮现，杜绝硬切白闪）
            AnimatedVisibility(
                visible = displayItems.isEmpty(),
                enter = fadeIn(tween(durationMillis = 280, delayMillis = 120, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(dampingRatio = 0.84f, stiffness = 360f)
                    ),
                exit = fadeOut(tween(durationMillis = 140)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "回收站是空的",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "整理时上滑删除的照片会暂存在这里",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 第 2 层：顶部悬浮真·液态玻璃导航栏
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .stableStatusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LiquidGlassSurface(
                backdrop = binBackdrop,
                isDark = isDark,
                cornerRadius = 21.dp,
                blurRadius = 18.dp,
                refractionStrengthDp = 12.dp,
                onClick = {
                    HapticFeedback.click(context)
                    onBack()
                },
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            LiquidGlassSurface(
                backdrop = binBackdrop,
                isDark = isDark,
                cornerRadius = 22.dp,
                blurRadius = 18.dp,
                refractionStrengthDp = 12.dp,
                pressBounce = false,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "相册回收站",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AnimatedContent(
                        targetState = "${displayItems.size} 张照片 · 共 $formattedSize",
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "trash_header_summary"
                    ) { summary ->
                        Text(
                            text = summary,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LiquidGlassSurface(
                backdrop = binBackdrop,
                isDark = isDark,
                cornerRadius = 21.dp,
                blurRadius = 18.dp,
                refractionStrengthDp = 12.dp,
                onClick = {
                    HapticFeedback.click(context)
                    selectedIds = if (selectedIds.size == displayItems.size) {
                        emptySet()
                    } else {
                        displayItems.mapTo(HashSet(displayItems.size)) { it.photoId }
                    }
                }
            ) {
                Text(
                    text = if (selectedIds.size == displayItems.size && displayItems.isNotEmpty()) "取消" else "全选",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        // 第 3 层：底部悬浮真·液态玻璃操作浮岛（滚过与补位时实时弯曲折射下方画作，清空时平滑滑出零闪动）
        AnimatedVisibility(
            visible = displayItems.isNotEmpty(),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.84f, stiffness = 400f)
            ) + fadeIn(tween(200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(170)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .fillMaxWidth()
        ) {
            LiquidGlassSurface(
                backdrop = binBackdrop,
                isDark = isDark,
                cornerRadius = 26.dp,
                blurRadius = 22.dp,
                refractionStrengthDp = 14.dp,
                tintColor = if (isDark) Color(0x78181A22) else Color(0x9CFFFFFF),
                elevation = if (isDark) 12.dp else 14.dp,
                pressBounce = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsGlassButton(
                        text = if (selectedIds.isEmpty()) "恢复全部" else "恢复 (${selectedIds.size})",
                        isPrimary = false,
                        isDark = isDark,
                        leadingIcon = Icons.Rounded.Restore,
                        onClick = {
                            HapticFeedback.click(context)
                            pendingConfirmAction = RecycleBinConfirmAction.RESTORE
                        },
                        modifier = Modifier.weight(1f)
                    )

                    SettingsGlassButton(
                        text = if (selectedIds.isEmpty()) "彻底删除" else "删除 (${selectedIds.size})",
                        isPrimary = false,
                        isDanger = true,
                        isDark = isDark,
                        leadingIcon = Icons.Rounded.DeleteForever,
                        onClick = {
                            HapticFeedback.tick(context)
                            pendingConfirmAction = RecycleBinConfirmAction.DELETE
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 统一瑞士黑白曜石微光玻璃确认浮层（彻底删除 + 恢复全部/所选防误触二次确认）
        pendingConfirmAction?.let { action ->
            val targets = if (selectedIds.isEmpty()) {
                displayItems
            } else {
                displayItems.filter { it.photoId in selectedIds }
            }
            if (targets.isEmpty()) {
                pendingConfirmAction = null
            } else {
                RecycleBinConfirmSheet(
                    action = action,
                    targets = targets,
                    isAllItems = selectedIds.isEmpty() || selectedIds.size == displayItems.size,
                    isDark = isDark,
                    canDirectDelete = canDirectDelete,
                    onDismiss = { pendingConfirmAction = null },
                    onConfirm = {
                        pendingConfirmAction = null
                        val targetIds = targets.map { it.photoId }
                        val targetIdSet = targetIds.toSet()
                        val targetIdByUri = targets.associate { it.uriString to it.photoId }
                        val targetUris = targets.map { Uri.parse(it.uriString) }
                        when (action) {
                            RecycleBinConfirmAction.RESTORE -> {
                                HapticFeedback.click(context)
                                selectedIds = emptySet()
                                exitingActions = exitingActions + targetIds.associateWith { RecycleBinConfirmAction.RESTORE }
                                pulseGlassReflow(durationMs = 820)
                                coroutineScope.launch {
                                    // 第 1 拍轻盈上浮缩散 140ms 后立即冻结视口快照并启动第 2 拍 14ms 多米诺桌面级自动补位
                                    delay(140L)
                                    triggerReflowRemoval(targetIdSet, hideOptimistically = true)
                                    exitingActions = exitingActions - targetIdSet
                                    onRestore(targetIds)
                                }
                            }
                            RecycleBinConfirmAction.DELETE -> {
                                HapticFeedback.heavy(context)
                                if (canDirectDelete) {
                                    selectedIds = emptySet()
                                    exitingActions = exitingActions + targetIds.associateWith { RecycleBinConfirmAction.DELETE }
                                    pulseGlassReflow(durationMs = 840)
                                    // 立即在 MainActivity 作用域启动后台 IO 预检与静默删图预构建，
                                    // 并在 640ms（整套两拍级联补位 100% 丝滑落定后）才触发静默系统放行，零白洞、零抢焦截断
                                    onPermanentDelete(targetUris, 640L) { deletedUriSet ->
                                        val notDeletedIds = targetIdByUri
                                            .filterKeys { it !in deletedUriSet }
                                            .values
                                            .toSet()
                                        if (notDeletedIds.isNotEmpty()) {
                                            optimisticallyRemovedIds = optimisticallyRemovedIds - notDeletedIds
                                            exitingActions = exitingActions - notDeletedIds
                                        }
                                    }
                                    coroutineScope.launch {
                                        // 第 1 拍缩散微倾淡出 140ms 后零停顿衔接第 2 拍 120fps 桌面级多米诺自动补位
                                        delay(140L)
                                        triggerReflowRemoval(targetIdSet, hideOptimistically = true)
                                        exitingActions = exitingActions - targetIdSet
                                    }
                                } else {
                                    pulseGlassReflow(durationMs = 760)
                                    onPermanentDelete(targetUris, 0L) { }
                                }
                            }
                        }
                    }
                )
            }
        }

        // 大图沉浸预览查看器
        if (previewPhoto != null) {
            PhotoViewerOverlay(
                photo = previewPhoto!!,
                onDismiss = { previewPhoto = null }
            )
        }
    }
}

@Composable
private fun RecycleBinConfirmSheet(
    action: RecycleBinConfirmAction,
    targets: List<LocalTrashEntity>,
    isAllItems: Boolean,
    isDark: Boolean,
    canDirectDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isDelete = action == RecycleBinConfirmAction.DELETE
    val targetBytes = remember(targets) { targets.sumOf { it.size } }
    val sizeText = remember(targetBytes) { formatFileSize(targetBytes) }

    val titleText = when {
        isDelete && isAllItems -> "彻底删除全部 ${targets.size} 张照片？"
        isDelete -> "彻底删除选中的 ${targets.size} 张照片？"
        isAllItems -> "恢复全部 ${targets.size} 张照片？"
        else -> "恢复选中的 ${targets.size} 张照片？"
    }
    val noteText = when {
        isDelete && canDirectDelete -> "永久释放 $sizeText，删除后不可恢复"
        isDelete -> "释放 $sizeText，确认后交由系统完成删除"
        isAllItems -> "当前回收站筛选清单将被清空"
        else -> "所选照片将回到相册，其余保持待删"
    }

    if (isDelete) {
        TrashDeleteConfirmSheet(
            title = titleText,
            subtitle = noteText,
            confirmText = "彻底删除",
            isDark = isDark,
            onDismiss = onDismiss,
            onConfirm = onConfirm
        )
        return
    }

    val context = LocalContext.current
    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SettingsIconBadge(
                icon = Icons.Rounded.Restore,
                tint = MaterialTheme.colorScheme.primary,
                isDark = isDark,
                modifier = Modifier.size(42.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = titleText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = noteText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsGlassButton(
                    text = "取消",
                    isPrimary = false,
                    isDark = isDark,
                    onClick = {
                        HapticFeedback.tick(context)
                        dismissSmoothly(null)
                    },
                    modifier = Modifier.weight(1f)
                )
                SettingsGlassButton(
                    text = "确认恢复",
                    isPrimary = true,
                    isDanger = false,
                    isDark = isDark,
                    onClick = { dismissSmoothly { onConfirm() } },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * 全站统一的瑞士黑白曜石 / 磨砂宣瓷微光玻璃彻底删除确认浮层 (TrashDeleteConfirmSheet)
 *
 * 供「回收站 → 彻底删除」与「主界面批次完成卡 → 清空回收站」共用：
 * 100% 跟随深浅双主题自动切换曜石黑与宣瓷白材质，彻底取代割裂的纯白系统“拒绝/允许”丑弹窗。
 */
@Composable
internal fun TrashDeleteConfirmSheet(
    title: String,
    subtitle: String,
    confirmText: String = "彻底删除",
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    MundruBottomSheet(onDismiss = onDismiss) { dismissSmoothly ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SettingsIconBadge(
                icon = Icons.Rounded.DeleteForever,
                tint = MaterialTheme.colorScheme.error,
                isDark = isDark,
                modifier = Modifier.size(44.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsGlassButton(
                    text = "取消",
                    isPrimary = false,
                    isDark = isDark,
                    onClick = {
                        HapticFeedback.tick(context)
                        dismissSmoothly(null)
                    },
                    modifier = Modifier.weight(1f)
                )
                SettingsGlassButton(
                    text = confirmText,
                    isPrimary = false,
                    isDanger = true,
                    isDark = isDark,
                    onClick = { dismissSmoothly { onConfirm() } },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun LocalTrashEntity.toPhotoItem(): PhotoItem = PhotoItem(
    id = photoId,
    uri = Uri.parse(uriString),
    displayName = "已删除照片 #$photoId",
    dateModified = deletedAt,
    dateTaken = deletedAt,
    size = size,
    width = 0,
    height = 0,
    bucketName = "相册回收站"
)
