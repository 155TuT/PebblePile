package com.pebblepile.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pebblepile.android.model.SourceRef

private data class DinnerSource(
    val label: String, val recordId: String, val source: SourceRef, val raw: String, val start: Int, val end: Int,
) {
    val from = raw.offsetByCodePoints(0, start)
    val until = raw.offsetByCodePoints(0, end)
    val quote = raw.substring(from, until)
}

private val dinnerSources = listOf(
    DinnerSource("周一晚餐 · E01", "E01", SourceRef("monday", 1),
        "今天周一加班到八点半，晚饭预算二十元。我吃了十八元的热汤面，十分钟就吃上了，不用洗锅。", 30, 37),
    DinnerSource("周三晚餐 · E02", "E02", SourceRef("wednesday", 1),
        "今天周三又加班了，晚饭还是想控制在二十元以内。我吃了十六元的盖饭，不用准备食材，也不用洗锅，省事让我很满意。", 40, 45),
)

@Preview(name = "360dp", widthDp = 360, heightDp = 800)
@Preview(name = "360dp · 200%", widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
fun PebbleComponentsPreview() {
    PebbleComponentTheme {
        var text by rememberSaveable { mutableStateOf(dinnerSources.first().raw) }
        var selected by rememberSaveable { mutableStateOf(false) }
        var correcting by rememberSaveable { mutableStateOf(false) }
        var openedRecord by rememberSaveable { mutableStateOf<String?>(null) }
        var condition by rememberSaveable { mutableStateOf("加班时优先省事") }
        var draft by rememberSaveable { mutableStateOf(condition) }
        var saved by rememberSaveable { mutableStateOf(false) }
        var taskState by rememberSaveable { mutableStateOf(PebbleFeedbackState.PROCESSING_FAILED) }

        Column(Modifier.fillMaxSize().background(PebbleVisuals.Sand).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PebbleInput(text, { text = it }, "记录原话")
            PebbleTag("工作日加班时优先省事", selected, { selected = it }, origin = "自动整理", needsReview = true)
            PebbleEvidenceCard(condition, onCorrect = { draft = condition; correcting = true }, origin = "根据两次晚餐经历推断") {
                dinnerSources.forEach { item ->
                    PebbleEvidenceSource(item.quote, item.label, onOpenSource = { openedRecord = item.recordId })
                }
            }
            if (saved) {
                PebbleFeedback(PebbleFeedbackState.SAVED)
                PebbleFeedback(taskState, onRetry = { taskState = PebbleFeedbackState.PROCESSING })
            } else {
                PebbleFeedback(PebbleFeedbackState.FAILED, detail = "样例草稿已保留", onRetry = { saved = true })
            }
        }
        val item = dinnerSources.firstOrNull { it.recordId == openedRecord }
        if (item != null) AlertDialog(
            onDismissRequest = { openedRecord = null }, title = { PebbleText(item.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PebbleText("来源 ${item.source.sourceId} · 版本 ${item.source.sourceVersion}")
                    Text(buildAnnotatedString {
                        append(item.raw)
                        addStyle(SpanStyle(background = PebbleFeedbackState.REVIEW.surface), item.from, item.until)
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = { TextButton(onClick = { openedRecord = null }) { PebbleText("关闭") } },
        )
        if (correcting) AlertDialog(
            onDismissRequest = { correcting = false }, title = { PebbleText("纠正条件") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PebbleInput(draft, { draft = it }, "适用条件") } },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { condition = draft; correcting = false }) { PebbleText("确认") } },
            dismissButton = { TextButton(onClick = { correcting = false }) { PebbleText("取消") } },
        )
    }
}
