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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ApiReceipt
import com.pebblepile.android.model.CapturedSource
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.CorrectionScope
import com.pebblepile.android.model.EvidenceItem
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

internal data class EvidenceAvailability(
    val source: CapturedSource? = null,
    val range: IntRange? = null,
    val reason: String? = null,
)

internal fun EvidenceItem.resolve(source: CapturedSource?): EvidenceAvailability {
    unavailableReason?.let { return EvidenceAvailability(reason = it) }
    if (source == null || source.ref != sourceRef) return EvidenceAvailability(reason = "来源版本已失效或被移除")
    val text = source.text
    if (start < 0 || end <= start || end > text.codePointCount(0, text.length)) {
        return EvidenceAvailability(reason = "引文区间已失效")
    }
    val from = text.offsetByCodePoints(0, start)
    val to = text.offsetByCodePoints(0, end)
    if (text.substring(from, to) != quote) return EvidenceAvailability(reason = "引文与该版本原话不一致")
    return EvidenceAvailability(source, from until to)
}

private fun CoreApi.resolveEvidence(item: EvidenceItem): EvidenceAvailability {
    val detail = getRecordDetail(item.sourceRecordId, item.sourceRef)
    return item.resolve(detail?.sources?.singleOrNull { it.ref == item.sourceRef })
}

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
    var evidenceAvailability by remember(recordId) { mutableStateOf<Map<String, String?>>(emptyMap()) }
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
    var selectedTagId by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var modifyingTagId by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var editFailure by remember(recordId) { mutableStateOf<String?>(null) }
    var modifiedTagName by rememberSaveable(recordId) { mutableStateOf("") }
    var notice by remember(recordId) { mutableStateOf<DetailNotice?>(null) }

    suspend fun reload(refreshEditingBase: Boolean = false) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            val detail = api.getRecordDetail(recordId)
            val loadedTasks = detail?.jobIds.orEmpty().mapNotNull(api::getTask)
            val evidence = detail?.record?.suggestions.orEmpty().flatMap { it.evidence }
            val availability = evidence.associate { item ->
                item.id to api.resolveEvidence(item).reason
            }
            Triple(detail, loadedTasks, availability)
        }
        record = loaded.first?.record
        futureCondition = loaded.first?.futureCondition
        tasks = loaded.second
        evidenceAvailability = loaded.third
        if (refreshEditingBase) {
            editingRecordRevision = loaded.first?.record?.revision ?: 0L
            editingPreferenceRevision = loaded.first?.futureCondition?.revision ?: 0L
        }
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
        editFailure = null
        conditionDraft = item.currentCondition
        correctionScope = item.conditionScope ?: CorrectionScope.THIS_TIME
        editingRecordRevision = item.revision
        editingPreferenceRevision = futureCondition?.revision ?: 0L
        correctionOperation = null
        showCorrection = true
    }

    fun openGoalEditor() {
        editFailure = null
        editingRecordRevision = item.revision
        goalTitleDraft = item.title.ifBlank { item.rawText.take(24) }
        goalStatusDraft = item.itemStatus
        goalOperation = null
        showGoalEditor = true
    }

    fun runTagAction(tag: RecordTag, action: TagAction, name: String? = null) {
        if (busyTagKey != null) return
        val baseRevision = if (action == TagAction.MODIFY) editingRecordRevision else item.revision
        val operationKey = "${tag.id}:${action.name}:${name.orEmpty()}"
        busyTagKey = operationKey
        val operationId = tagOperations[operationKey]
            ?: UUID.randomUUID().toString().also { tagOperations[operationKey] = it }
        scope.launch {
            val receipt = withContext(Dispatchers.IO) {
                api.reviseTag(item.id, baseRevision, tag.id, action, name, operationId)
            }
            busyTagKey = null
            when {
                receipt.success -> {
                    tagOperations.remove(operationKey)
                    selectedTagId = null
                    modifyingTagId = null
                    notice = DetailNotice(receipt.message, PebbleFeedbackState.UPDATED)
                    reload()
                    onChanged()
                }
                receipt.conflict -> conflict = receipt
                else -> editFailure = receipt.message
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
            PebbleTagGroup {
                item.tags.forEach { tag ->
                    PebbleTag(
                        label = if (tag.excluded) "${tag.name} · 已排除" else tag.name,
                        modifier = Modifier.testTag("record_tag_${tag.id}"),
                        needsReview = tag.reviewState == TagReviewState.NEEDS_REVIEW,
                        onClick = {
                            selectedTagId = tag.id
                            editFailure = null
                        },
                    )
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
                        val reason = evidenceAvailability[evidence.id]
                        PebbleEvidenceSource(
                            quote = if (reason == null) "“${evidence.quote}”" else "引文已失效",
                            sourceLabel = "${evidence.title} · ${evidence.recordDate} · ${evidence.sourceRef.sourceId} v${evidence.sourceRef.sourceVersion} · [${evidence.start},${evidence.end})",
                            onOpenSource = { evidenceId = evidence.id },
                            sourceAvailable = reason == null,
                            unavailableReason = reason ?: "来源暂不可用",
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
                label = "实际选择", enabled = !feedbackSaving,
            )
            PebbleInput(
                value = experience,
                onValueChange = {
                    experience = it
                    feedbackOperation = null
                },
                label = "实际体验／结果", enabled = !feedbackSaving,
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
                item.currentGoalCondition?.let { "本次适用条件：$it" } ?: "尚未设置本次适用条件。",
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
        var resolved by remember(evidence) { mutableStateOf<EvidenceAvailability?>(null) }
        LaunchedEffect(api, evidence) {
            val latest = withContext(Dispatchers.IO) { api.resolveEvidence(evidence) }
            evidenceAvailability = evidenceAvailability + (evidence.id to latest.reason)
            resolved = latest
        }
        resolved?.let { availability ->
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
                val source = availability.source
                val range = availability.range
                if (source == null || range == null) {
                    PebbleFeedback(
                        PebbleFeedbackState.PROCESSING_FAILED,
                        detail = availability.reason ?: "来源暂不可用",
                    )
                } else {
                    PebbleQuote(
                        highlightedSource(source.text, range),
                        source = "${source.capturedAt} · ${source.captureMethod}",
                    )
                }
            }
        }
    }

    val selectedTag = item.tags.firstOrNull { it.id == selectedTagId }
    if (selectedTag != null && modifyingTagId == null && conflict == null) {
        val ref = selectedTag.sourceRef
        var source by remember(recordId, ref) { mutableStateOf<CapturedSource?>(null) }
        var resolving by remember(recordId, ref) { mutableStateOf(ref != null) }
        LaunchedEffect(recordId, ref) {
            if (ref != null) {
                source = withContext(Dispatchers.IO) { api.getRecordDetail(recordId, ref)?.sources?.singleOrNull { it.ref == ref } }
                resolving = false
            }
        }
        PebbleSheet("标签依据", onDismiss = { if (busyTagKey == null) selectedTagId = null }) {
            PebbleText(selectedTag.name, style = MaterialTheme.typography.titleMedium)
            PebbleText("${selectedTag.reviewState.label} · 标签算法 ${selectedTag.algorithmVersion}", color = PebbleVisuals.Muted)
            when {
                resolving -> PebbleText("正在读取来源版本")
                ref == null -> PebbleText("这条旧标签尚待核对来源版本", color = PebbleVisuals.Muted)
                source?.text?.contains(selectedTag.sourceQuote) == true && selectedTag.sourceQuote.isNotEmpty() ->
                    PebbleQuote(selectedTag.sourceQuote, source = "原话版本 v${ref.sourceVersion} · ${ref.sourceId}")
                else -> PebbleText("来源版本已失效或引文已变更", color = PebbleVisuals.Warning)
            }
            editFailure?.let { PebbleFeedback(PebbleFeedbackState.FAILED, detail = it) }
            val enabled = busyTagKey == null
            PebbleTagGroup {
                if (selectedTag.excluded) {
                    PebbleLink("恢复", { runTagAction(selectedTag, TagAction.CONFIRM) }, enabled = enabled)
                } else {
                    PebbleLink("确认", { runTagAction(selectedTag, TagAction.CONFIRM) }, enabled = enabled)
                    PebbleLink("修改", {
                        editFailure = null
                        editingRecordRevision = item.revision
                        modifyingTagId = selectedTag.id
                        modifiedTagName = selectedTag.name
                    }, enabled = enabled)
                    PebbleLink("待核对", { runTagAction(selectedTag, TagAction.REVIEW) }, enabled = enabled)
                    PebbleLink("排除", { runTagAction(selectedTag, TagAction.EXCLUDE) }, enabled = enabled)
                }
            }
        }
    }

    item.tags.firstOrNull { it.id == modifyingTagId && conflict == null }?.let { tag ->
        PebbleDialog(
            title = "修改标签",
            onDismiss = { if (busyTagKey == null) modifyingTagId = null },
            confirmLabel = if (busyTagKey != null) "保存中…" else "确认修改",
            confirmEnabled = modifiedTagName.isNotBlank() && busyTagKey == null,
            onConfirm = {
                if (modifiedTagName.isNotBlank()) runTagAction(tag, TagAction.MODIFY, modifiedTagName.trim())
            },
        ) {
            editFailure?.let { PebbleFeedback(PebbleFeedbackState.FAILED, detail = it) }
            PebbleText("修改会保留算法依据，并把标签标记为人工确认。", color = PebbleVisuals.Muted)
            PebbleInput(
                value = modifiedTagName,
                onValueChange = { modifiedTagName = it },
                label = "标签名称", enabled = busyTagKey == null,
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
                        api.updateRecordGoal(item.id, editingRecordRevision, goalTitleDraft, goalStatusDraft, operationId)
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
                        else -> editFailure = receipt.message
                    }
                }
            },
        ) {
            editFailure?.let { PebbleFeedback(PebbleFeedbackState.FAILED, detail = it) }
            PebbleInput(
                value = goalTitleDraft,
                onValueChange = {
                    goalTitleDraft = it
                    goalOperation = null
                },
                label = "事项名称", enabled = !goalSaving,
            )
            PebbleText("当前事项状态", style = MaterialTheme.typography.labelLarge)
            PebbleSegmented(
                enabled = !goalSaving,
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
                        else -> editFailure = receipt.message
                    }
                }
            },
        ) {
            editFailure?.let { PebbleFeedback(PebbleFeedbackState.FAILED, detail = it) }
            item.currentGoalCondition?.let {
                PebbleText("本次目标条件：$it", color = PebbleVisuals.Muted)
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
                label = "新的适用条件", enabled = !correctionSaving,
            )
            PebbleText("影响范围", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CorrectionScope.entries.forEach { option ->
                    PebbleChoiceTile(
                        label = option.label,
                        selected = correctionScope == option, enabled = !correctionSaving,
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
                    reload(refreshEditingBase = true)
                    onChanged()
                }
            },
        ) {
            PebbleText(receipt.message)
            receipt.currentRecord?.let { current ->
                PebbleText("最新记录修订：${current.revision}", color = PebbleVisuals.Muted)
                PebbleText("本次目标条件：${current.currentGoalCondition ?: "未设置"}")
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
            PebbleStatusBadge(task.feedbackState(), label = task.progressLabel())
            PebbleText(task.kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            PebbleText("修订 ${task.revision}", color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodySmall)
        }
        PebbleText(
            task.progressDescription(),
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

internal fun CoreTask.feedbackState(): PebbleFeedbackState = when (state) {
    TaskState.QUEUED, TaskState.RUNNING -> PebbleFeedbackState.PROCESSING
    TaskState.WAITING -> if (waitReason == "user_paused") PebbleFeedbackState.PAUSED else PebbleFeedbackState.WAITING
    TaskState.NEEDS_REVIEW -> PebbleFeedbackState.REVIEW
    TaskState.COMPLETED -> PebbleFeedbackState.UPDATED
    TaskState.CANCELLED -> PebbleFeedbackState.PAUSED
    TaskState.FAILED, TaskState.STALE -> PebbleFeedbackState.PROCESSING_FAILED
}

internal fun CoreTask.progressLabel(): String = when (state) {
    TaskState.QUEUED -> "已排队"
    TaskState.RUNNING -> "正在整理"
    TaskState.WAITING -> if (waitReason == "user_paused") "已暂停" else "等待条件"
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

internal fun CoreTask.progressDescription(): String = when (state) {
    TaskState.QUEUED, TaskState.RUNNING -> when (phase) {
        "retry_queued" -> if (state == TaskState.QUEUED) "重试任务已排队。" else "正在重新处理。"
        "isolated" -> "资料已隔离，等待清理。"
        null -> if (state == TaskState.QUEUED) "等待开始处理。" else "任务正在处理。"
        else -> "当前阶段：$phase"
    }
    TaskState.WAITING -> when (waitReason) {
        "user_paused" -> "选择“继续”可恢复任务。"
        "network" -> "等待网络连接。"
        "model" -> "等待模型就绪。"
        "budget_exhausted" -> "处理预算已用完，等待追加额度。"
        null -> "等待处理条件就绪。"
        else -> "等待原因：$waitReason"
    }
    TaskState.NEEDS_REVIEW -> "请核对本次处理结果。"
    TaskState.COMPLETED -> "本次处理已完成。"
    TaskState.FAILED -> if (TaskAction.RETRY in allowedActions) "可以重试此任务。" else "本次处理失败。"
    TaskState.CANCELLED -> "本次任务已取消。"
    TaskState.STALE -> "任务依赖的输入已更新。"
}

private fun TaskAction.label(): String = when (this) {
    TaskAction.PAUSE -> "暂停"
    TaskAction.RESUME -> "继续"
    TaskAction.CANCEL -> "取消任务"
    TaskAction.RETRY -> "重试此任务"
}

private fun highlightedSource(text: String, range: IntRange): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(
        SpanStyle(background = PebbleVisuals.MossSoft, color = PebbleVisuals.Moss, fontWeight = FontWeight.Medium),
        range.first,
        range.last + 1,
    )
}
