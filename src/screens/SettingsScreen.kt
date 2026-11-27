package com.pebblepile.android

import android.content.Intent
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.ConditionSetting
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.model.TagReviewState
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun SettingsHomeScreen(
    settings: PebbleSettings,
    onSettingsChange: (PebbleSettings) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRules: () -> Unit,
    onOpenData: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        PebbleCard {
            PebbleSwitch(
                "自动整理",
                settings.autoOrganize,
                { onSettingsChange(settings.copy(autoOrganize = it)) },
            )
            PebbleSwitch(
                "推断辅助",
                settings.inferenceAssist,
                { onSettingsChange(settings.copy(inferenceAssist = it)) },
            )
            PebbleText("文字大小", style = MaterialTheme.typography.labelLarge)
            PebbleSegmented(
                options = PebbleTextScale.entries.map { it.label },
                selectedIndex = PebbleTextScale.entries.indexOf(settings.textScale),
                onSelect = { index ->
                    onSettingsChange(settings.copy(textScale = PebbleTextScale.entries[index]))
                },
            )
        }

        PebbleCard {
            PebbleListRow(
                label = "记忆与偏好",
                detail = "查看当前事项条件与长期条件",
                icon = PebbleGlyph.Memory,
                onClick = onOpenMemory,
            )
            PebbleListRow(
                label = "标签与规则",
                detail = "查看本地规则生成的有效标签和核对状态",
                icon = PebbleGlyph.Tag,
                onClick = onOpenRules,
            )
            PebbleListRow(
                label = "本地数据",
                detail = "查看记录和来源版本数量，分享统计摘要",
                icon = PebbleGlyph.Export,
                onClick = onOpenData,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private data class MemoryOverview(val records: List<PebbleRecord>, val future: ConditionSetting?)

@Composable
internal fun MemorySettingsScreen(api: CoreApi, onOpenRecord: (String) -> Unit) {
    SettingsContent(api, load = {
        val records = api.queryRecords().records
        MemoryOverview(
            records.filter { !it.currentGoalCondition.isNullOrBlank() },
            records.firstOrNull()?.let { api.queryContext(it.id)?.futureCondition },
        )
    }) { memory ->
        memory.future?.let { preference ->
            PebbleCard {
                PebbleListRow("长期条件", detail = preference.text, icon = PebbleGlyph.Memory,
                    onClick = { onOpenRecord(preference.recordId) })
            }
        }
        memory.records.forEach { record ->
            PebbleCard {
                PebbleListRow(
                    label = record.title.ifBlank { record.rawText.take(24) },
                    detail = record.currentGoalCondition,
                    value = "仅本次",
                    icon = PebbleGlyph.Memory,
                    onClick = { onOpenRecord(record.id) },
                )
            }
        }
        if (memory.future == null && memory.records.isEmpty()) {
            PebbleText("在记录详情中设置条件后，可以在这里查看。", color = PebbleVisuals.Muted)
        }
    }
}

@Composable
internal fun TagRulesScreen(api: CoreApi) {
    SettingsContent(api, load = { api.queryRecords().records }) { records ->
        val tagged = records.filter { record -> record.tags.any { !it.excluded } }
        if (tagged.isEmpty()) PebbleText("还没有生效的标签。", color = PebbleVisuals.Muted)
        tagged.forEach { record ->
            PebbleCard {
                PebbleText(record.title.ifBlank { record.rawText.take(24) }, style = MaterialTheme.typography.titleMedium)
                record.tags.filterNot { it.excluded }.forEach { tag ->
                    PebbleTag(
                        label = tag.name,
                        origin = tag.algorithmVersion,
                        needsReview = tag.reviewState == TagReviewState.NEEDS_REVIEW,
                    )
                    PebbleText("${tag.reviewState.label} · ${tag.sourceQuote}", color = PebbleVisuals.Muted)
                }
            }
        }
    }
}

private data class DataSummary(val records: Int, val sources: Int, val byStatus: Map<ItemStatus, Int>)

@Composable
internal fun DataManagementScreen(api: CoreApi) {
    val context = LocalContext.current
    SettingsContent(api, load = {
        val records = api.queryRecords().records
        DataSummary(records.size, records.sumOf { api.getRecordDetail(it.id)?.sources?.size ?: 0 },
            records.groupingBy { it.itemStatus }.eachCount())
    }) { summary ->
        PebbleCard {
            PebbleListRow("本地记录", value = summary.records.toString(), icon = PebbleGlyph.Library)
            PebbleListRow("来源版本", value = summary.sources.toString(), icon = PebbleGlyph.Sources)
        }
        PebbleText("摘要包含记录数、来源版本数和各事项状态的数量。", color = PebbleVisuals.Muted)
        PebbleButton(
            "分享本地数据摘要",
            onClick = {
                val statuses = JSONObject().apply { summary.byStatus.forEach { (status, count) -> put(status.name, count) } }
                val payload = JSONObject().put("exportedRecords", summary.records)
                    .put("sourceVersions", summary.sources).put("statusCounts", statuses)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, payload.toString(2))
                context.startActivity(Intent.createChooser(intent, "分享 PebblePile 数据摘要"))
            },
            icon = PebbleGlyph.Export,
        )
    }
}

@Composable
private fun <T : Any> SettingsContent(api: CoreApi, load: () -> T, content: @Composable (T) -> Unit) {
    var value by remember(api) { mutableStateOf<T?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, attempt) {
        error = null
        try {
            value = withContext(Dispatchers.IO) { load() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        val problem = error
        val loaded = value
        when {
            loaded != null -> content(loaded)
            problem != null -> {
                PebbleText(problem, color = PebbleVisuals.Danger)
                PebbleButton("重新读取", { attempt++ }, style = PebbleButtonStyle.Secondary)
            }
            else -> PebbleText("正在读取", color = PebbleVisuals.Muted)
        }
        Spacer(Modifier.height(24.dp))
    }
}
