package com.pebblepile.android

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** VI values from assets/design-tokens.json. Fonts are injected after license verification. */
object PebbleVisuals {
    val Sand = Color(0xFFF5F3EB)
    val Ink = Color(0xFF222C24)
    val Muted = Color(0xFF5E6B5D)
    val Moss = Color(0xFF536D58)
    val Outline = Color(0xFF788374)
}

@Composable
fun PebbleComponentTheme(cjk: FontFamily = FontFamily.Default, content: @Composable () -> Unit) {
    val body = TextStyle(fontFamily = cjk, fontWeight = FontWeight.Normal,
        fontSynthesis = FontSynthesis.None, fontSize = 16.sp, lineHeight = 26.sp)
    MaterialTheme(
        colorScheme = lightColorScheme(primary = PebbleVisuals.Moss,
            onPrimary = Color(0xFFFFFDF7), background = PebbleVisuals.Sand,
            surface = PebbleVisuals.Sand, onSurface = PebbleVisuals.Ink,
            onSurfaceVariant = PebbleVisuals.Muted, outline = PebbleVisuals.Outline,
            error = Color(0xFF934C47)),
        typography = Typography(bodyLarge = body, bodyMedium = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            titleMedium = body.copy(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
            labelLarge = body.copy(fontWeight = FontWeight.Medium)), content = content)
}

/** Layered soft relief, drawn in dp. Inset is clipped inside the control. */
private fun Modifier.relief(inset: Boolean = false, radius: Int = 16): Modifier = drawBehind {
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
    drawRoundRect(PebbleVisuals.Sand, cornerRadius = CornerRadius(r))
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
fun PebbleInput(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, errorMessage: String? = null) {
    OutlinedTextField(value = value, onValueChange = onValueChange,
        label = { Text(label) }, enabled = enabled, isError = errorMessage != null,
        supportingText = if (errorMessage != null) { { Text(errorMessage) } } else null,
        minLines = 1, maxLines = 4, shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).relief(inset = true)
            .semantics { if (errorMessage != null) error(errorMessage) })
}

@Composable
fun PebbleTag(label: String, selected: Boolean, onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, origin: String? = null, needsReview: Boolean = false,
    enabled: Boolean = true) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Column(modifier.heightIn(min = 48.dp).relief(inset = selected || pressed)
        .clip(RoundedCornerShape(16.dp))
        .toggleable(value = selected, interactionSource = interactions, indication = androidx.compose.material3.ripple(),
            enabled = enabled, role = Role.Checkbox, onValueChange = onSelectedChange)
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = PebbleVisuals.Moss,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        origin?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (needsReview) PebbleFeedback(PebbleFeedbackState.REVIEW)
    }
}

enum class PebbleFeedbackState(val label: String, val ink: Color, val surface: Color) {
    SAVED("已保存", Color(0xFF3E6246), Color(0xFFE5EFE2)),
    PROCESSING("正在整理", Color(0xFF3C6470), Color(0xFFE6EEF0)),
    REVIEW("待核对", Color(0xFF775C29), Color(0xFFF3EAD2)),
    FAILED("保存失败", Color(0xFF934C47), Color(0xFFF5E6E2))
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
            PebbleFeedbackState.SAVED -> listOf("M5 13L9 17L19 7")
            PebbleFeedbackState.REVIEW, PebbleFeedbackState.FAILED -> warningPaths
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
fun PebbleFeedback(state: PebbleFeedbackState, modifier: Modifier = Modifier,
    detail: String? = null, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(state.surface, RoundedCornerShape(16.dp))
        .padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusIcon(state, state.ink)
            Text(state.label, color = state.ink, modifier = Modifier.weight(1f))
        }
        detail?.let { Text(it, color = state.ink) }
        if (state == PebbleFeedbackState.FAILED && onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
}

@Composable
fun PebbleEvidenceCard(quote: String, sourceLabel: String, condition: String,
    onOpenSource: () -> Unit, onCorrect: () -> Unit, modifier: Modifier = Modifier,
    sourceAvailable: Boolean = true) {
    Column(modifier.fillMaxWidth().relief(radius = 24).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = PebbleVisuals.Muted)
        Text(quote, style = MaterialTheme.typography.bodyLarge)
        Text(condition, color = PebbleVisuals.Moss)
        if (!sourceAvailable) Text("来源暂不可用", color = PebbleVisuals.Muted)
        // Vertical actions remain reachable with long labels and 200% font scaling.
        TextButton(onClick = onOpenSource, enabled = sourceAvailable,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("查看原文") }
        TextButton(onClick = onCorrect, modifier = Modifier.heightIn(min = 48.dp)) { Text("纠正条件") }
    }
}

/** Interactive preview fixtures; does not call CoreAPI or report real persistence. */
@Preview(name = "360dp", widthDp = 360, heightDp = 800)
@Preview(name = "360dp · 200%", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
fun PebbleComponentsPreview() {
    PebbleComponentTheme {
        var text by rememberSaveable { mutableStateOf("今天工作日加班，预算二十元，希望少洗几个碗。") }
        var selected by rememberSaveable { mutableStateOf(false) }
        var correcting by rememberSaveable { mutableStateOf(false) }
        var source by rememberSaveable { mutableStateOf(false) }
        var condition by rememberSaveable { mutableStateOf("加班时优先省事") }
        var retrying by rememberSaveable { mutableStateOf(false) }
        var draft by rememberSaveable { mutableStateOf(condition) }
        Column(Modifier.fillMaxSize().background(PebbleVisuals.Sand)
            .verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PebbleInput(text, { text = it }, "记录原话")
            PebbleTag("工作日加班时优先省事", selected, { selected = it }, origin = "自动整理", needsReview = true)
            PebbleFeedback(PebbleFeedbackState.SAVED, detail = "样例状态，不代表实际保存")
            PebbleFeedback(PebbleFeedbackState.PROCESSING)
            PebbleFeedback(if (retrying) PebbleFeedbackState.PROCESSING else PebbleFeedbackState.FAILED,
                detail = "交互样例，不执行真实保存", onRetry = { retrying = true })
            PebbleEvidenceCard(text, "虚构晚餐记录 · 本人原话", condition,
                onOpenSource = { source = true }, onCorrect = { draft = condition; correcting = true })
        }
        if (source) AlertDialog(onDismissRequest = { source = false }, title = { Text("原话") },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { source = false }) { Text("关闭") } })
        if (correcting) AlertDialog(onDismissRequest = { correcting = false }, title = { Text("纠正条件") },
            text = { PebbleInput(draft, { draft = it }, "适用条件") },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { condition = draft; correcting = false }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { correcting = false }) { Text("取消") } })
    }
}
