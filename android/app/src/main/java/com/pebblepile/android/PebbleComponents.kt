package com.pebblepile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PebbleTagGroup(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun PebbleTag(
    label: String, selected: Boolean, onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, origin: String? = null, needsReview: Boolean = false,
    enabled: Boolean = true, icon: PebbleGlyph? = null,
) {
    val source = remember { MutableInteractionSource() }
    val toggle = Modifier.toggleable(selected, source, indication = null, enabled = enabled,
        role = Role.Checkbox, onValueChange = onSelectedChange)
    TagSurface(label, modifier, origin, needsReview, enabled, selected, icon, source, toggle)
}

/** A label on a record can open its evidence sheet, or simply identify the record. */
@Composable
fun PebbleTag(
    label: String, modifier: Modifier = Modifier, origin: String? = null, needsReview: Boolean = false,
    icon: PebbleGlyph? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val action = if (onClick == null) Modifier.semantics(mergeDescendants = true) {} else
        Modifier.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    TagSurface(label, modifier, origin, needsReview, enabled, false, icon, source, action)
}

@Composable
private fun TagSurface(
    label: String, modifier: Modifier, origin: String?, needsReview: Boolean, enabled: Boolean, selected: Boolean,
    icon: PebbleGlyph?, source: MutableInteractionSource, action: Modifier,
) {
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val ink = when {
        !enabled -> PebbleVisuals.Outline
        needsReview -> PebbleVisuals.Warning
        selected -> PebbleVisuals.Moss
        else -> PebbleVisuals.Ink
    }
    val surface = when {
        !enabled -> PebbleVisuals.SurfaceMuted
        needsReview -> PebbleVisuals.WarningSoft
        else -> PebbleVisuals.Sand
    }
    val relief = when {
        !enabled || needsReview -> Relief.Flat
        selected || pressed -> Relief.Inset
        else -> Relief.Raised
    }
    Column(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).pebbleSurface(relief, color = surface)
        .pebbleFocus(focused).clip(RoundedCornerShape(16.dp)).then(action)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val glyph = if (selected) PebbleGlyph.Check else icon
            glyph?.let { PebbleIcon(it, color = ink) }
            PebbleText(if (needsReview) "$label · 待核对" else label, color = ink,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
        origin?.let { PebbleText(it, color = ink, style = MaterialTheme.typography.bodyMedium) }
    }
}

enum class PebbleFeedbackState(val label: String, val ink: Color, val surface: Color, val icon: PebbleGlyph) {
    SAVED("已保存", PebbleVisuals.Success, PebbleVisuals.SuccessSoft, PebbleGlyph.Check),
    PROCESSING("正在整理", PebbleVisuals.Info, PebbleVisuals.InfoSoft, PebbleGlyph.Info),
    REVIEW("待核对", PebbleVisuals.Warning, PebbleVisuals.WarningSoft, PebbleGlyph.Warning),
    FAILED("保存失败", PebbleVisuals.Danger, PebbleVisuals.DangerSoft, PebbleGlyph.Warning),
    PROCESSING_FAILED("整理失败", PebbleVisuals.Danger, PebbleVisuals.DangerSoft, PebbleGlyph.Warning),
    WAITING("等待条件", PebbleVisuals.Warning, PebbleVisuals.WarningSoft, PebbleGlyph.Clock),
    UPDATED("已更新", PebbleVisuals.Success, PebbleVisuals.SuccessSoft, PebbleGlyph.Check),
    PAUSED("已暂停", PebbleVisuals.Warning, PebbleVisuals.WarningSoft, PebbleGlyph.Pause),
}

@Composable
fun PebbleStatusBadge(state: PebbleFeedbackState, modifier: Modifier = Modifier, label: String = state.label) {
    Row(modifier.heightIn(min = 32.dp).background(state.surface, androidx.compose.foundation.shape.CircleShape)
        .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PebbleIcon(state.icon, Modifier.size(16.dp), state.ink)
        PebbleText(label, color = state.ink, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PebbleBanner(
    label: String, icon: PebbleGlyph, modifier: Modifier = Modifier, detail: String? = null,
    tone: Color = PebbleVisuals.Info, action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().pebbleSurface(Relief.Inset).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PebbleIcon(icon, color = tone)
            Column(Modifier.weight(1f)) {
                PebbleText(label)
                detail?.let { PebbleText(it, color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        action?.invoke()
    }
}

/** The parent owns the notification's lifetime and its position above the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PebbleToast(
    label: String, icon: PebbleGlyph, modifier: Modifier = Modifier,
    tone: Color = PebbleVisuals.Success, action: (@Composable () -> Unit)? = null,
) {
    FlowRow(modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 48.dp).pebbleSurface(Relief.Raised)
        .padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.align(Alignment.CenterVertically).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PebbleIcon(icon, color = tone)
            PebbleText(label)
        }
        action?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PebbleFeedback(
    state: PebbleFeedbackState, modifier: Modifier = Modifier, detail: String? = null,
    onRetry: (() -> Unit)? = null, onContinue: (() -> Unit)? = null, onCancel: (() -> Unit)? = null,
) {
    val retry = onRetry?.takeIf { state == PebbleFeedbackState.FAILED || state == PebbleFeedbackState.PROCESSING_FAILED }
    if ((state == PebbleFeedbackState.SAVED || state == PebbleFeedbackState.UPDATED) && detail == null &&
        onContinue == null && onCancel == null) {
        PebbleToast(state.label, state.icon, modifier, state.ink)
        return
    }
    val actions: (@Composable () -> Unit)? = if (retry == null && onContinue == null && onCancel == null) null else {
        {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                retry?.let {
                    PebbleButton(if (state == PebbleFeedbackState.FAILED) "重试保存" else "重试整理",
                        it, style = PebbleButtonStyle.Secondary, icon = PebbleGlyph.Retry)
                }
                onContinue?.let { PebbleButton("继续", it, style = PebbleButtonStyle.Secondary) }
                onCancel?.let { PebbleButton("取消", it, style = PebbleButtonStyle.Secondary) }
            }
        }
    }
    PebbleBanner(state.label, state.icon, modifier, detail, state.ink, actions)
}

@Composable
fun PebbleEvidenceSource(
    quote: String, sourceLabel: String, onOpenSource: () -> Unit,
    sourceAvailable: Boolean = true, unavailableReason: String = "来源暂不可用",
) {
    val interactions = remember { MutableInteractionSource() }
    val openSource = if (sourceAvailable) {
        Modifier.clickable(
            interactions,
            indication = null,
            onClickLabel = "查看原文，$sourceLabel",
            role = Role.Button,
            onClick = onOpenSource,
        )
    } else {
        Modifier
    }
    PebbleQuote(quote, modifier = openSource, source = sourceLabel) {
        if (!sourceAvailable) PebbleText(unavailableReason, color = PebbleVisuals.Muted)
        PebbleLink("查看原文", onOpenSource, enabled = sourceAvailable, actionLabel = "查看原文，$sourceLabel")
    }
}

@Composable
fun PebbleEvidenceCard(
    condition: String, onCorrect: () -> Unit, modifier: Modifier = Modifier,
    origin: String? = null, sources: @Composable ColumnScope.() -> Unit,
) {
    PebbleCard(modifier) {
        origin?.let { PebbleText(it, style = MaterialTheme.typography.bodyMedium, color = PebbleVisuals.Moss) }
        PebbleText(condition, style = MaterialTheme.typography.titleMedium)
        sources()
        PebbleButton("纠正条件", onCorrect)
    }
}

@Composable
fun PebbleEvidenceCard(
    quote: String, sourceLabel: String, condition: String, onOpenSource: () -> Unit,
    onCorrect: () -> Unit, modifier: Modifier = Modifier, sourceAvailable: Boolean = true,
) {
    PebbleEvidenceCard(condition, onCorrect, modifier) {
        PebbleEvidenceSource(quote, sourceLabel, onOpenSource, sourceAvailable)
    }
}
