package com.pebblepile.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryScreen(
    api: CoreApi,
    refreshKey: Int,
    onOpen: (String) -> Unit,
    onMessage: (String, PebbleFeedbackState) -> Unit,
) {
    var records by remember { mutableStateOf<List<PebbleRecord>>(emptyList()) }
    var selectedTags by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by rememberSaveable { mutableStateOf(true) }
    var loadError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        runCatching { withContext(Dispatchers.IO) { api.queryRecords().records } }
            .onSuccess {
                records = it
                loadError = null
                val active = it.flatMap { record -> record.tags.filterNot { tag -> tag.excluded }.map { tag -> tag.name } }.toSet()
                selectedTags = selectedTags.filter { it in active }
            }
            .onFailure {
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
    val visible = records.filter { record ->
        val queryMatches = query.isBlank() ||
            record.rawText.contains(query, ignoreCase = true) ||
            record.title.contains(query, ignoreCase = true) ||
            record.tags.any { !it.excluded && it.name.contains(query, ignoreCase = true) }
        val tagsMatch = selectedTags.all { selected ->
            record.tags.any { !it.excluded && it.name == selected }
        }
        queryMatches && tagsMatch
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        PebbleText("时间线", style = MaterialTheme.typography.titleLarge)
        PebbleText(
            "通过时间和标签找回同一份原话。",
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        PebbleSearchField(query, { query = it }, {})
        Spacer(Modifier.height(12.dp))

        if (availableTags.isNotEmpty()) {
            PebbleTagGroup(Modifier.fillMaxWidth()) {
                PebbleTag(
                    label = "全部",
                    selected = selectedTags.isEmpty(),
                    onSelectedChange = { selectedTags = emptyList() },
                )
                availableTags.forEach { tag ->
                    PebbleTag(
                        label = tag,
                        selected = tag in selectedTags,
                        onSelectedChange = { selected ->
                            selectedTags = if (selected) selectedTags + tag else selectedTags - tag
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            loading -> PebbleFeedback(PebbleFeedbackState.PROCESSING, detail = "正在读取记录")
            loadError != null -> PebbleFeedback(
                PebbleFeedbackState.PROCESSING_FAILED,
                detail = loadError,
                onRetry = { onMessage("请返回后重新打开记录库", PebbleFeedbackState.WAITING) },
            )
            visible.isEmpty() -> PebbleCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PebbleIcon(PebbleGlyph.Library)
                    PebbleText(if (records.isEmpty()) "还没有记录" else "没有符合条件的记录")
                }
                PebbleText("可以从首页输入，或通过系统分享保存。", color = PebbleVisuals.Muted)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                visible.groupBy { it.capturedAt.take(10) }.forEach { (date, dateRecords) ->
                    item(key = "date_$date") {
                        PebbleText(date, color = PebbleVisuals.Moss, style = MaterialTheme.typography.labelLarge)
                    }
                    items(dateRecords, key = { it.id }) { record ->
                        PebbleCard {
                            PebbleListRow(
                                label = record.rawText,
                                detail = record.capturedAt,
                                icon = PebbleGlyph.Timeline,
                                onClick = { onOpen(record.id) },
                            )
                            if (record.tags.isNotEmpty()) {
                                PebbleTagGroup {
                                    record.tags.filterNot { it.excluded }.take(4).forEach { tag ->
                                        PebbleTag(
                                            label = tag.name,
                                            onClick = {
                                                selectedTags = listOf(tag.name)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
