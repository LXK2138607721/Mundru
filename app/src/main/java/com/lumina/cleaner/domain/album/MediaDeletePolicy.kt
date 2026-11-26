package com.lumina.cleaner.domain.album

/**
 * 物理删除执行策略：
 * - [DIRECT_MANAGE_MEDIA]：Android 12+ 已一次性授予 MANAGE_MEDIA 且非仅部分授权，直接 contentResolver.delete 零系统白框；
 * - [SYSTEM_CONSENT_BATCH]：Android 11+ 未授予 MANAGE_MEDIA，优雅降级至 MediaStore.createDeleteRequest 系统授权窗；
 * - [LEGACY_DIRECT_WITH_FALLBACK]：Android 9~10 直删并兼容 Android 10 RecoverableSecurityException。
 */
internal enum class DeleteExecutionStrategy {
    DIRECT_MANAGE_MEDIA,
    SYSTEM_CONSENT_BATCH,
    LEGACY_DIRECT_WITH_FALLBACK
}

/**
 * 纯逻辑媒体删除与一次性权限引导判定策略，便于全覆盖单元测试与跨版本安全兜底。
 */
internal object MediaDeletePolicy {

    fun resolveStrategy(
        sdkInt: Int,
        canManageMedia: Boolean,
        isPartialAccess: Boolean
    ): DeleteExecutionStrategy = when {
        sdkInt >= 31 && canManageMedia && !isPartialAccess -> DeleteExecutionStrategy.DIRECT_MANAGE_MEDIA
        sdkInt >= 30 -> DeleteExecutionStrategy.SYSTEM_CONSENT_BATCH
        else -> DeleteExecutionStrategy.LEGACY_DIRECT_WITH_FALLBACK
    }

    /**
     * 首次启动一次性「媒体管理」权限引导触发条件：
     * 1. Android 12+ (API 31+) 且系统存在可跳转的 ACTION_REQUEST_MANAGE_MEDIA 页面；
     * 2. 已完成手势引导并持有完整相册读取权限（非仅部分照片授权）；
     * 3. 当前尚未授予媒体管理权限；
     * 4. 从未弹过该一次性引导（无论用户上次开启还是暂不开启，后续重开与升级均绝不再次打扰）。
     */
    fun shouldShowOneTimeManageMediaPrompt(
        sdkInt: Int,
        hasCompletedOnboarding: Boolean,
        hasFullPhotoPermission: Boolean,
        canManageMedia: Boolean,
        hasPromptedBefore: Boolean,
        hasSystemSettingsEntry: Boolean
    ): Boolean = sdkInt >= 31 &&
        hasCompletedOnboarding &&
        hasFullPhotoPermission &&
        !canManageMedia &&
        !hasPromptedBefore &&
        hasSystemSettingsEntry
}
