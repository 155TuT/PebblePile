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
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        PebbleText("设置", style = MaterialTheme.typography.titleLarge)
        PebbleText(
            "这里的选项保存在本机演示层。自动整理会改变之后新记录的待办创建。",
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )

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
            PebbleText("字号与外观", style = MaterialTheme.typography.labelLarge)
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
                label = "数据处理与备份",
                detail = "查看本地记录、来源数量并分享数据摘要",
                icon = PebbleGlyph.Export,
                onClick = onOpenData,
            )
        }

        PebbleFeedback(
            PebbleFeedbackState.WAITING,
            detail = "当前为本地演示适配器：设置与资料库保存在 SharedPreferences。SQLite、加密备份与跨设备恢复由后续仓储模块接入。",
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun MemorySettingsScreen(
    api: CoreApi,
    onOpenRecord: (String) -> Unit,
) {
    val loaded = rememberRecords(api)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        PebbleText("记忆与偏好", style = MaterialTheme.typography.titleLarge)
        PebbleText(
            "长期条件来自记录详情中的“以后按此条件”；当前事项条件只在对应记录中生效。",
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        loaded.records.forEach { record ->
            PebbleCard {
                PebbleListRow(
                    label = record.title.ifBlank { record.rawText.take(24) },
                    detail = record.currentCondition.ifBlank { "尚未设置适用条件" },
                    value = record.conditionScope?.label,
                    icon = PebbleGlyph.Memory,
                    onClick = { onOpenRecord(record.id) },
                )
                if (record.conditionScope != null) {
                    PebbleStatusBadge(
                        PebbleFeedbackState.UPDATED,
                        label = "适用范围：${record.conditionScope.label}",
                    )
                }
            }
        }
        if (!loaded.loading && loaded.records.isEmpty()) {
            PebbleFeedback(PebbleFeedbackState.WAITING, detail = "还没有可调整的记忆或条件。")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun TagRulesScreen(api: CoreApi) {
    val loaded = rememberRecords(api)
    val tags = loaded.records.flatMap { it.tags }.distinctBy { it.name }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        PebbleText("标签与规则", style = MaterialTheme.typography.titleLarge)
        PebbleText(
            "以下标签由本地文本规则生成，不冒充算法结果。可在记录详情确认、修改、排除或标记待核对。",
            color = PebbleVisuals.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (tags.isEmpty() && !loaded.loading) {
            PebbleFeedback(PebbleFeedbackState.WAITING, detail = "还没有生成标签。")
        }
        tags.forEach { tag ->
            PebbleCard {
                PebbleTag(
                    label = tag.name,
                    origin = "${tag.algorithmVersion} · ${tag.reviewState.label}",
                    needsReview = tag.reviewState == com.pebblepile.android.model.TagReviewState.NEEDS_REVIEW,
                )
                PebbleText(
                    "依据：${tag.sourceQuote}",
                    color = PebbleVisuals.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun DataManagementScreen(api: CoreApi) {
    val context = LocalContext.current
    val loaded = rememberRecords(api)
    var sourceCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(loaded.records) {
        sourceCount = withContext(Dispatchers.IO) {
            loaded.records.sumOf { record -> api.getRecordDetail(record.id)?.sources?.size ?: 0 }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        PebbleText("数据处理与备份", style = MaterialTheme.typography.titleLarge)
        PebbleCard {
            PebbleListRow(
                label = "本地记录",
                value = loaded.records.size.toString(),
                icon = PebbleGlyph.Library,
            )
            PebbleListRow(
                label = "来源版本",
                value = sourceCount.toString(),
                icon = PebbleGlyph.Sources,
            )
            PebbleListRow(
                label = "备份位置",
                detail = "当前仅在应用私有 SharedPreferences 中保存",
                icon = PebbleGlyph.Connection,
            )
        }
        PebbleFeedback(
            PebbleFeedbackState.WAITING,
            detail = "当前没有完整加密备份导出。下面的操作会把资料摘要交给系统分享目标，便于交接检查，不会上传到应用服务器。",
        )
        PebbleButton(
            "分享本地数据摘要",
            onClick = {
                val payload = JSONObject()
                    .put("exportedRecords", loaded.records.size)
                    .put("sourceVersions", sourceCount)
                    .put("records", JSONArray().also { array ->
                        loaded.records.forEach { record ->
                            array.put(
                                JSONObject()
                                    .put("recordId", record.id)
                                    .put("title", record.title)
                                    .put("capturedAt", record.capturedAt)
                                    .put("status", record.itemStatus.name)
                                    .put("people", JSONArray(record.people))
                            )
                        }
                    })
                    .toString(2)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                context.startActivity(Intent.createChooser(intent, "分享 PebblePile 数据摘要"))
            },
            style = PebbleButtonStyle.Primary,
            icon = PebbleGlyph.Export,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private data class LoadedRecords(val loading: Boolean, val records: List<PebbleRecord>)

@Composable
private fun rememberRecords(api: CoreApi): LoadedRecords {
    var loading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<PebbleRecord>>(emptyList()) }
    LaunchedEffect(api) {
        loading = true
        records = withContext(Dispatchers.IO) { api.queryRecords().records }
        loading = false
    }
    return LoadedRecords(loading, records)
}
