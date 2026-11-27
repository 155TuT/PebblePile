package com.pebblepile.android

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

enum class PebbleCaptureMode(val label: String) { Chat("对话"), Record("记录") }

enum class PebbleLibraryView(val label: String, val glyph: PebbleGlyph) {
    Timeline("时间线", PebbleGlyph.Timeline), Notebook("记录本", PebbleGlyph.Library),
}

private enum class SegmentStyle { Capture, Library, Standard }

@Composable
fun PebbleCaptureModeSelector(
    mode: PebbleCaptureMode, onModeChange: (PebbleCaptureMode) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    SegmentRow(PebbleCaptureMode.entries.map { it.label }, mode.ordinal,
        { onModeChange(PebbleCaptureMode.entries[it]) }, modifier, SegmentStyle.Capture, enabled)
}

@Composable
fun PebbleLibraryViewSelector(
    view: PebbleLibraryView, onViewChange: (PebbleLibraryView) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    SegmentRow(PebbleLibraryView.entries.map { it.label }, view.ordinal,
        { onViewChange(PebbleLibraryView.entries[it]) }, modifier, SegmentStyle.Library, enabled,
        iconAt = { PebbleLibraryView.entries[it].glyph })
}

@Composable
fun PebbleSegmented(
    options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    SegmentRow(options, selectedIndex, onSelect, modifier, SegmentStyle.Standard, enabled)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentRow(
    options: List<String>, index: Int, onSelect: (Int) -> Unit, modifier: Modifier,
    style: SegmentStyle, enabled: Boolean, iconAt: ((Int) -> PebbleGlyph)? = null,
) {
    val capture = style == SegmentStyle.Capture
    val fullWidth = style != SegmentStyle.Standard
    val group = when (style) {
        SegmentStyle.Capture -> Modifier
        SegmentStyle.Library -> Modifier.pebbleSurface(Relief.Inset).padding(4.dp)
        SegmentStyle.Standard -> Modifier.pebbleSurface(Relief.Inset).padding(8.dp)
    }
    FlowRow(modifier.then(if (fullWidth) Modifier.fillMaxWidth() else Modifier).selectableGroup().then(group),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val selected = i == index
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusedAsState()
            val pressed by interactions.collectIsPressedAsState()
            val radius = if (capture) 16.dp else 12.dp
            val shape = RoundedCornerShape(radius)
            val relief = when {
                !enabled -> Relief.Flat
                pressed || (capture && selected) -> Relief.Inset
                selected -> Relief.Raised
                else -> Relief.Flat
            }
            val background = when {
                !enabled -> PebbleVisuals.SurfaceMuted
                capture && selected -> PebbleVisuals.MossSoft
                !selected && !pressed -> Color.Transparent
                else -> PebbleVisuals.Sand
            }
            val ink = when {
                !enabled -> PebbleVisuals.Outline
                style == SegmentStyle.Library -> PebbleVisuals.Ink
                selected -> PebbleVisuals.Moss
                capture -> PebbleVisuals.Muted
                else -> PebbleVisuals.Ink
            }
            val width = if (fullWidth) Modifier.weight(1f).widthIn(min = 48.dp) else Modifier.widthIn(min = 68.dp)
            Row(width.heightIn(min = 48.dp).pebbleSurface(relief, radius, background).pebbleFocus(focused, radius)
                .then(if (enabled && capture && selected) Modifier.border(1.dp, PebbleVisuals.Moss, shape) else Modifier)
                .clip(shape).selectable(selected, interactions, indication = null, enabled = enabled,
                    role = Role.Tab, onClick = { onSelect(i) })
                .padding(horizontal = if (style == SegmentStyle.Standard) 16.dp else 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                iconAt?.let { PebbleIcon(it(i), color = ink) }
                PebbleText(label, color = ink,
                    style = if (selected && !capture) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun PebbleCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
                   enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pebbleFocus(focused).toggleable(checked, source, indication = null,
        enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = when { !enabled -> PebbleVisuals.SurfaceMuted; checked -> PebbleVisuals.Moss; else -> PebbleVisuals.Sand }
        Box(Modifier.size(24.dp).pebbleSurface(if (checked || !enabled) Relief.Flat else Relief.Inset, 8.dp, color)
            .border(1.dp, if (checked && enabled) PebbleVisuals.Moss else PebbleVisuals.Outline, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) PebbleIcon(PebbleGlyph.Check, Modifier.size(16.dp), if (enabled) PebbleVisuals.Content else PebbleVisuals.Outline)
        }
        PebbleText(label, color = if (enabled) PebbleVisuals.Ink else PebbleVisuals.Outline)
    }
}

@Composable
fun PebbleRadio(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier,
                enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Row(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pebbleFocus(focused).selectable(selected, source, indication = null,
        enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).pebbleSurface(if (enabled) Relief.Inset else Relief.Flat, 12.dp)
            .border(1.dp, PebbleVisuals.Outline, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Spacer(Modifier.size(12.dp).background(if (enabled) PebbleVisuals.Moss else PebbleVisuals.Outline, CircleShape))
        }
        PebbleText(label, color = if (enabled) PebbleVisuals.Ink else PebbleVisuals.Outline)
    }
}

@Composable
fun PebbleSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
                 enabled: Boolean = true) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val state = if (checked) "已开启" else "已关闭"
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).pebbleFocus(focused)
        .toggleable(checked, interactions, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { stateDescription = state }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            PebbleText(label, color = if (enabled) PebbleVisuals.Ink else PebbleVisuals.Outline)
            PebbleText(state, Modifier.clearAndSetSemantics {}, color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        val position by animateDpAsState(if (checked) 24.dp else 0.dp, tween(180), label = "switch")
        val color = if (checked && enabled) PebbleVisuals.Moss else PebbleVisuals.SurfaceMuted
        Box(Modifier.size(56.dp,32.dp).pebbleSurface(if (enabled) Relief.Inset else Relief.Flat, 16.dp, color).padding(4.dp)) {
            Spacer(Modifier.offset { IntOffset(position.roundToPx(), 0) }.size(24.dp).pebbleSurface(Relief.Primary, 12.dp, PebbleVisuals.Content))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PebbleSlider(
    label: String, value: Float, onValueChange: (Float) -> Unit, valueLabel: String,
    modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PebbleText(label, Modifier.weight(1f))
            PebbleText(valueLabel, color = PebbleVisuals.Muted)
        }
        Slider(value, onValueChange, Modifier.fillMaxWidth().heightIn(min = 48.dp).pebbleFocus(focused)
            .semantics { contentDescription = label; stateDescription = valueLabel },
            valueRange = valueRange, steps = steps, enabled = enabled, interactionSource = interactions,
            onValueChangeFinished = onValueChangeFinished,
            thumb = {
                Spacer(Modifier.size(24.dp,32.dp).pebbleSurface(if (enabled) Relief.Raised else Relief.Flat, 12.dp)
                    .border(1.dp, PebbleVisuals.Outline, RoundedCornerShape(12.dp)))
            },
            track = {
                val fraction = if (valueRange.endInclusive > valueRange.start)
                    ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f,1f) else 0f
                Box(Modifier.fillMaxWidth().height(8.dp).pebbleSurface(Relief.Inset, 4.dp)) {
                    Spacer(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .background(if (enabled) PebbleVisuals.Moss else PebbleVisuals.Outline, CircleShape))
                }
            })
    }
}

/** M4 scope choices: each option stands on its own surface inside the caller's selectableGroup. */
@Composable
fun PebbleChoiceTile(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier,
                     enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val surface = when { !enabled -> PebbleVisuals.SurfaceMuted; selected -> PebbleVisuals.MossSoft; else -> PebbleVisuals.Sand }
    val relief = when { !enabled -> Relief.Flat; selected || pressed -> Relief.Inset; else -> Relief.Raised }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 64.dp).pebbleSurface(relief, color = surface)
        .pebbleFocus(focused).then(if (selected && enabled) Modifier.border(1.dp, PebbleVisuals.Moss, shape) else Modifier)
        .clip(shape).selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(12.dp), contentAlignment = Alignment.Center) {
        PebbleText(label, color = when { !enabled -> PebbleVisuals.Outline; selected -> PebbleVisuals.Moss; else -> PebbleVisuals.Ink },
            style = MaterialTheme.typography.labelLarge)
    }
}
