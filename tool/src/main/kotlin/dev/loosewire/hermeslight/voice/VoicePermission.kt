/*
 * Copyright 2026 LooseWireDev
 */
package dev.loosewire.hermeslight.voice

import android.Manifest
import androidx.compose.runtime.Composable
import com.thelightphone.sdk.PermissionRequestLauncher
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.asKotlinResult

/**
 * The runtime microphone permission, through LightOS rather than a platform permission dialog.
 *
 * The SDK owns the request component; a tool cannot call `requestPermissions` itself. Any
 * denial, server block or failed service call is [false], so a caller never records optimistically.
 */
suspend fun hasRecordAudioPermission(): Boolean =
    checkPermission(Manifest.permission.RECORD_AUDIO)
        .asKotlinResult
        .map { it.permissionResult == LightServiceMethod.GetPermission.Result.Granted }
        .getOrDefault(false)

/**
 * Opens the LightOS permission sheet for RECORD_AUDIO; null when no activity is attached.
 *
 * `launch()` returns immediately and does not report the answer: re-check
 * [hasRecordAudioPermission] when the screen is next shown or when the user retries.
 */
@Composable
fun rememberRecordAudioPermissionLauncher(): PermissionRequestLauncher? =
    rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
