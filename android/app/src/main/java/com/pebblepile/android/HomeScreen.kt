package com.pebblepile.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.CancellationException
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
    onOpenRecord: (String) -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var operationId by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentTask by remember(receiptJobId) { mutableStateOf<CoreTask?>(null) }
    var taskReadError by remember(receiptJobId) { mutableStateOf<String?>(null) }
    var readAttempt by remember(receiptJobId) { mutableStateOf(0) }
    var assist by rememberSaveable { mutableStateOf<InputAssist?>(null) }
    var assistText by rememberSaveable { mutableStateOf("") }

    fun editDraft(text: String) {
        if (text != draft) {
            operationId = null
            saveError = null
            onDraftChange(text)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) editDraft(appendDraft(draft, spoken))
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

    LaunchedEffect(receiptJobId, readAttempt) {
        val jobId = receiptJobId ?: return@LaunchedEffect
        taskReadError = null
        try {
            currentTask = withContext(Dispatchers.IO) { api.getTask(jobId) }
            if (currentTask == null) taskReadError = "找不到本条记录的整理任务。"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            taskReadError = e.message ?: "读取整理进度失败。"
        }
    }

    fun submit() {
        if (saving || draft.isBlank()) return
        saving = true
        saveError = null
        val id = operationId ?: UUID.randomUUID().toString().also { operationId = it }
        scope.launch(Dispatchers.Main.immediate) {
            val receipt: ApiReceipt = try {
                withContext(Dispatchers.IO) {
                    api.recordExperience(
                        text = draft,
                        captureMethod = if (captureMode == PebbleCaptureMode.Chat) "app_home_dialog" else "app_home_record",
                        operationId = id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = e.message ?: "保存失败，请重试"
                return@launch
            } finally {
                saving = false
            }
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

    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // With the IME or large text, let the whole form scroll so all actions remain reachable.
        val compact = maxHeight < 440.dp
        val pageScroll = rememberScrollState()
        Column(if (compact) Modifier.fillMaxSize().verticalScroll(pageScroll) else Modifier.fillMaxSize()) {
            PebbleCaptureModeSelector(
                mode = captureMode,
                onModeChange = { next ->
                    if (next != captureMode) {
                        operationId = null
                        saveError = null
                        onCaptureModeChange(next)
                    }
                },
                enabled = !saving,
            )
            Column(
                if (compact) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                PebbleText("留下今天的原话", style = MaterialTheme.typography.headlineSmall)

                val receipt = receiptRecordId?.let { recordId ->
                    HomeReceipt(receiptMessage.orEmpty(), recordId, receiptJobId)
                }
                receipt?.let {
                    SavedProgressCard(
                        receipt = it,
                        task = currentTask,
                        readError = taskReadError,
                        retryRead = { readAttempt++ },
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
            }

            Spacer(Modifier.height(12.dp))
            PebbleComposer(
                value = draft,
                onValueChange = ::editDraft,
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
    }

    assist?.let { mode ->
        PebbleDialog(
            title = if (mode == InputAssist.Material) "添加材料" else "语音输入不可用",
            onDismiss = { assist = null },
            confirmLabel = if (mode == InputAssist.Material) "添加到草稿" else "写入草稿",
            confirmEnabled = assistText.isNotBlank(),
            onConfirm = {
                editDraft(appendDraft(draft, assistText.trim()))
                assist = null
            },
        ) {
            PebbleText(
                if (mode == InputAssist.Material) {
                    "这段文字会追加到草稿末尾，随原话一起保存。"
                } else {
                    "本机暂时无法识别语音，可以手动输入这段话。"
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
private fun SavedProgressCard(
    receipt: HomeReceipt, task: CoreTask?, readError: String?, retryRead: () -> Unit, onOpenRecord: () -> Unit,
) {
    val state = task?.feedbackState() ?: PebbleFeedbackState.WAITING
    PebbleCard {
        PebbleStatusBadge(PebbleFeedbackState.SAVED, label = "已保存")
        PebbleText(receipt.message, style = MaterialTheme.typography.bodyMedium)
        PebbleStatusBadge(state, label = when {
            receipt.jobId == null -> "未创建整理任务"
            readError != null -> "进度暂不可用"
            task == null -> "正在读取进度"
            else -> task.progressLabel()
        })
        PebbleText(
            when {
                receipt.jobId == null -> "可以在设置中开启自动整理。"
                readError != null -> readError
                task == null -> "可在记录详情查看进度。"
                else -> task.progressDescription()
            },
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (readError != null) PebbleButton("重新读取进度", retryRead, style = PebbleButtonStyle.Secondary)
        PebbleButton(
            "查看当前记录",
            onOpenRecord,
            style = PebbleButtonStyle.Secondary,
            icon = PebbleGlyph.ChevronRight,
        )
    }
}
