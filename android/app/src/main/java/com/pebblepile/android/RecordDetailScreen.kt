package com.pebblepile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.CoreTask
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.RecordTag
import com.pebblepile.android.model.TagAction
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.model.TaskAction
import com.pebblepile.android.model.TaskState
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class DetailNotice(
    val message: String,
    val state: PebbleFeedbackState,
    val retry: (() -> Unit)? = null,
)

private data class EvidenceAvailability(
    val source: CapturedSource?,
    val reason: String?,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RecordDetailScreen(
    api: CoreApi,
    recordId: String,
    inferenceAssistEnabled: Boolean,
    onChanged: () -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var record by remember(recordId) { mutableStateOf<PebbleRecord?>(null) }
    var futureCondition by remember(recordId) { mutableStateOf<ConditionSetting?>(null) }
    var tasks by remember(recordId) { mutableStateOf<List<CoreTask>>(emptyList()) }
    var evidenceAvailability by remember(recordId) { mutableStateOf<Map<String, EvidenceAvailability>>(emptyMap()) }
    var loading by remember(recordId) { mutableStateOf(true) }
    var choice by rememberSaveable(recordId) { mutableStateOf("") }
    var experience by rememberSaveable(recordId) { mutableStateOf("") }
    var conditionDraft by rememberSaveable(recordId) { mutableStateOf("") }
    var correctionScope by rememberSaveable(recordId) { mutableStateOf(CorrectionScope.THIS_TIME) }
    var editingRecordRevision by rememberSaveable(recordId) { mutableLongStateOf(0L) }
    var editingPreferenceRevision by rememberSaveable(recordId) { mutableLongStateOf(0L) }
    var showCorrection by rememberSaveable(recordId) { mutableStateOf(false) }
    var showGoalEditor by rememberSaveable(recordId) { mutableStateOf(false) }
    var goalTitleDraft by rememberSaveable(recordId) { mutableStateOf("") }
    var goalStatusDraft by rememberSaveable(recordId) { mutableStateOf(ItemStatus.OPEN) }
    var feedbackSaving by remember(recordId) { mutableStateOf(false) }
    var correctionSaving by remember(recordId) { mutableStateOf(false) }
    var goalSaving by remember(recordId) { mutableStateOf(false) }
    var busyTagKey by remember(recordId) { mutableStateOf<String?>(null) }
    var busyTaskId by remember(recordId) { mutableStateOf<String?>(null) }
    var feedbackOperation by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var correctionOperation by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var goalOperation by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    val tagOperations = remember(recordId) { mutableMapOf<String, String>() }
    val taskOperations = remember(recordId) { mutableMapOf<String, String>() }
    var conflict by remember(recordId) { mutableStateOf<ApiReceipt?>(null) }
    var evidenceId by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var modifyingTag by remember(recordId) { mutableStateOf<RecordTag?>(null) }
    var modifiedTagName by rememberSaveable(recordId) { mutableStateOf("") }
    var notice by remember(recordId) { mutableStateOf<DetailNotice?>(null) }

    suspend fun reload() {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            val detail = api.getRecordDetail(recordId)
            val loadedTasks = detail?.jobIds.orEmpty().mapNotNull(api::getTask)
            val evidence = detail?.record?.suggestions.orEmpty().flatMap { it.evidence }
            val availability = evidence.associate { item ->
                val sourceDetail = api.getRecordDetail(item.sourceRecordId, item.sourceRef)
                item.id to EvidenceAvailability(
                    source = sourceDetail?.sources?.singleOrNull { it.ref == item.sourceRef },
                    reason = if (sourceDetail == null) {
                        item.unavailableReason ?: "来源版本已失效或被移除"
                    } else null,
                )
            }
            Triple(detail, loadedTasks, availability)
        }
        record = loaded.first?.record
        futureCondition = loaded.first?.futureCondition
        tasks = loaded.second
        evidenceAvailability = loaded.third
        editingRecordRevision = loaded.first?.record?.revision ?: 0L
        editingPreferenceRevision = loaded.first?.futureCondition?.revision ?: 0L
        loading = false
    }

    LaunchedEffect(recordId) { reload() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PebbleFeedback(PebbleFeedbackState.PROCESSING, detail = "正在读取记录")
        }
        return
    }
    val item = record
    if (item == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            PebbleCard {
                PebbleText("没有找到这条记录")
                PebbleText("返回记录库后重试。", color = PebbleVisuals.Muted)
            }
        }
        return
    }

    fun openCorrection() {
        conditionDraft = item.currentCondition
        correctionScope = item.conditionScope ?: CorrectionScope.THIS_TIME
        editingRecordRevision = item.revision
        editingPreferenceRevision = futureCondition?.revision ?: 0L
        correctionOperation = null
        showCorrection = true
    }

    fun openGoalEditor() {
        goalTitleDraft = item.title.ifBlank { item.rawText.take(24) }
        goalStatusDraft = item.itemStatus
        goalOperation = null
        showGoalEditor = true
    }

    fun runTagAction(tag: RecordTag, action: TagAction, name: String? = null) {
        if (busyTagKey != null) return
        val operationKey = "${tag.id}:${action.name}:${name.orEmpty()}"
        busyTagKey = operationKey
        val operationId = tagOperations[operationKey]
            ?: UUID.randomUUID().toString().also { tagOperations[operationKey] = it }
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                api.reviseTag(item.id, item.revision, tag.id, action, name, operationId)
            }
            busyTagKey = null
            when {
                receipt.success -> {
                    tagOperations.remove(operationKey)
                    modifyingTag = null
                    notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                    reload()
                    onChanged()
                }
                receipt.conflict -> conflict = receipt
                else -> notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED)
            }
        }
    }

    fun submitFeedback() {
        if (feedbackSaving) return
        feedbackSaving = true
        val operationId = feedbackOperation ?: UUID.randomUUID().toString().also { feedbackOperation = it }
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                api.recordOutcome(item.id, item.revision, choice, experience, operationId)
            }
            feedbackSaving = false
            when {
                receipt.success -> {
                    feedbackOperation = null
                    choice = ""
                    experience = ""
                    notice = DetailNotice(receipt.message, PebbleFeedbackState.SAVED)
                    reload()
                    onChanged()
                }
                receipt.conflict -> conflict = receipt
                else -> notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED, ::submitFeedback)
            }
        }
    }

    fun controlTask(task: CoreTask, action: TaskAction) {
        if (busyTaskId != null) return
        busyTaskId = task.id
        val operationKey = "${task.id}:${action.name}"
        val operationId = taskOperations[operationKey]
            ?: UUID.randomUUID().toString().also { taskOperations[operationKey] = it }
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                api.controlTask(task.id, task.revision, action, operationId)
            }
            busyTaskId = null
            when {
                receipt.success -> {
                    taskOperations.remove(operationKey)
                    notice = DetailNotice(
                        when (action) {
                            TaskAction.PAUSE -> "任务已暂停"
                            TaskAction.RESUME -> "任务已继续"
                            TaskAction.CANCEL -> "任务已取消，原记录仍保留"
                            TaskAction.RETRY -> "已重试该整理任务"
                        },
                        PebbleFeedbackState.UPDATED,
                    )
                    reload()
                    onChanged()
                }
                receipt.conflict -> conflict = receipt
                else -> notice = DetailNotice(receipt.message, PebbleFeedbackState.PROCESSING_FAILED)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))

        SectionTitle("当前事项")
        PebbleCard {
            PebbleText(item.title.ifBlank { "未命名事项" }, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PebbleStatusBadge(itemStatusFeedback(item.itemStatus), label = item.itemStatus.label)
                if (item.people.isNotEmpty()) {
                    PebbleText("人物：${item.people.joinToString("、")}", color = PebbleVisuals.Muted)
                }
            }
            PebbleText(
                item.currentGoalCondition?.let { "本次目标条件：$it" }
                    ?: item.currentCondition.takeIf { it.isNotBlank() }?.let { "当前条件：$it" }
                    ?: "尚未补充当前目标条件。",
                color = PebbleVisuals.Muted,
            )
            PebbleButton(
                "编辑事项信息",
                ::openGoalEditor,
                style = PebbleButtonStyle.Secondary,
                icon = PebbleGlyph.Edit,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("原始记录")
        PebbleCard {
            PebbleQuote(
                item.rawText,
                source = "${item.capturedAt} · ${if (item.captureMethod == "android_share") "来自系统分享" else "应用内记录"}",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PebbleStatusBadge(PebbleFeedbackState.SAVED, label = item.status)
                if (item.tags.any { it.reviewState == TagReviewState.NEEDS_REVIEW }) {
                    PebbleStatusBadge(PebbleFeedbackState.REVIEW, label = "有标签待核对")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("标签与依据")
        PebbleCard {
            if (item.tags.isEmpty()) {
                PebbleText("暂时没有标签", color = PebbleVisuals.Muted)
            }
            item.tags.forEach { tag ->
                PebbleTag(
                    label = if (tag.excluded) "${tag.name} · 已排除" else tag.name,
                    origin = "${tag.reviewState.label} · ${tag.algorithmVersion}",
                    needsReview = tag.reviewState == TagReviewState.NEEDS_REVIEW,
                )
                PebbleText("依据：${tag.sourceQuote}", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodySmall)
                PebbleTagGroup(Modifier.fillMaxWidth()) {
                    if (!tag.excluded) {
                        PebbleLink("确认", { runTagAction(tag, TagAction.CONFIRM) }, enabled = busyTagKey == null)
                        PebbleLink("修改", {
                            modifyingTag = tag
                            modifiedTagName = tag.name
                        }, enabled = busyTagKey == null)
                        PebbleLink("待核对", { runTagAction(tag, TagAction.REVIEW) }, enabled = busyTagKey == null)
                        PebbleLink(
                            if (busyTagKey?.startsWith("${tag.id}:EXCLUDE") == true) "排除中…" else "排除",
                            { runTagAction(tag, TagAction.EXCLUDE) },
                            enabled = busyTagKey == null,
                        )
                    } else {
                        PebbleLink("恢复", { runTagAction(tag, TagAction.CONFIRM) }, enabled = busyTagKey == null)
                    }
                }
                if (busyTagKey?.startsWith("${tag.id}:") == true) {
                    PebbleText("正在更新该标签…", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("建议与来源")
        if (!inferenceAssistEnabled) {
            PebbleFeedback(
                PebbleFeedbackState.PAUSED,
                detail = "推断辅助已在设置中关闭。已有建议仍保留在资料库中，但当前详情不展示推断内容。",
            )
        } else if (item.suggestions.isEmpty()) {
            PebbleCard { PebbleText("暂时没有建议", color = PebbleVisuals.Muted) }
        } else {
            item.suggestions.forEach { suggestion ->
                PebbleEvidenceCard(
                    condition = suggestion.title,
                    onCorrect = ::openCorrection,
                    origin = suggestion.reason,
                ) {
                    suggestion.evidence.forEach { evidence ->
                        val availability = evidenceAvailability[evidence.id]
                        PebbleEvidenceSource(
                            quote = "“${evidence.quote}”",
                            sourceLabel = "${evidence.title} · ${evidence.recordDate} · ${evidence.sourceRef.sourceId} v${evidence.sourceRef.sourceVersion} · [${evidence.start},${evidence.end})",
                            onOpenSource = { evidenceId = evidence.id },
                            sourceAvailable = availability?.reason == null,
                            unavailableReason = availability?.reason ?: "来源暂不可用",
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("实际选择与结果")
        PebbleCard {
            if (item.outcomes.isEmpty()) {
                PebbleText("还没有补记实际选择与体验。", color = PebbleVisuals.Muted)
            }
            item.outcomes.forEach { outcome ->
                PebbleText("用户选择", color = PebbleVisuals.Moss, style = MaterialTheme.typography.labelLarge)
                PebbleQuote(outcome.choice.ifBlank { "未记录选择" })
                PebbleText("实际体验／结果", color = PebbleVisuals.Moss, style = MaterialTheme.typography.labelLarge)
                PebbleQuote(outcome.experience.ifBlank { "未记录体验" }, source = outcome.createdAt)
            }
            PebbleInput(
                value = choice,
                onValueChange = {
                    choice = it
                    feedbackOperation = null
                },
                label = "实际选择",
            )
            PebbleInput(
                value = experience,
                onValueChange = {
                    experience = it
                    feedbackOperation = null
                },
                label = "实际体验／结果",
            )
            PebbleButton(
                label = "保存实际反馈",
                onClick = ::submitFeedback,
                style = PebbleButtonStyle.Primary,
                enabled = choice.isNotBlank() || experience.isNotBlank(),
                busy = feedbackSaving,
                busyLabel = "保存中",
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("整理任务")
        if (tasks.isEmpty()) {
            PebbleFeedback(
                PebbleFeedbackState.WAITING,
                detail = "这条记录当前没有后台任务。自动整理关闭时，新记录不会创建整理任务。",
            )
        }
        tasks.forEach { task ->
            TaskProgressCard(
                task = task,
                busy = busyTaskId == task.id,
                onAction = { action -> controlTask(task, action) },
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("纠正适用条件")
        PebbleCard {
            PebbleText(
                if (item.currentCondition.isBlank()) "尚未设置当前事项条件" else "当前条件：${item.currentCondition}",
            )
            item.conditionScope?.let {
                PebbleText("适用范围：${it.label}", color = PebbleVisuals.Moss)
            }
            futureCondition?.let {
                PebbleText("长期条件：${it.text} · 修订 ${it.revision}", color = PebbleVisuals.Info)
            }
            PebbleText(
                "“仅本次”只更新当前事项；“以后按此条件”会更新长期条件并创建重建上下文任务。",
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            PebbleButton(
                "修改条件",
                ::openCorrection,
                style = PebbleButtonStyle.Secondary,
                icon = PebbleGlyph.Edit,
            )
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            PebbleFeedback(it.state, detail = it.message, onRetry = it.retry)
        }
        Spacer(Modifier.height(28.dp))
    }

    val evidence = item.suggestions.flatMap { it.evidence }.firstOrNull { it.id == evidenceId }
    if (evidence != null) {
        val availability = evidenceAvailability[evidence.id]
        PebbleDialog(
            title = "来源与原话",
            onDismiss = { evidenceId = null },
            confirmLabel = "关闭",
            cancelLabel = null,
            confirmEnabled = true,
            onConfirm = { evidenceId = null },
        ) {
            PebbleText("${evidence.title} · ${evidence.recordDate}", color = PebbleVisuals.Moss)
            PebbleText(
                "来源 ${evidence.sourceRef.sourceId} · 版本 ${evidence.sourceRef.sourceVersion} · 码点 [${evidence.start},${evidence.end})",
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            val source = availability?.source
            if (source == null) {
                PebbleFeedback(
                    PebbleFeedbackState.PROCESSING_FAILED,
                    detail = availability?.reason ?: evidence.unavailableReason ?: "来源暂不可用",
                )
            } else {
                PebbleQuote(
                    highlightedSource(source.text, evidence.start, evidence.end),
                    source = "${source.capturedAt} · ${source.captureMethod}",
                )
            }
        }
    }

    modifyingTag?.let { tag ->
        PebbleDialog(
            title = "修改标签",
            onDismiss = { modifyingTag = null },
            confirmLabel = if (busyTagKey != null) "保存中…" else "确认修改",
            confirmEnabled = modifiedTagName.isNotBlank() && busyTagKey == null,
            onConfirm = {
                if (modifiedTagName.isNotBlank()) runTagAction(tag, TagAction.MODIFY, modifiedTagName.trim())
            },
        ) {
            PebbleText("修改会保留算法依据，并把标签标记为人工确认。", color = PebbleVisuals.Muted)
            PebbleInput(
                value = modifiedTagName,
                onValueChange = { modifiedTagName = it },
                label = "标签名称",
            )
        }
    }

    if (showGoalEditor && conflict == null) {
        PebbleDialog(
            title = "编辑事项信息",
            onDismiss = { if (!goalSaving) showGoalEditor = false },
            confirmLabel = if (goalSaving) "保存中…" else "保存事项",
            confirmEnabled = goalTitleDraft.isNotBlank() && !goalSaving,
            onConfirm = {
                if (goalSaving || goalTitleDraft.isBlank()) return@PebbleDialog
                goalSaving = true
                val operationId = goalOperation ?: UUID.randomUUID().toString().also { goalOperation = it }
                scope.launch {
                    val receipt = withContext(Dispatchers.IO) {
                        api.updateRecordGoal(item.id, item.revision, goalTitleDraft, goalStatusDraft, operationId)
                    }
                    goalSaving = false
                    when {
                        receipt.success -> {
                            goalOperation = null
                            showGoalEditor = false
                            notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                            reload()
                            onChanged()
                        }
                        receipt.conflict -> conflict = receipt
                        else -> notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED)
                    }
                }
            },
        ) {
            PebbleInput(
                value = goalTitleDraft,
                onValueChange = {
                    goalTitleDraft = it
                    goalOperation = null
                },
                label = "事项名称",
            )
            PebbleText("当前事项状态", style = MaterialTheme.typography.labelLarge)
            PebbleSegmented(
                options = ItemStatus.entries.map { it.label },
                selectedIndex = ItemStatus.entries.indexOf(goalStatusDraft),
                onSelect = {
                    goalStatusDraft = ItemStatus.entries[it]
                    goalOperation = null
                },
            )
        }
    }

    if (showCorrection && conflict == null) {
        PebbleDialog(
            title = "纠正条件",
            onDismiss = { if (!correctionSaving) showCorrection = false },
            confirmLabel = if (correctionSaving) "处理中…" else "确认修改",
            confirmEnabled = conditionDraft.isNotBlank() && !correctionSaving,
            onConfirm = {
                if (correctionSaving || conditionDraft.isBlank()) return@PebbleDialog
                correctionSaving = true
                val operationId = correctionOperation ?: UUID.randomUUID().toString().also {
                    correctionOperation = it
                }
                scope.launch {
                    val receipt = withContext(Dispatchers.IO) {
                        api.reviseMemory(
                            item.id,
                            editingRecordRevision,
                            conditionDraft,
                            correctionScope,
                            operationId,
                            basePreferenceRevision = editingPreferenceRevision.takeIf {
                                correctionScope == CorrectionScope.FUTURE
                            },
                        )
                    }
                    correctionSaving = false
                    when {
                        receipt.success -> {
                            correctionOperation = null
                            showCorrection = false
                            notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                            reload()
                            onChanged()
                        }
                        receipt.conflict -> conflict = receipt
                        else -> notice = DetailNotice(receipt.message, PebbleFeedbackState.FAILED)
                    }
                }
            },
        ) {
            if (item.currentCondition.isNotBlank()) {
                PebbleText("当前条件：${item.currentCondition}", color = PebbleVisuals.Muted)
            }
            if (correctionScope == CorrectionScope.FUTURE) {
                futureCondition?.let { PebbleText("当前长期条件：${it.text} · 修订 ${it.revision}", color = PebbleVisuals.Muted) }
            }
            PebbleInput(
                value = conditionDraft,
                onValueChange = {
                    conditionDraft = it
                    correctionOperation = null
                },
                label = "新的适用条件",
            )
            PebbleText("影响范围", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CorrectionScope.entries.forEach { option ->
                    PebbleChoiceTile(
                        label = option.label,
                        selected = correctionScope == option,
                        onSelect = {
                            correctionScope = option
                            correctionOperation = null
                        },
                    )
                }
            }
            PebbleText(
                if (correctionScope == CorrectionScope.THIS_TIME) {
                    "只更新当前事项，不改变其他记录使用的长期条件。"
                } else {
                    "更新长期条件，并创建上下文重建任务。其他记录随后读取新的长期条件。"
                },
                color = PebbleVisuals.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    conflict?.let { receipt ->
        PebbleDialog(
            title = "检测到修订冲突",
            onDismiss = { conflict = null },
            confirmLabel = "查看最新版本",
            cancelLabel = null,
            onConfirm = {
                scope.launch {
                    conflict = null
                    reload()
                    onChanged()
                }
            },
        ) {
            PebbleText(receipt.message)
            receipt.currentRecord?.let { current ->
                PebbleText("最新记录修订：${current.revision}", color = PebbleVisuals.Muted)
                PebbleText("当前条件：${current.currentCondition.ifBlank { "未设置" }}")
                PebbleText("当前状态：${current.itemStatus.label}")
            }
            receipt.currentPreference?.let {
                PebbleText("最新长期条件：${it.text} · 修订 ${it.revision}")
            }
            PebbleText("当前输入草稿仍保留，请核对最新内容后再次提交。", color = PebbleVisuals.Muted)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskProgressCard(task: CoreTask, busy: Boolean, onAction: (TaskAction) -> Unit) {
    PebbleCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PebbleStatusBadge(taskFeedback(task.state), label = task.state.label())
            PebbleText(task.kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            PebbleText("修订 ${task.revision}", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PebbleText(
            task.phase?.let { "阶段：$it" }
                ?: task.waitReason?.let { "等待原因：${task.waitReason.label()}" }
                ?: "尚未返回阶段信息",
            color = PebbleVisuals.Muted,
        )
        if (task.allowedActions.isEmpty()) {
            PebbleText("当前任务没有可执行操作。", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            PebbleTagGroup(Modifier.fillMaxWidth()) {
                task.allowedActions.sortedBy { it.ordinal }.forEach { action ->
                    PebbleButton(
                        action.label(),
                        { onAction(action) },
                        style = if (action == TaskAction.CANCEL) PebbleButtonStyle.Danger else PebbleButtonStyle.Secondary,
                        enabled = !busy,
                        busy = busy,
                        busyLabel = "处理中",
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    PebbleText(text, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
}

private fun itemStatusFeedback(status: ItemStatus): PebbleFeedbackState = when (status) {
    ItemStatus.OPEN -> PebbleFeedbackState.WAITING
    ItemStatus.IN_PROGRESS -> PebbleFeedbackState.PROCESSING
    ItemStatus.WAITING -> PebbleFeedbackState.WAITING
    ItemStatus.NEEDS_REVIEW -> PebbleFeedbackState.REVIEW
    ItemStatus.COMPLETED -> PebbleFeedbackState.UPDATED
}

private fun taskFeedback(state: TaskState): PebbleFeedbackState = when (state) {
    TaskState.QUEUED, TaskState.RUNNING -> PebbleFeedbackState.PROCESSING
    TaskState.WAITING -> PebbleFeedbackState.WAITING
    TaskState.NEEDS_REVIEW -> PebbleFeedbackState.REVIEW
    TaskState.COMPLETED -> PebbleFeedbackState.UPDATED
    TaskState.FAILED, TaskState.STALE, TaskState.CANCELLED -> PebbleFeedbackState.PROCESSING_FAILED
}

private fun TaskState.label(): String = when (this) {
    TaskState.QUEUED -> "已排队"
    TaskState.RUNNING -> "正在整理"
    TaskState.WAITING -> "等待条件"
    TaskState.NEEDS_REVIEW -> "待核对"
    TaskState.COMPLETED -> "已完成"
    TaskState.FAILED -> "整理失败"
    TaskState.CANCELLED -> "已取消"
    TaskState.STALE -> "输入已失效"
}

private fun com.pebblepile.android.model.TaskKind.label(): String = when (name) {
    "ORGANIZE" -> "整理原话"
    "APPLY_FEEDBACK" -> "应用反馈"
    "REBUILD_CONTEXT" -> "重建上下文"
    "DELETE_CLEANUP" -> "清理已删除内容"
    else -> name
}

private fun String.label(): String = when (this) {
    "user_paused" -> "用户暂停"
    else -> this
}

private fun TaskAction.label(): String = when (this) {
    TaskAction.PAUSE -> "暂停"
    TaskAction.RESUME -> "继续"
    TaskAction.CANCEL -> "取消任务"
    TaskAction.RETRY -> "重试此任务"
}

private fun highlightedSource(text: String, start: Int, end: Int): AnnotatedString {
    val codePoints = text.codePointCount(0, text.length)
    val safeStart = start.coerceIn(0, codePoints)
    val safeEnd = end.coerceIn(safeStart, codePoints)
    val utf16Start = text.offsetByCodePoints(0, safeStart)
    val utf16End = text.offsetByCodePoints(0, safeEnd)
    return buildAnnotatedString {
        append(text)
        if (utf16End > utf16Start) {
            addStyle(
                SpanStyle(
                    background = PebbleVisuals.MossSoft,
                    color = PebbleVisuals.Moss,
                    fontWeight = FontWeight.Medium,
                ),
                utf16Start,
                utf16End,
            )
        }
    }
}
