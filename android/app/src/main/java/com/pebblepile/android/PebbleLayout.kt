package com.pebblepile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
fun PebbleTopBar(
    title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        PebbleText(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PebbleToolbar(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.heightIn(min = 64.dp).pebbleSurface(Relief.Raised, 32.dp).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content)
}

@Composable
fun PebbleComposer(
    value: String, onValueChange: (String) -> Unit, mode: PebbleCaptureMode, onSubmit: () -> Unit,
    modifier: Modifier = Modifier, onAdd: (() -> Unit)? = null, onVoice: (() -> Unit)? = null,
    submitting: Boolean = false, enabled: Boolean = true, error: String? = null,
    placeholder: String = "记点什么，或让我帮你处理", canSubmit: Boolean = value.isNotBlank(),
) {
    val action = if (mode == PebbleCaptureMode.Chat) "发送" else "保存"
    Column(modifier.fillMaxWidth().heightIn(min = 172.dp).pebbleSurface(Relief.Raised, 24.dp).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PebbleInput(value, onValueChange, "记录原话", enabled = enabled, errorMessage = error,
            placeholder = placeholder, showLabel = false)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            onAdd?.let { PebbleIconButton(PebbleGlyph.Add, "添加材料", it, style = PebbleIconButtonStyle.Plain, enabled = enabled) }
            Spacer(Modifier.weight(1f))
            onVoice?.let { PebbleIconButton(PebbleGlyph.Voice, "语音输入", it, style = PebbleIconButtonStyle.Plain, enabled = enabled) }
            PebbleIconButton(if (submitting) PebbleGlyph.Clock else PebbleGlyph.Send,
                if (submitting) "正在" + action else action, onSubmit, style = PebbleIconButtonStyle.Primary,
                enabled = enabled && !submitting && canSubmit)
        }
    }
}

/** A shared container for record summaries, evidence, settings groups and preferences. */
@Composable
fun PebbleCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().pebbleSurface(Relief.Raised, 24.dp).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/** A static quote, message or impact note. Optional actions keep their own focus and click targets. */
@Composable
fun PebbleQuote(text: String, modifier: Modifier = Modifier, source: String? = null,
                actions: (@Composable ColumnScope.() -> Unit)? = null) {
    PebbleQuote(AnnotatedString(text), modifier, source, actions)
}

@Composable
fun PebbleQuote(text: AnnotatedString, modifier: Modifier = Modifier, source: String? = null,
                actions: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth().background(PebbleVisuals.SurfaceMuted, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        source?.let { PebbleText(it, color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium) }
        PebbleText(text)
        actions?.invoke(this)
    }
}

@Composable
fun PebbleListRow(
    label: String, modifier: Modifier = Modifier, detail: String? = null, value: String? = null,
    icon: PebbleGlyph? = null, onClick: (() -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val action = if (onClick == null) Modifier else Modifier
        .then(if (pressed) Modifier.pebbleSurface(Relief.Inset, 12.dp) else Modifier)
        .pebbleFocus(focused, 12.dp).clip(RoundedCornerShape(12.dp))
        .clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).then(action).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { PebbleIcon(it) } }
        Column(Modifier.weight(1f)) {
            PebbleText(label)
            detail?.let { PebbleText(it, color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        value?.let { PebbleText(it, Modifier.widthIn(max = 104.dp), color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium) }
        if (onClick != null) {
            Box(Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) { PebbleIcon(PebbleGlyph.ChevronRight) }
        }
    }
}
