package com.lumina.cleaner.domain.album

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeletePolicyTest {

    @Test
    fun resolveStrategy_usesDirectManageMediaOnlyOnApi31PlusWithFullPermission() {
        assertEquals(
            DeleteExecutionStrategy.DIRECT_MANAGE_MEDIA,
            MediaDeletePolicy.resolveStrategy(sdkInt = 35, canManageMedia = true, isPartialAccess = false)
        )
        assertEquals(
            DeleteExecutionStrategy.DIRECT_MANAGE_MEDIA,
            MediaDeletePolicy.resolveStrategy(sdkInt = 31, canManageMedia = true, isPartialAccess = false)
        )
        // 部分照片授权或未授予 MANAGE_MEDIA 时必须回落至系统批量删除授权窗
        assertEquals(
            DeleteExecutionStrategy.SYSTEM_CONSENT_BATCH,
            MediaDeletePolicy.resolveStrategy(sdkInt = 35, canManageMedia = true, isPartialAccess = true)
        )
        assertEquals(
            DeleteExecutionStrategy.SYSTEM_CONSENT_BATCH,
            MediaDeletePolicy.resolveStrategy(sdkInt = 35, canManageMedia = false, isPartialAccess = false)
        )
        // Android 11 (API 30) 无 MANAGE_MEDIA，固定走系统批量授权窗
        assertEquals(
            DeleteExecutionStrategy.SYSTEM_CONSENT_BATCH,
            MediaDeletePolicy.resolveStrategy(sdkInt = 30, canManageMedia = true, isPartialAccess = false)
        )
        // Android 9~10 走旧版直删兼容路径
        assertEquals(
            DeleteExecutionStrategy.LEGACY_DIRECT_WITH_FALLBACK,
            MediaDeletePolicy.resolveStrategy(sdkInt = 29, canManageMedia = false, isPartialAccess = false)
        )
        assertEquals(
            DeleteExecutionStrategy.LEGACY_DIRECT_WITH_FALLBACK,
            MediaDeletePolicy.resolveStrategy(sdkInt = 28, canManageMedia = false, isPartialAccess = false)
        )
    }

    @Test
    fun oneTimeManageMediaPrompt_triggersOnlyOnceAfterOnboardingAndFullAccess() {
        // 首次启动完成引导并授予完整相册权限且尚未引导过 -> 触发一次性引导
        assertTrue(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = true,
                canManageMedia = false,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = true
            )
        )

        // 已弹出过一次（无论重开应用还是升级覆盖安装）-> 永不再弹
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = true,
                canManageMedia = false,
                hasPromptedBefore = true,
                hasSystemSettingsEntry = true
            )
        )

        // 已授予媒体管理权限 -> 不弹
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = true,
                canManageMedia = true,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = true
            )
        )

        // 未完成引导、仅部分照片授权、Android 11 以下或系统未开放该设置页 -> 不弹
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = false,
                hasFullPhotoPermission = true,
                canManageMedia = false,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = true
            )
        )
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = false,
                canManageMedia = false,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = true
            )
        )
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 30,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = true,
                canManageMedia = false,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = true
            )
        )
        assertFalse(
            MediaDeletePolicy.shouldShowOneTimeManageMediaPrompt(
                sdkInt = 35,
                hasCompletedOnboarding = true,
                hasFullPhotoPermission = true,
                canManageMedia = false,
                hasPromptedBefore = false,
                hasSystemSettingsEntry = false
            )
        )
    }
}
