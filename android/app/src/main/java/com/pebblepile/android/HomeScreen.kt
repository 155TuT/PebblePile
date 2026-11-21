package com.pebblepile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun HomeEntryScreen(
    api: CoreApi,
    draft: String,
    onDraftChange: (String) -> Unit,
    captureMode: PebbleCaptureMode,
    onCaptureModeChange: (PebbleCaptureMode) -> Unit,
    onOpenLibrary: () -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by rememberSaveable { mutableStateOf(false) }
    var operationId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(draft) {
        operationId = null
    }

    fun submit() {
        if (saving || draft.isBlank()) return
        saving = true
        val id = operationId ?: UUID.randomUUID().toString().also { operationId = it }
        scope.launch {
            val receipt = api.recordExperience(
                text = draft,
                captureMethod = if (captureMode == PebbleCaptureMode.Chat) "app_home_dialog" else "app_home_record",
                operationId = id,
            )
            saving = false
            if (receipt.success && receipt.recordId != null) {
                operationId = null
                onSaved(receipt.recordId)
            } else {
                onMessage(receipt.message.ifBlank { "保存失败，请重试" }, PebbleFeedbackState.FAILED)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            PebbleText("留下今天的原话", style = MaterialTheme.typography.headlineSmall)
            PebbleText(
                "切换对话或记录不会清空正在输入的内容。",
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            PebbleCard {
                PebbleText("系统分享和应用内记录会进入同一份资料库。")
                PebbleButton(
                    "打开记录库",
                    onOpenLibrary,
                    style = PebbleButtonStyle.Secondary,
                    icon = PebbleGlyph.Library,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        PebbleCaptureModeSelector(
            mode = captureMode,
            onModeChange = onCaptureModeChange,
            enabled = !saving,
        )
        Spacer(Modifier.height(12.dp))
        PebbleComposer(
            value = draft,
            onValueChange = onDraftChange,
            mode = captureMode,
            onSubmit = ::submit,
            submitting = saving,
            enabled = !saving,
            canSubmit = draft.isNotBlank(),
        )
        Spacer(Modifier.height(16.dp))
    }
}
