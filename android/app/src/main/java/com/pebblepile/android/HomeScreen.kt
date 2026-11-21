package com.pebblepile.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class InputAssist { Material, VoiceFallback }

private data class HomeReceipt(
    val message: String,
    val recordId: String,
    val jobId: String?,
)

@Composable
internal fun HomeEntryScreen(
    api: CoreApi,
    draft: String,
    onDraftChange: (String) -> Unit,
    captureMode: PebbleCaptureMode,
    onCaptureModeChange: (PebbleCaptureMode) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by rememberSaveable { mutableStateOf(false) }
    var operationId by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentTask by remember { mutableStateOf<CoreTask?>(null) }
    var assist by remember { mutableStateOf<InputAssist?>(null) }
    var assistText by rememberSaveable { mutableStateOf("") }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) onDraftChange(appendDraft(draft, spoken))
        }
    }

    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要补充的原话")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            assist = InputAssist.VoiceFallback
        }
    }

    LaunchedEffect(draft) {
        operationId = null
        saveError = null
    }

    LaunchedEffect(receiptJobId) {
        currentTask = receiptJobId?.let { jobId ->
            withContext(Dispatchers.IO) { api.getTask(jobId) }
        }
    }

    fun submit() {
        if (saving || draft.isBlank()) return
        saving = true
        saveError = null
        val id = operationId ?: UUID.randomUUID().toString().also { operationId = it }
        scope.launch {
            val receipt: ApiReceipt = withContext(Dispatchers.IO) {
                api.recordExperience(
                    text = draft,
                    captureMethod = if (captureMode == PebbleCaptureMode.Chat) "app_home_dialog" else "app_home_record",
                    operationId = id,
                )
            }
            saving = false
            if (receipt.success && receipt.recordId != null) {
                operationId = null
                receiptMessage = "已保存 · ${receipt.recordId}"
                receiptRecordId = receipt.recordId
                receiptJobId = receipt.jobId
                onSaved(receipt.recordId)
            } else {
                saveError = receipt.message.ifBlank { "保存失败，请重试" }
                onMessage(saveError!!, PebbleFeedbackState.FAILED)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            PebbleText("留下今天的原话", style = MaterialTheme.typography.headlineSmall)
            PebbleText(
                "切换对话或记录不会清空正在输入的内容。",
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )

            val receipt = receiptRecordId?.let { recordId ->
                HomeReceipt(receiptMessage.orEmpty(), recordId, receiptJobId)
            }
            receipt?.let {
                SavedProgressCard(
                    receipt = it,
                    task = currentTask,
                    onOpenRecord = { onOpenRecord(it.recordId) },
                )
            }

            saveError?.let {
                PebbleFeedback(
                    PebbleFeedbackState.FAILED,
                    detail = "$it\n草稿仍保留，可以直接重试同一保存请求。",
                    onRetry = ::submit,
                )
            }

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
            onAdd = {
                assistText = ""
                assist = InputAssist.Material
            },
            onVoice = ::launchVoiceInput,
            submitting = saving,
            enabled = !saving,
            error = saveError,
            canSubmit = draft.isNotBlank(),
        )
        Spacer(Modifier.height(16.dp))
    }

    assist?.let { mode ->
        PebbleDialog(
            title = if (mode == InputAssist.Material) "添加材料" else "语音输入不可用",
            onDismiss = { assist = null },
            confirmLabel = if (mode == InputAssist.Material) "添加到草稿" else "写入草稿",
            confirmEnabled = assistText.isNotBlank(),
            onConfirm = {
                onDraftChange(appendDraft(draft, assistText.trim()))
                assist = null
            },
        ) {
            PebbleText(
                if (mode == InputAssist.Material) {
                    "把补充材料写进当前草稿。保存后它仍是一条可回溯的原始材料。"
                } else {
                    "本机没有可用的系统语音识别入口。下面的内容会作为可编辑草稿写入，不是真实语音识别结果，也不会申请麦克风权限。"
                },
                color = PebbleVisuals.Muted,
            )
            PebbleInput(
                value = assistText,
                onValueChange = { assistText = it },
                label = if (mode == InputAssist.Material) "补充材料" else "手动转写文字",
                placeholder = if (mode == InputAssist.Material) "写下需要一起保存的材料" else "写下本应口述的内容",
            )
        }
    }
}

private fun appendDraft(draft: String, addition: String): String =
    if (draft.isBlank()) addition else "${draft.trimEnd()}\n$addition"

@Composable
private fun SavedProgressCard(receipt: HomeReceipt, task: CoreTask?, onOpenRecord: () -> Unit) {
    val state = when (task?.state) {
        null -> PebbleFeedbackState.WAITING
        TaskState.QUEUED, TaskState.RUNNING -> PebbleFeedbackState.PROCESSING
        TaskState.WAITING -> PebbleFeedbackState.WAITING
        TaskState.NEEDS_REVIEW -> PebbleFeedbackState.REVIEW
        TaskState.COMPLETED -> PebbleFeedbackState.UPDATED
        TaskState.FAILED, TaskState.STALE, TaskState.CANCELLED -> PebbleFeedbackState.PROCESSING_FAILED
    }
    PebbleCard {
        PebbleStatusBadge(PebbleFeedbackState.SAVED, label = "已保存")
        PebbleText(receipt.message, style = MaterialTheme.typography.bodyMedium)
        PebbleStatusBadge(state, label = if (task == null) "未创建整理任务" else state.label)
        PebbleText(
            task?.phase?.let { "阶段：$it" }
                ?: task?.waitReason?.let { "等待原因：$it" }
                ?: if (task == null) "可在设置中重新开启自动整理。" else "等待后台模块推进",
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        PebbleButton(
            "查看当前记录",
            onOpenRecord,
            style = PebbleButtonStyle.Secondary,
            icon = PebbleGlyph.ChevronRight,
        )
    }
}
