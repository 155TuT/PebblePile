package com.pebblepile.android

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared Android colors from assets/design-tokens.json. */
object PebbleVisuals {
    val Sand = Color(0xFFF5F3EB)
    val Ink = Color(0xFF222C24)
    val Muted = Color(0xFF5E6B5D)
    val Moss = Color(0xFF536D58)
    val Focus = Color(0xFF365B44)
    val Content = Color(0xFFFFFDF7)
    val Outline = Color(0xFF788374)
}

@Composable
fun PebbleComponentTheme(cjk: FontFamily = PebbleWenKai, content: @Composable () -> Unit) {
    val body = TextStyle(fontFamily = cjk, fontWeight = FontWeight.Normal,
        fontSynthesis = FontSynthesis.None, fontSize = 16.sp, lineHeight = 26.sp)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = PebbleVisuals.Moss,
            onPrimary = PebbleVisuals.Content, background = PebbleVisuals.Sand,
            surface = PebbleVisuals.Sand, onSurface = PebbleVisuals.Ink, onBackground = PebbleVisuals.Ink,
            surfaceContainerHigh = PebbleVisuals.Content, surfaceTint = PebbleVisuals.Moss,
            onSurfaceVariant = PebbleVisuals.Muted, outline = PebbleVisuals.Outline,
            error = Color(0xFF934C47)),
        typography = Typography(bodyLarge = body, bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            headlineSmall = body.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
            titleMedium = body.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
            labelLarge = body.copy(fontWeight = FontWeight.Medium)),
    ) { CompositionLocalProvider(LocalContentColor provides PebbleVisuals.Ink, content = content) }
}

/** Layered soft relief, drawn in dp. Inset is clipped inside the control. */
private fun Modifier.relief(inset: Boolean = false, radius: Int = 16, surface: Color = PebbleVisuals.Sand): Modifier = drawBehind {
    val r = radius.dp.toPx()
    val shade = Color(0xFFD6D7CF)
    if (!inset) {
        for (i in 14 downTo 1) {
            val spread = i.dp.toPx() / 2
            for ((offset, color) in listOf(6f to shade, -6f to Color.White)) {
                drawRoundRect(color.copy(alpha = 0.045f),
                    topLeft = Offset(offset.dp.toPx() - spread, offset.dp.toPx() - spread),
                    size = androidx.compose.ui.geometry.Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(r + spread))
            }
        }
    }
    drawRoundRect(surface, cornerRadius = CornerRadius(r))
}.then(if (inset) Modifier.clip(RoundedCornerShape(radius.dp)).drawBehind {
    // Inset light and shade remain supplementary to the accessible control outline.
    for (i in 7 downTo 1) {
        val width = i.dp.toPx()
        drawRoundRect(Color(0xFFD1D4C9).copy(alpha = .10f), topLeft = Offset(-width / 2, -width / 2),
            cornerRadius = CornerRadius(radius.dp.toPx()), style = Stroke(width))
        drawRoundRect(Color.White.copy(alpha = .10f), topLeft = Offset(width / 2, width / 2),
            cornerRadius = CornerRadius(radius.dp.toPx()), style = Stroke(width))
    }
} else Modifier)

@Composable
fun PebbleInput(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, errorMessage: String? = null,
    placeholder: String? = null, help: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled,
            label = { PebbleText(label) },
            placeholder = placeholder?.let { { PebbleText(it) } },
            visualTransformation = PebbleEditableText,
            isError = errorMessage != null, minLines = 1, maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PebbleVisuals.Focus),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).relief(inset = true)
                .semantics { if (errorMessage != null) error(errorMessage) },
        )
        val message = errorMessage ?: help
        if (message != null) {
            PebbleText(message, Modifier.padding(start = 16.dp, top = 4.dp),
                color = if (errorMessage == null) PebbleVisuals.Muted else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PebbleTag(
    label: String, selected: Boolean, onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, origin: String? = null, needsReview: Boolean = false,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val ink = if (enabled) PebbleVisuals.Moss else PebbleVisuals.Outline
    Column(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .then(if (enabled) Modifier.relief(inset = selected || pressed) else Modifier.background(PebbleVisuals.Sand, shape))
        .then(if (focused) Modifier.border(2.dp, PebbleVisuals.Focus, shape) else Modifier)
        .clip(shape)
        .toggleable(value = selected, interactionSource = interactions, indication = ripple(),
            enabled = enabled, role = Role.Checkbox, onValueChange = onSelectedChange)
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) StatusIcon(PebbleFeedbackState.SAVED, ink)
            PebbleText(label, color = ink, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
        origin?.let { PebbleText(it, color = ink, style = MaterialTheme.typography.bodyMedium) }
        if (needsReview) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusIcon(PebbleFeedbackState.REVIEW, PebbleFeedbackState.REVIEW.ink)
                PebbleText("待核对", style = MaterialTheme.typography.bodyMedium, color = PebbleFeedbackState.REVIEW.ink)
            }
        }
    }
}

enum class PebbleFeedbackState(val label: String, val ink: Color, val surface: Color) {
    SAVED("已保存", Color(0xFF3E6246), Color(0xFFE5EFE2)),
    PROCESSING("正在整理", Color(0xFF3C6470), Color(0xFFE6EEF0)),
    REVIEW("待核对", Color(0xFF775C29), Color(0xFFF3EAD2)),
    FAILED("保存失败", Color(0xFF934C47), Color(0xFFF5E6E2)),
    PROCESSING_FAILED("整理失败", Color(0xFF934C47), Color(0xFFF5E6E2)),
    WAITING("等待条件", Color(0xFF775C29), Color(0xFFF3EAD2)),
    UPDATED("已更新", Color(0xFF3E6246), Color(0xFFE5EFE2)),
}

// Iconoir Regular check/info/warning paths, from assets/icons (MIT; original LICENSE retained).
private val infoPaths = listOf("M12 11.5V16.5", "M12 7.51L12.01 7.49889",
    "M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22Z")
private val warningPaths = listOf("M20.0429 21H3.95705C2.41902 21 1.45658 19.3364 2.22324 18.0031L10.2662 4.01533C11.0352 2.67792 12.9648 2.67791 13.7338 4.01532L21.7768 18.0031C22.5434 19.3364 21.581 21 20.0429 21Z",
    "M12 9V13", "M12 17.01L12.01 16.9989")

@Composable
private fun StatusIcon(state: PebbleFeedbackState, color: Color) {
    val paths: List<Path> = remember(state) {
        (when (state) {
            PebbleFeedbackState.SAVED, PebbleFeedbackState.UPDATED -> listOf("M5 13L9 17L19 7")
            PebbleFeedbackState.REVIEW, PebbleFeedbackState.FAILED, PebbleFeedbackState.PROCESSING_FAILED -> warningPaths
            else -> infoPaths
        }).map { PathParser().parsePathString(it).toPath() }
    }
    Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
        scale(size.width / 24, size.height / 24, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

@Composable
fun PebbleFeedback(
    state: PebbleFeedbackState, modifier: Modifier = Modifier, detail: String? = null,
    onRetry: (() -> Unit)? = null, onContinue: (() -> Unit)? = null, onCancel: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().background(state.surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusIcon(state, state.ink)
                PebbleText(state.label, color = state.ink, modifier = Modifier.weight(1f))
            }
            detail?.let { PebbleText(it, color = state.ink) }
        }
        if ((state == PebbleFeedbackState.FAILED || state == PebbleFeedbackState.PROCESSING_FAILED) && onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                PebbleText(if (state == PebbleFeedbackState.FAILED) "重试保存" else "重试整理")
            }
        }
        if (onContinue != null) TextButton(onClick = onContinue) { PebbleText("继续") }
        if (onCancel != null) TextButton(onClick = onCancel) { PebbleText("取消") }
    }
}

// Each source owns its own callback. The page binds its record, source version and quote range.
@Composable
fun PebbleEvidenceSource(
    quote: String, sourceLabel: String, onOpenSource: () -> Unit,
    sourceAvailable: Boolean = true, unavailableReason: String = "来源暂不可用",
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PebbleText(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = PebbleVisuals.Muted)
        PebbleText(quote, style = MaterialTheme.typography.bodyLarge)
        if (!sourceAvailable) PebbleText(unavailableReason, color = PebbleVisuals.Muted)
        TextButton(onClick = onOpenSource, enabled = sourceAvailable,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                onClick(label = "查看原文，$sourceLabel", action = null)
            }) { PebbleText("查看原文") }
    }
}

@Composable
fun PebbleEvidenceCard(
    condition: String, onCorrect: () -> Unit, modifier: Modifier = Modifier,
    origin: String? = null, sources: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().relief(radius = 24, surface = PebbleVisuals.Content).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        origin?.let { PebbleText(it, style = MaterialTheme.typography.bodyMedium, color = PebbleVisuals.Muted) }
        PebbleText(condition, color = PebbleVisuals.Moss)
        sources()
        TextButton(onClick = onCorrect, modifier = Modifier.heightIn(min = 48.dp)) { PebbleText("纠正条件") }
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
