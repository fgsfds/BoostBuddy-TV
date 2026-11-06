// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.datetime.Clock
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key.Companion.DirectionCenter
import androidx.compose.ui.input.key.Key.Companion.DirectionDown
import androidx.compose.ui.input.key.Key.Companion.DirectionDownLeft
import androidx.compose.ui.input.key.Key.Companion.DirectionDownRight
import androidx.compose.ui.input.key.Key.Companion.DirectionLeft
import androidx.compose.ui.input.key.Key.Companion.DirectionRight
import androidx.compose.ui.input.key.Key.Companion.DirectionUp
import androidx.compose.ui.input.key.Key.Companion.DirectionUpLeft
import androidx.compose.ui.input.key.Key.Companion.DirectionUpRight
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.slartus.boostbuddy.components.auth.AuthComponent
import ru.slartus.boostbuddy.components.auth.AuthViewState
import ru.slartus.boostbuddy.ui.common.LocalPlatformConfiguration
import ru.slartus.boostbuddy.ui.common.keyboardAsState
import ru.slartus.boostbuddy.ui.widgets.WebView
import ru.slartus.boostbuddy.utils.Platform

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthScreen(component: AuthComponent) {
    val density = LocalDensity.current

    val platformConfiguration = LocalPlatformConfiguration.current
    var useCursor by remember { mutableStateOf(false) }
    val isKeyboardOpen by keyboardAsState()
    val state by component.viewStates.subscribeAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = CenterVertically
                    ) {
                        Text(
                            text = "Вход в Boosty"
                        )
                    }
                },
                actions = {
                    if (state.mode == AuthViewState.Mode.WebView) {
                        if (state.canRetryLogin) {
                            AuthActionButton(
                                label = "Повторить вход",
                                onClick = component::onRetryLoginClick,
                                enabled = !state.isLoading,
                                primary = true,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        AuthActionButton(
                            label = "Вход по телефону",
                            onClick = component::onSwitchToPhoneLoginClick,
                            enabled = !state.isLoading,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        if (platformConfiguration.platform == Platform.AndroidTV)
                            IconButton(onClick = { useCursor = !useCursor }) {
                                Icon(
                                    imageVector = Icons.Filled.NorthWest,
                                    contentDescription = "Программный курсор"
                                )
                            }
                        IconButton(onClick = component::onReloadClick, enabled = !state.isLoading) {
                            Icon(
                                imageVector = Icons.Filled.Repeat,
                                contentDescription = "Повторить"
                            )
                        }
                    }
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 20.dp).size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }

            )
        },
    ) { innerPadding ->
        if (state.mode != AuthViewState.Mode.WebView) {
            PhoneAuthContent(
                state = state,
                component = component,
                isKeyboardOpen = isKeyboardOpen,
                modifier = Modifier.padding(innerPadding).fillMaxSize()
            )
            return@Scaffold
        }
        var cursorPositionX by remember { mutableStateOf(0.dp) }
        var cursorPositionY by remember { mutableStateOf(0.dp) }
        var clickOffset by remember { mutableStateOf<Offset?>(null) }
        val focusRequester = remember { FocusRequester() }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(isKeyboardOpen, useCursor) {
            if (useCursor) {
                if (isKeyboardOpen)
                    focusRequester.freeFocus()
                else
                    focusRequester.requestFocus()
            }
        }
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .then(
                    if (!useCursor) Modifier
                    else Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (isKeyboardOpen) return@onPreviewKeyEvent false
                            if (!isOwnKeyCode(keyEvent)) return@onPreviewKeyEvent false
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    DirectionUp -> {
                                        val newValue = cursorPositionY - 5.dp
                                        cursorPositionY = cursorPositionY.dec()
                                        newValue >= 0.dp
                                    }

                                    DirectionUpLeft -> {
                                        cursorPositionY = cursorPositionY.dec()
                                        cursorPositionX = cursorPositionX.dec()
                                        true
                                    }

                                    DirectionUpRight -> {
                                        cursorPositionY = cursorPositionY.dec()
                                        cursorPositionX = cursorPositionX.inc()
                                        true
                                    }

                                    DirectionDown -> {
                                        cursorPositionY = cursorPositionY.inc()
                                        true
                                    }

                                    DirectionDownLeft -> {
                                        cursorPositionY = cursorPositionY.inc()
                                        cursorPositionX = cursorPositionX.dec()
                                        true
                                    }

                                    DirectionDownRight -> {
                                        cursorPositionY = cursorPositionY.inc()
                                        cursorPositionX = cursorPositionX.inc()
                                        true
                                    }

                                    DirectionLeft -> {
                                        cursorPositionX = cursorPositionX.dec()
                                        true
                                    }

                                    DirectionRight -> {
                                        cursorPositionX = cursorPositionX.inc()
                                        true
                                    }

                                    DirectionCenter -> {
                                        coroutineScope.launch {
                                            clickOffset = with(density) {
                                                Offset(
                                                    cursorPositionX.toPx(),
                                                    cursorPositionY.toPx()
                                                )
                                            }
                                            delay(500)
                                            if (!isKeyboardOpen)
                                                focusRequester.requestFocus()
                                        }
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                true
                            }
                        }
                )

        ) {
            WebView(
                state.url,
                clickOffset,
                onCookieChange = component::onCookiesChanged
            )
            if (useCursor)
                Icon(
                    modifier = Modifier.size(24.dp)
                        .clickable {
                            useCursor = false
                        }
                        .offset(x = cursorPositionX, y = cursorPositionY),
                    imageVector = Icons.Filled.NorthWest,
                    tint = Color.Red,
                    contentDescription = "Обновить"
                )
        }
    }
    AuthErrorDialog(state.errorMessage, component::onDismissError)
}

@Composable
private fun PhoneAuthContent(
    state: AuthViewState,
    component: AuthComponent,
    isKeyboardOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val isTv = LocalPlatformConfiguration.current.platform == Platform.AndroidTV
    BoxWithConstraints(modifier) {
        if (isTv && maxWidth >= 800.dp) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuthIntroduction(Modifier.weight(0.9f))
                Box(
                    Modifier.weight(1.1f).verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    PhoneAuthForm(state, component, isKeyboardOpen, Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                AuthIntroduction(Modifier.widthIn(max = 600.dp).fillMaxWidth())
                PhoneAuthForm(state, component, isKeyboardOpen, Modifier.widthIn(max = 600.dp).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun AuthErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    val errorFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не удалось продолжить") },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            AuthActionButton(
                label = "Понятно",
                onClick = onDismiss,
                primary = true,
                modifier = Modifier.focusRequester(errorFocus),
            )
            LaunchedEffect(message) { errorFocus.requestFocus() }
        },
    )
}

@Composable
private fun AuthIntroduction(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            "BOOSTBUDDY",
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("Ваши подписки\nна большом экране", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Введите номер, привязанный к аккаунту с вашими подписками Boosty.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Входили через Google или почту? Сначала привяжите номер в профиле Boosty на телефоне. Также доступен вход через сайт.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneAuthForm(
    state: AuthViewState,
    component: AuthComponent,
    isKeyboardOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val phoneFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val countryInteraction = remember { MutableInteractionSource() }
    val phoneInteraction = remember { MutableInteractionSource() }
    val codeInteraction = remember { MutableInteractionSource() }
    val resendAt = state.resendAvailableAtEpochMs
    val secondsLeft by produceState(initialValue = computeSecondsLeft(resendAt), key1 = resendAt) {
        do {
            value = computeSecondsLeft(resendAt)
            if (value > 0) delay(1000)
        } while (value > 0)
    }
    LaunchedEffect(state.mode, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        when (state.mode) {
            AuthViewState.Mode.EnterPhone -> phoneFocus.requestFocus()
            AuthViewState.Mode.EnterSmsCode -> codeFocus.requestFocus()
            AuthViewState.Mode.CompleteLogin -> submitFocus.requestFocus()
            AuthViewState.Mode.WebView -> Unit
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.mode) {
                AuthViewState.Mode.EnterPhone -> {
                    Text("Ваш номер телефона", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthActionButton(
                            label = "Сербия +381",
                            onClick = {
                                component.onCountryCodeChanged("+381")
                                phoneFocus.requestFocus()
                            },
                            enabled = !state.isLoading,
                            primary = state.countryCode == "+381",
                            modifier = Modifier.weight(1f),
                        )
                        AuthActionButton(
                            label = "+7",
                            onClick = {
                                component.onCountryCodeChanged("+7")
                                phoneFocus.requestFocus()
                            },
                            enabled = !state.isLoading,
                            primary = state.countryCode == "+7",
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.countryCode,
                            onValueChange = component::onCountryCodeChanged,
                            label = { Text("Код страны") },
                            singleLine = true,
                            enabled = !state.isLoading,
                            textStyle = MaterialTheme.typography.titleLarge,
                            shape = RoundedCornerShape(12.dp),
                            interactionSource = countryInteraction,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { phoneFocus.requestFocus() }),
                            modifier = Modifier.width(126.dp).authFocusRing(countryInteraction)
                                .tvAuthFieldNavigation(isKeyboardOpen),
                        )
                        OutlinedTextField(
                            value = state.phone,
                            onValueChange = component::onPhoneChanged,
                            label = { Text("Номер") },
                            placeholder = { Text(if (state.countryCode == "+381") "64 123 4567" else "999 123 4567") },
                            singleLine = true,
                            enabled = !state.isLoading,
                            textStyle = MaterialTheme.typography.titleLarge,
                            shape = RoundedCornerShape(12.dp),
                            interactionSource = phoneInteraction,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); submitFocus.requestFocus() }),
                            modifier = Modifier.weight(1f).focusRequester(phoneFocus).authFocusRing(phoneInteraction)
                                .tvAuthFieldNavigation(isKeyboardOpen),
                        )
                    }
                    Text(
                        "Код страны можно изменить. В Сербии вводите номер без начального 0.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AuthActionButton(
                        label = when {
                            state.isLoading -> "Запрашиваем код…"
                            secondsLeft > 0 -> "Получить код через $secondsLeft с"
                            else -> "Получить код"
                        },
                        onClick = { keyboard?.hide(); component.onSendSmsCodeClick() },
                        enabled = !state.isLoading && secondsLeft == 0,
                        primary = true,
                        loading = state.isLoading,
                        modifier = Modifier.fillMaxWidth().focusRequester(submitFocus),
                    )
                }
                AuthViewState.Mode.EnterSmsCode -> {
                    Text("Код для ${state.challengePhone.orEmpty()}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Проверьте сообщения в приложении Boosty, связанный Telegram-бот или SMS.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.smsCode,
                        onValueChange = component::onSmsCodeChanged,
                        label = { Text("Код подтверждения") },
                        singleLine = true,
                        enabled = !state.isLoading,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        shape = RoundedCornerShape(12.dp),
                        interactionSource = codeInteraction,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); submitFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus).authFocusRing(codeInteraction)
                            .tvAuthFieldNavigation(isKeyboardOpen),
                    )
                    AuthActionButton(
                        label = if (state.isLoading) "Проверяем…" else "Войти",
                        onClick = { keyboard?.hide(); component.onConfirmSmsCodeClick() },
                        enabled = !state.isLoading && state.smsCode.isNotBlank(),
                        primary = true,
                        loading = state.isLoading,
                        modifier = Modifier.fillMaxWidth().focusRequester(submitFocus),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthActionButton(
                            label = if (secondsLeft > 0) "Повторно через $secondsLeft с" else "Отправить ещё раз",
                            onClick = component::onResendSmsCodeClick,
                            enabled = !state.isLoading && secondsLeft == 0,
                            modifier = Modifier.weight(1f),
                        )
                        AuthActionButton(
                            label = "Изменить номер",
                            onClick = component::onBackToPhoneClick,
                            enabled = !state.isLoading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                AuthViewState.Mode.CompleteLogin -> {
                    Text("Код принят", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Проверяем аккаунт Boosty. Если соединение прервалось, повторите вход — новый код не нужен.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    AuthActionButton(
                        label = if (state.isLoading) "Проверяем аккаунт…" else "Повторить вход",
                        onClick = component::onRetryLoginClick,
                        enabled = !state.isLoading && state.canRetryLogin,
                        primary = true,
                        loading = state.isLoading,
                        modifier = Modifier.fillMaxWidth().focusRequester(submitFocus),
                    )
                    AuthActionButton(
                        label = "Изменить номер",
                        onClick = component::onBackToPhoneClick,
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthViewState.Mode.WebView -> Unit
            }
            AuthActionButton(
                label = "Войти через сайт",
                onClick = { keyboard?.hide(); component.onSwitchToWebViewClick() },
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AuthActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 54.dp),
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Modifier.authFocusRing(interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return border(
        if (focused) 3.dp else 1.dp,
        if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
        RoundedCornerShape(12.dp),
    )
}

@Composable
private fun Modifier.tvAuthFieldNavigation(isKeyboardOpen: Boolean): Modifier {
    val isTv = LocalPlatformConfiguration.current.platform == Platform.AndroidTV
    val focusManager = LocalFocusManager.current
    if (!isTv || isKeyboardOpen) return this
    // Without the IME, remote arrows navigate controls rather than move a text caret.
    // Let the text field and the system keyboard handle keys while editing.
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            DirectionUp -> FocusDirection.Up
            DirectionDown -> FocusDirection.Down
            DirectionLeft -> FocusDirection.Left
            DirectionRight -> FocusDirection.Right
            else -> null
        } ?: return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
        true
    }
}

private fun computeSecondsLeft(resendAtEpochMs: Long?): Int {
    if (resendAtEpochMs == null) return 0
    val diff = resendAtEpochMs - Clock.System.now().toEpochMilliseconds()
    return if (diff <= 0) 0 else ((diff + 999) / 1000).toInt()
}

private fun Dp.inc(): Dp = this + 5.dp
private fun Dp.dec(): Dp = max(this - 5.dp, 0.dp)

private fun isOwnKeyCode(keyEvent: KeyEvent): Boolean =
    when (keyEvent.key) {
        DirectionUp,
        DirectionUpLeft,
        DirectionUpRight,
        DirectionDown,
        DirectionDownLeft,
        DirectionDownRight,
        DirectionLeft,
        DirectionRight,
        DirectionCenter -> true

        else -> false
    }
