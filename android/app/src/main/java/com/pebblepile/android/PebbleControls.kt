package com.pebblepile.android

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

enum class PebbleButtonStyle { Primary, Secondary, Danger }
enum class PebbleIconButtonStyle { Raised, Plain, Primary }

@Composable
fun PebbleButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    style: PebbleButtonStyle = PebbleButtonStyle.Primary, icon: PebbleGlyph? = null,
    enabled: Boolean = true, busy: Boolean = false, busyLabel: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val secondary = style == PebbleButtonStyle.Secondary
    val background = when {
        !enabled -> PebbleVisuals.SurfaceMuted
        style == PebbleButtonStyle.Danger -> PebbleVisuals.Danger
        secondary -> if (hovered && !pressed) PebbleVisuals.Hover else PebbleVisuals.Sand
        pressed -> PebbleVisuals.MossPressed
        hovered -> PebbleVisuals.MossHover
        else -> PebbleVisuals.Moss
    }
    val relief = when {
        !enabled -> Relief.Flat
        pressed && secondary -> Relief.Inset
        pressed && style == PebbleButtonStyle.Danger -> Relief.DangerPressed
        pressed -> Relief.Pressed
        secondary -> Relief.Raised
        else -> Relief.Primary
    }
    val ink = when { !enabled -> PebbleVisuals.Outline; secondary -> PebbleVisuals.Moss; else -> PebbleVisuals.Content }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .pebbleSurface(relief, color = background).pebbleFocus(focused)
        .then(if (enabled && secondary) Modifier.border(1.dp, PebbleVisuals.Outline, shape) else Modifier)
        .clip(shape).clickable(interactions, indication = null, enabled = enabled && !busy,
            role = Role.Button, onClick = onClick)
        .then(if (busy) Modifier.semantics { stateDescription = "正在处理" } else Modifier)
        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        // Reserve the space for both labels so saving does not move the adjoining action.
        @Composable fun face(text: String, glyph: PebbleGlyph?, visible: Boolean) {
            Row(Modifier.padding(horizontal = 20.dp).then(if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                glyph?.let { PebbleIcon(it, color = ink) }
                PebbleText(text, color = ink, style = MaterialTheme.typography.labelLarge)
            }
        }
        face(label, icon, !busy)
        if (busyLabel != null) {
            face(busyLabel, PebbleGlyph.Clock, busy)
        } else if (busy) {
            face(label, icon?.let { PebbleGlyph.Clock }, true)
            if (icon == null) PebbleIcon(PebbleGlyph.Clock, Modifier.align(Alignment.CenterStart).size(16.dp), ink)
        }
    }
}

@Composable
fun PebbleIconButton(
    icon: PebbleGlyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    style: PebbleIconButtonStyle = PebbleIconButtonStyle.Raised, enabled: Boolean = true, tint: Color? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val primary = style == PebbleIconButtonStyle.Primary
    val background = when {
        !enabled -> PebbleVisuals.SurfaceMuted
        primary && pressed -> PebbleVisuals.MossPressed
        primary -> PebbleVisuals.Moss
        hovered && !pressed -> PebbleVisuals.Hover
        else -> PebbleVisuals.Sand
    }
    val relief = when {
        !enabled -> Relief.Flat
        primary && pressed -> Relief.Pressed
        pressed -> Relief.Inset
        primary -> Relief.Primary
        style == PebbleIconButtonStyle.Plain -> Relief.Flat
        else -> Relief.Raised
    }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .pebbleSurface(relief, color = background).pebbleFocus(focused)
        .clip(RoundedCornerShape(16.dp))
        .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val ink = when {
            !enabled -> PebbleVisuals.Outline
            tint != null -> tint
            primary -> PebbleVisuals.Content
            style == PebbleIconButtonStyle.Plain -> PebbleVisuals.Moss
            else -> PebbleVisuals.Ink
        }
        PebbleIcon(icon, color = ink)
    }
}

@Composable
fun PebbleLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
               enabled: Boolean = true, actionLabel: String = label) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pebbleFocus(focused, 8.dp)
        .clickable(interactions, indication = null, enabled = enabled, onClickLabel = actionLabel,
            role = Role.Button, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        PebbleText(label, color = if (enabled) PebbleVisuals.Focus else PebbleVisuals.Outline,
            style = MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.Underline))
    }
}

enum class PebbleFieldKind { Multiline, SingleLine, Search }

@Composable
fun PebbleInput(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, errorMessage: String? = null,
    placeholder: String? = null, help: String? = null, kind: PebbleFieldKind = PebbleFieldKind.Multiline,
    showLabel: Boolean = true, leadingIcon: PebbleGlyph? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = PebbleEditableText,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val ink = if (enabled) PebbleVisuals.Ink else PebbleVisuals.Outline
    val singleLine = kind != PebbleFieldKind.Multiline
    val minHeight = when (kind) {
        PebbleFieldKind.Multiline -> 80.dp
        PebbleFieldKind.SingleLine -> 64.dp
        PebbleFieldKind.Search -> 56.dp
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showLabel) PebbleText(label, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium)
        BasicTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4, interactionSource = interactions,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink), cursorBrush = SolidColor(PebbleVisuals.Moss),
            modifier = Modifier.fillMaxWidth()
                .pebbleSurface(if (enabled) Relief.Inset else Relief.Flat,
                    color = if (enabled) PebbleVisuals.Sand else PebbleVisuals.SurfaceMuted)
                .pebbleFocus(focused)
                .border(1.dp, if (errorMessage != null) PebbleVisuals.Danger else PebbleVisuals.Outline, shape)
                .semantics {
                    contentDescription = label
                    if (errorMessage != null) error(errorMessage)
                    else if (help != null) stateDescription = help
                },
            decorationBox = { field ->
                Row(Modifier.fillMaxWidth().heightIn(min = minHeight)
                    .padding(horizontal = 16.dp, vertical = if (kind == PebbleFieldKind.Search) 12.dp else 16.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(if (kind == PebbleFieldKind.Search) 12.dp else 8.dp)) {
                    leadingIcon?.let { PebbleIcon(it, color = if (kind == PebbleFieldKind.Search) PebbleVisuals.Ink else PebbleVisuals.Muted) }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) PebbleText(placeholder, color = PebbleVisuals.Muted)
                        field()
                    }
                }
            },
        )
        (errorMessage ?: help)?.let {
            PebbleText(it, color = if (errorMessage != null) PebbleVisuals.Danger else PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PebbleSearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit,
                      modifier: Modifier = Modifier, label: String = "搜索记录") {
    PebbleInput(query, onQueryChange, label, modifier, placeholder = label, kind = PebbleFieldKind.Search,
        showLabel = false, leadingIcon = PebbleGlyph.Search,
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }))
}
