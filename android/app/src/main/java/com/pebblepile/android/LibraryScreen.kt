package com.pebblepile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryScreen(
    api: CoreApi,
    refreshKey: Int,
    view: PebbleLibraryView,
    onViewChange: (PebbleLibraryView) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedTags: Set<String>,
    onSelectedTagsChange: (Set<String>) -> Unit,
    selectedPerson: String?,
    onSelectedPersonChange: (String?) -> Unit,
    selectedStatus: ItemStatus?,
    onSelectedStatusChange: (ItemStatus?) -> Unit,
    onOpen: (String) -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
) {
    var records by remember { mutableStateOf<List<PebbleRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val focus = LocalFocusManager.current
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, refreshKey, retry) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { api.queryRecords().records } }
            .onSuccess {
                records = it
                loadError = null
                val activeTags = it.flatMap { record ->
                    record.tags.filterNot { tag -> tag.excluded }.map { tag -> tag.name }
                }.toSet()
                onSelectedTagsChange(selectedTags.intersect(activeTags))
                if (selectedPerson != null && it.none { record -> selectedPerson in record.people }) {
                    onSelectedPersonChange(null)
                }
                if (selectedStatus != null && it.none { record -> record.itemStatus == selectedStatus }) {
                    onSelectedStatusChange(null)
                }
            }
            .onFailure {
                if (it is CancellationException) throw it
                loadError = it.message ?: "记录库读取失败"
                onMessage("记录库读取失败", PebbleFeedbackState.FAILED)
            }
        loading = false
    }

    val availableTags = records.flatMap { it.tags }
        .filterNot { it.excluded }
        .map { it.name }
        .distinct()
        .sorted()
    val availablePeople = records.flatMap { it.people }.distinct().sorted()
    val availableStatuses = ItemStatus.entries.filter { status -> records.any { it.itemStatus == status } }
    val visible = records.filter { record ->
        val queryMatches = query.isBlank() ||
            record.rawText.contains(query, ignoreCase = true) ||
            record.title.contains(query, ignoreCase = true) ||
            record.tags.any { !it.excluded && it.name.contains(query, ignoreCase = true) } ||
            record.people.any { it.contains(query, ignoreCase = true) }
        val tagsMatch = selectedTags.all { selected ->
            record.tags.any { !it.excluded && it.name == selected }
        }
        queryMatches &&
            tagsMatch &&
            (selectedPerson == null || selectedPerson in record.people) &&
            (selectedStatus == null || record.itemStatus == selectedStatus)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "library_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(4.dp))
                PebbleSearchField(query, onQueryChange, { focus.clearFocus() }, Modifier.testTag("library_search"))
                PebbleLibraryViewSelector(view, onViewChange)
            }
        }

        if (availableTags.isNotEmpty()) {
            item(key = "tag_filters") {
                FilterGroup("标签") {
                    PebbleTag(
                        label = "全部",
                        selected = selectedTags.isEmpty(),
                        onSelectedChange = { onSelectedTagsChange(emptySet()) },
                        modifier = Modifier.testTag("filter_tag_all"),
                    )
                    availableTags.forEach { tag ->
                        PebbleTag(
                            label = tag,
                            selected = tag in selectedTags,
                            modifier = Modifier.testTag("filter_tag_$tag"),
                            onSelectedChange = { selected ->
                                onSelectedTagsChange(if (selected) selectedTags + tag else selectedTags - tag)
                            },
                        )
                    }
                }
            }
        }

        if (availablePeople.isNotEmpty()) {
            item(key = "people_filters") {
                FilterGroup("人物") {
                    PebbleTag(
                        label = "全部",
                        selected = selectedPerson == null,
                        modifier = Modifier.testTag("filter_person_all"),
                        onSelectedChange = { onSelectedPersonChange(null) },
                    )
                    availablePeople.forEach { person ->
                        PebbleTag(
                            label = person,
                            selected = selectedPerson == person,
                            modifier = Modifier.testTag("filter_person_$person"),
                            onSelectedChange = { selected ->
                                onSelectedPersonChange(if (selected) person else null)
                            },
                        )
                    }
                }
            }
        }

        if (availableStatuses.isNotEmpty()) {
            item(key = "status_filters") {
                FilterGroup("事项状态") {
                    PebbleTag(
                        label = "全部",
                        selected = selectedStatus == null,
                        modifier = Modifier.testTag("filter_status_all"),
                        onSelectedChange = { onSelectedStatusChange(null) },
                    )
                    availableStatuses.forEach { status ->
                        PebbleTag(
                            label = status.label,
                            selected = selectedStatus == status,
                            modifier = Modifier.testTag("filter_status_${status.name}"),
                            onSelectedChange = { selected ->
                                onSelectedStatusChange(if (selected) status else null)
                            },
                        )
                    }
                }
            }
        }

        when {
            loading -> item("loading") {
                PebbleFeedback(PebbleFeedbackState.PROCESSING, detail = "正在读取记录")
            }
            loadError != null -> item("error") {
                PebbleBanner("记录库读取失败", PebbleGlyph.Warning, detail = loadError, tone = PebbleVisuals.Danger) {
                    PebbleButton("重新读取", { retry++ }, icon = PebbleGlyph.Retry)
                }
            }
            visible.isEmpty() -> item("empty") {
                PebbleCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PebbleIcon(PebbleGlyph.Library)
                        PebbleText(if (records.isEmpty()) "还没有记录" else "没有符合当前筛选的记录")
                    }
                    PebbleText(
                        if (records.isEmpty()) "可以从首页输入，或通过系统分享保存。" else "调整搜索、人物、状态或标签筛选后重试。",
                        color = PebbleVisuals.Muted,
                    )
                }
            }
            view == PebbleLibraryView.Timeline -> {
                visible.groupBy { it.capturedAt.take(10) }.forEach { (date, dateRecords) ->
                    item(key = "date_$date") {
                        PebbleText(date, color = PebbleVisuals.Moss, style = MaterialTheme.typography.labelLarge)
                    }
                    items(dateRecords, key = { "timeline_${it.id}" }) { record ->
                        TimelineRecord(record, onOpen) {
                            onSelectedTagsChange(setOf(it))
                            onSelectedPersonChange(null)
                            onSelectedStatusChange(null)
                        }
                    }
                }
            }
            else -> {
                item(key = "notebook_title") {
                    PebbleText("记录本", style = MaterialTheme.typography.titleMedium)
                }
                items(visible, key = { "notebook_${it.id}" }) { record ->
                    PebbleCard(Modifier.testTag("library_record_${record.id}")) {
                        PebbleListRow(
                            label = record.title.ifBlank { record.rawText.take(28) },
                            detail = buildString {
                                append(record.capturedAt.take(16))
                                if (record.people.isNotEmpty()) append(" · ${record.people.joinToString("、")}")
                            },
                            value = record.itemStatus.label,
                            icon = PebbleGlyph.Library,
                            onClick = { onOpen(record.id) },
                        )
                        PebbleText(record.rawText, color = PebbleVisuals.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PebbleText(title, style = MaterialTheme.typography.labelLarge)
        PebbleTagGroup(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineRecord(record: PebbleRecord, onOpen: (String) -> Unit, onTagClick: (String) -> Unit) {
    PebbleCard {
        PebbleListRow(
            label = record.rawText,
            modifier = Modifier.testTag("library_record_${record.id}"),
            detail = "${record.capturedAt.take(16)} · ${record.itemStatus.label}",
            value = record.people.joinToString("、").ifBlank { null },
            icon = PebbleGlyph.Timeline,
            onClick = { onOpen(record.id) },
        )
        if (record.tags.any { !it.excluded }) {
            PebbleTagGroup {
                record.tags.filterNot { it.excluded }.take(5).forEach { tag ->
                    PebbleTag(label = tag.name, needsReview = tag.reviewState == TagReviewState.NEEDS_REVIEW,
                        onClick = { onTagClick(tag.name) })
                }
            }
        }
    }
}
