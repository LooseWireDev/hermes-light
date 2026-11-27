package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.consumeBackKey

private enum class SetupRow {
    Endpoint,
    TokenMode,
    PasswordMode,
    Token,
    Username,
    Password,
    Test,
}

private enum class SetupEditor {
    Endpoint,
    Token,
    Username,
    Password,
}

private fun rowsFor(mode: AuthModeUi): List<SetupRow> =
    if (mode == AuthModeUi.Token) {
        listOf(
            SetupRow.Endpoint,
            SetupRow.TokenMode,
            SetupRow.PasswordMode,
            SetupRow.Token,
            SetupRow.Test,
        )
    } else {
        listOf(
            SetupRow.Endpoint,
            SetupRow.TokenMode,
            SetupRow.PasswordMode,
            SetupRow.Username,
            SetupRow.Password,
            SetupRow.Test,
        )
    }

private fun masked(value: String): String = if (value.isEmpty()) "" else "•".repeat(value.length.coerceAtMost(48))

/**
 * Setup: endpoint, token or password mode, provider status and a connection test. M0 does not
 * submit credentials or persist a session; it only proves the link and hands drafts back to the
 * application through callbacks.
 */
@Composable
fun SetupScreen(
    state: SetupUi,
    actions: SetupActions,
) {
    val colors = LightThemeTokens.colors
    val rows = remember(state.mode) { rowsFor(state.mode) }
    val listState = rememberLazyListState()
    var focusIndex by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SetupEditor?>(null) }

    LaunchedEffect(rows.size) {
        focusIndex = focusIndex.coerceIn(0, rows.lastIndex)
    }

    LaunchedEffect(focusIndex) {
        if (focusIndex in rows.indices) listState.animateScrollToItem(focusIndex)
    }

    fun submit(
        editor: SetupEditor,
        value: String,
    ) {
        when (editor) {
            SetupEditor.Endpoint -> actions.onEndpointChange(value.trim())
            SetupEditor.Token -> actions.onTokenChange(value)
            SetupEditor.Username -> actions.onUsernameChange(value)
            SetupEditor.Password -> actions.onPasswordChange(value)
        }
        editing = null
    }

    val editor = editing
    if (editor != null) {
        val initial =
            when (editor) {
                SetupEditor.Endpoint -> state.endpoint
                SetupEditor.Token -> state.token
                SetupEditor.Username -> state.username
                SetupEditor.Password -> state.password
            }
        val title =
            when (editor) {
                SetupEditor.Endpoint -> "Server URL"
                SetupEditor.Token -> "Session token"
                SetupEditor.Username -> "Username"
                SetupEditor.Password -> "Password"
            }
        key(editor) {
            val textState = rememberTextFieldState(initial)
            val keyboardOptions = rememberKeyboardOptions()
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(colors.background)
                        .consumeBackKey { editing = null },
            ) {
                LightTextInputEditor(
                    title = title,
                    state = textState,
                    onSubmit = { submit(editor, it.toString()) },
                    onBack = { editing = null },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = "SAVE",
                    singleLine = true,
                    initialCaps = editor == SetupEditor.Username,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    WheelScroll(
        onUp = {
            if (focusIndex > 0) focusIndex--
        },
        onDown = {
            if (focusIndex < rows.lastIndex) focusIndex++
        },
        onClick = {
            when (rows.getOrNull(focusIndex)) {
                SetupRow.Endpoint -> editing = SetupEditor.Endpoint
                SetupRow.TokenMode -> actions.onModeChange(AuthModeUi.Token)
                SetupRow.PasswordMode -> actions.onModeChange(AuthModeUi.Password)
                SetupRow.Token -> editing = SetupEditor.Token
                SetupRow.Username -> editing = SetupEditor.Username
                SetupRow.Password -> editing = SetupEditor.Password
                SetupRow.Test -> if (!state.testing) actions.onTestConnection()
                null -> Unit
            }
        },
        onBack = actions.onBack,
        onLongBack = actions.onLongBack,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background),
        ) {
            LightTopBar(
                leftButton =
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = actions.onBack,
                    ),
                center = LightTopBarCenter.Text("Setup"),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(status = state.connection)

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                itemsIndexed(items = rows, key = { _, row -> row.name }) { index, row ->
                    val focused = focusIndex == index
                    when (row) {
                        SetupRow.Endpoint -> {
                            FocusRow(
                                focused = focused,
                                onClick = { editing = SetupEditor.Endpoint },
                            ) {
                                LightTextField(
                                    label = "Server",
                                    value = state.endpoint,
                                    placeholder = DEFAULT_ENDPOINT,
                                    onClick = { editing = SetupEditor.Endpoint },
                                )
                            }
                        }

                        SetupRow.TokenMode -> {
                            ModeRow(
                                focused = focused,
                                selected = state.mode == AuthModeUi.Token,
                                title = "Use token",
                                detail = "loopback SPA token",
                                onClick = { actions.onModeChange(AuthModeUi.Token) },
                            )
                        }

                        SetupRow.PasswordMode -> {
                            ModeRow(
                                focused = focused,
                                selected = state.mode == AuthModeUi.Password,
                                title = "Sign in",
                                detail = "password provider",
                                onClick = { actions.onModeChange(AuthModeUi.Password) },
                            )
                        }

                        SetupRow.Token -> {
                            FocusRow(
                                focused = focused,
                                onClick = { editing = SetupEditor.Token },
                            ) {
                                LightTextField(
                                    label = "Token",
                                    value = masked(state.token),
                                    placeholder = "Paste the dashboard token",
                                    onClick = { editing = SetupEditor.Token },
                                )
                            }
                        }

                        SetupRow.Username -> {
                            FocusRow(
                                focused = focused,
                                onClick = { editing = SetupEditor.Username },
                            ) {
                                LightTextField(
                                    label = "Username",
                                    value = state.username,
                                    placeholder = "Dashboard username",
                                    onClick = { editing = SetupEditor.Username },
                                )
                            }
                        }

                        SetupRow.Password -> {
                            FocusRow(
                                focused = focused,
                                onClick = { editing = SetupEditor.Password },
                            ) {
                                LightTextField(
                                    label = "Password",
                                    value = masked(state.password),
                                    placeholder = "Dashboard password",
                                    onClick = { editing = SetupEditor.Password },
                                )
                            }
                        }

                        SetupRow.Test -> {
                            FocusRow(
                                focused = focused,
                                enabled = !state.testing,
                                onClick = { if (!state.testing) actions.onTestConnection() },
                            ) {
                                LightText(
                                    text = if (state.testing) "Testing…" else "Test connection",
                                    variant = LightTextVariant.Copy,
                                    lighten = state.testing,
                                )
                                LightText(
                                    text = "GET api/status",
                                    variant = LightTextVariant.Superfine,
                                )
                            }
                        }
                    }
                }

                item(key = "providers") {
                    val providerLine =
                        state.providerStatus.ifBlank {
                            if (state.providers.isEmpty()) {
                                "Providers: not discovered"
                            } else {
                                "Providers: " +
                                    state.providers.joinToString { provider ->
                                        if (provider.available) provider.label else "${provider.label} (unavailable)"
                                    }
                            }
                        }
                    HintLine(providerLine)
                }

                item(key = "error") {
                    state.error?.takeIf { it.isNotBlank() }?.let { error ->
                        HintLine("error: $error")
                    }
                }

                item(key = "hint") {
                    HintLine("M0 verifies the dashboard link. Sign-in and sending arrive in M1.")
                }
            }
        }
    }
}

@Composable
private fun ModeRow(
    focused: Boolean,
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    FocusRow(focused = focused, onClick = onClick) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = if (selected) "● $detail" else "○ $detail",
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
