package com.pebblepile.android

import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.zIndex

data class PebbleMenuAction(
    val label: String, val onClick: () -> Unit, val icon: PebbleGlyph? = null,
    val enabled: Boolean = true, val current: Boolean = false, val danger: Boolean = false,
)

/** Place the menu and its trigger in the same Box. It opens below the trigger, or above near the bottom. */
@Composable
fun PebbleMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<PebbleMenuAction>,
               modifier: Modifier = Modifier) {
    if (!expanded || items.isEmpty()) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val halo = with(LocalDensity.current) { 16.dp.roundToPx() }
    val position = remember(gap, halo) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
                                           popupContentSize: IntSize): IntOffset {
                val left = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left - halo else anchorBounds.right - popupContentSize.width + halo
                val below = anchorBounds.bottom + gap - halo
                val above = anchorBounds.top - gap - popupContentSize.height + halo
                val y = if (below + popupContentSize.height <= windowSize.height || above < 0) below else above
                return IntOffset(left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            }
        }
    }
    val config = LocalConfiguration.current
    val maxWidth = (config.screenWidthDp.dp - 32.dp).coerceAtLeast(48.dp)
    val focus = remember(items.size) { List(items.size) { FocusRequester() } }
    val available = items.indices.filter { items[it].enabled }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.padding(16.dp).then(modifier).widthIn(min = minOf(200.dp, maxWidth), max = maxWidth).width(IntrinsicSize.Max)
            .heightIn(max = (config.screenHeightDp.dp - 32.dp).coerceAtLeast(48.dp))
            .pebbleSurface(Relief.Raised, 20.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
            items.forEachIndexed { index, item ->
                val interactions = remember { MutableInteractionSource() }
                val focused by interactions.collectIsFocusedAsState()
                val hovered by interactions.collectIsHoveredAsState()
                val active = item.enabled && (item.current || focused || hovered)
                val ink = when { !item.enabled -> PebbleVisuals.Outline; item.danger -> PebbleVisuals.Danger
                    item.current -> PebbleVisuals.Moss; else -> PebbleVisuals.Ink }
                Row(Modifier.zIndex(if (focused) 1f else 0f).fillMaxWidth().heightIn(min = 48.dp)
                    .pebbleSurface(if (item.enabled && item.current) Relief.Inset else Relief.Flat, 12.dp,
                        if (active) PebbleVisuals.MossSoft else PebbleVisuals.Sand)
                    .pebbleFocus(focused,12.dp).focusRequester(focus[index])
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || available.isEmpty()) return@onPreviewKeyEvent false
                        val at = available.indexOf(index)
                        val next = when (event.key) {
                            Key.DirectionDown -> available[(at+1) % available.size]
                            Key.DirectionUp -> available[(at-1+available.size) % available.size]
                            Key.MoveHome -> available.first()
                            Key.MoveEnd -> available.last()
                            Key.Escape -> { onDismiss(); return@onPreviewKeyEvent true }
                            else -> return@onPreviewKeyEvent false
                        }
                        focus[next].requestFocus()
                        true
                    }
                    .clip(RoundedCornerShape(12.dp)).clickable(interactions, indication = null, enabled = item.enabled,
                        role = Role.Button, onClick = { onDismiss(); item.onClick() })
                    .semantics { if (item.current) selected = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    item.icon?.let { PebbleIcon(it, color = ink) }
                    PebbleText(item.label, color = ink)
                }
            }
        }
        if (LocalInputModeManager.current.inputMode == InputMode.Keyboard) {
            LaunchedEffect(focus) { available.firstOrNull()?.let { focus[it].requestFocus() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PebbleDialog(
    title: String, onDismiss: () -> Unit, confirmLabel: String, onConfirm: () -> Unit,
    modifier: Modifier = Modifier, cancelLabel: String? = "取消", danger: Boolean = false,
    confirmEnabled: Boolean = true, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) {
    Overlay(title, onDismiss, modifier, bottom = false, wide = wide) {
        val cancelFocus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (danger) PebbleIcon(PebbleGlyph.Warning, color = PebbleVisuals.Danger)
            PebbleText(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        }
        content()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cancelLabel?.let { PebbleButton(it, onDismiss, Modifier.focusRequester(cancelFocus), PebbleButtonStyle.Secondary) }
            PebbleButton(confirmLabel, onConfirm, style = if (danger) PebbleButtonStyle.Danger else PebbleButtonStyle.Primary,
                enabled = confirmEnabled)
        }
        if (cancelLabel != null && LocalInputModeManager.current.inputMode == InputMode.Keyboard) {
            LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        }
    }
}

@Composable
fun PebbleSheet(title: String, onDismiss: () -> Unit, modifier: Modifier = Modifier,
                content: @Composable ColumnScope.() -> Unit) {
    Overlay(title, onDismiss, modifier, bottom = true, wide = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PebbleText(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            PebbleIconButton(PebbleGlyph.Close, "关闭", onDismiss, style = PebbleIconButtonStyle.Plain)
        }
        content()
    }
}

@Composable
private fun Overlay(title: String, onDismiss: () -> Unit, modifier: Modifier, bottom: Boolean, wide: Boolean,
                    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The full-window dialog draws the VI scrim itself. Android's default dim would darken it twice.
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setDimAmount(0f)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                if (Build.VERSION.SDK_INT >= 30 && attributes.fitInsetsTypes != 0) {
                    attributes = attributes.apply { fitInsetsTypes = 0 }
                }
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowCompat.getInsetsController(this, decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
        val dismiss by rememberUpdatedState(onDismiss)
        Box(Modifier.fillMaxSize()) {
            Spacer(Modifier.matchParentSize().background(PebbleVisuals.Ink.copy(alpha = .32f))
                .pointerInput(Unit) { detectTapGestures { dismiss() } })
            val safe = WindowInsets.safeDrawing
            val panelInsets = if (bottom) safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top) else safe
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(panelInsets).imePadding()
                .padding(horizontal = if (bottom) 0.dp else 16.dp, vertical = if (bottom) 0.dp else 24.dp),
                contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.Center) {
                val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp,
                    bottomStart = if (bottom) 0.dp else 24.dp, bottomEnd = if (bottom) 0.dp else 24.dp)
                Column(modifier.widthIn(max = if (wide) 480.dp else 360.dp).fillMaxWidth()
                    .heightIn(max = if (bottom) maxHeight * .8f else maxHeight)
                    .pebbleSurface(Relief.Overlay, 24.dp, squareBottom = bottom).clip(shape)
                    .semantics { paneTitle = title }
                    // Receive taps on the panel's background so only the exposed scrim dismisses it.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .then(if (bottom) Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom)) else Modifier)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(if (bottom) 12.dp else 20.dp), content = content)
            }
        }
    }
}
