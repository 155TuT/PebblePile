package com.pebblepile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pebblepile.android.data.*
import kotlinx.coroutines.launch
import java.util.UUID

private val Sand = Color(0xFFF3F0E8)
private val CardSand = Color(0xFFFAF8F2)
private val Moss = Color(0xFF657B60)
private val Ink = Color(0xFF343A32)
private val Muted = Color(0xFF777B70)

class MainActivity : ComponentActivity() {
    private lateinit var api: CoreApi
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = DemoCoreApi(applicationContext)
        acceptShare(intent)
        setContent {
            PebbleTheme {
                PebbleApp(api = api, sharedText = sharedText, onShareConsumed = { sharedText = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) sharedText = text
        }
    }
}

@Composable
private fun PebbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Moss,
            onPrimary = Color.White,
            background = Sand,
            surface = CardSand,
            onSurface = Ink,
            secondary = Color(0xFF9AAB8D),
            error = Color(0xFFB65D52)
        ),
        content = content
    )
}

@Composable
private fun PebbleApp(api: CoreApi, sharedText: String?, onShareConsumed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<PebbleRecord>>(emptyList()) }
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var banner by remember { mutableStateOf<String?>(null) }
    var shareSaving by remember { mutableStateOf(false) }
    var conflictMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) { records = api.recentRecords() }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) banner = "收到来自其他应用的分享内容，请确认后保存"
    }

    BackHandler(enabled = selectedRecordId != null) { selectedRecordId = null; refreshKey++ }

    Scaffold(
        containerColor = Sand,
        topBar = {
            TopAppBarSimple(
                title = if (selectedRecordId == null) "叠石 · PebblePile" else "记录详情",
                showBack = selectedRecordId != null,
                onBack = { selectedRecordId = null; refreshKey++ }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (selectedRecordId == null) {
                HomeScreen(records = records, onOpen = { selectedRecordId = it }, onRefresh = { refreshKey++ })
            } else {
                RecordDetailScreen(
                    api = api,
                    recordId = selectedRecordId!!,
                    onChanged = { refreshKey++ },
                    onConflict = { conflictMessage = it }
                )
            }
        }
    }

    if (!sharedText.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { onShareConsumed(); banner = null },
            title = { Text("保存分享内容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下文字将作为一条新记录保存：", color = Muted, fontSize = 13.sp)
                    Surface(color = Sand, shape = MaterialTheme.shapes.medium) {
                        Text(sharedText, Modifier.padding(12.dp), color = Ink, maxLines = 8)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !shareSaving, onClick = {
                    shareSaving = true
                    scope.launch {
                        val receipt = api.recordExperience(sharedText, "android_share", UUID.randomUUID().toString())
                        shareSaving = false
                        if (receipt.success && receipt.recordId != null) {
                            selectedRecordId = receipt.recordId
                            refreshKey++
                            onShareConsumed()
                            banner = "已保存，可在记录库中再次找到这条原话"
                        } else banner = receipt.message
                    }
                }) { Text(if (shareSaving) "保存中…" else "确认保存") }
            },
            dismissButton = { TextButton(onClick = onShareConsumed) { Text("取消") } },
            containerColor = CardSand
        )
    }

    if (conflictMessage != null) {
        AlertDialog(
            onDismissRequest = { conflictMessage = null },
            title = { Text("检测到修订冲突") },
            text = { Text("${conflictMessage}\n\n当前输入草稿仍保留。请核对最新内容后再提交。") },
            confirmButton = { TextButton(onClick = { conflictMessage = null; refreshKey++ }) { Text("查看最新版本") } },
            containerColor = CardSand
        )
    }

    if (banner != null) {
        LaunchedEffect(banner) {
            kotlinx.coroutines.delay(2800)
            banner = null
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            color = Moss, shape = MaterialTheme.shapes.large, shadowElevation = 5.dp
        ) { Text(banner!!, Modifier.padding(14.dp), color = Color.White, fontSize = 13.sp) }
    }
}

@Composable
private fun TopAppBarSimple(title: String, showBack: Boolean, onBack: () -> Unit) {
    Surface(color = Sand, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeScreen(records: List<PebbleRecord>, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    val availableTags = records.flatMap { it.tags }.filterNot { it.excluded }.map { it.name }.distinct().sorted()
    val filteredRecords = if (selectedTag == null) records else records.filter { record ->
        record.tags.any { !it.excluded && it.name == selectedTag }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("记录库", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("从其他应用分享文字，或打开一条已有记录。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        if (availableTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = selectedTag == null, onClick = { selectedTag = null }, label = { Text("全部") })
                availableTags.forEach { tag ->
                    FilterChip(selected = selectedTag == tag, onClick = { selectedTag = tag }, label = { Text(tag) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("最近记录", fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        if (filteredRecords.isEmpty()) {
            Surface(color = CardSand, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = Moss, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (records.isEmpty()) "还没有记录" else "这个标签下没有记录", fontWeight = FontWeight.Medium, color = Ink)
                    Text("可以从任意支持文字分享的应用发送一段内容到叠石。", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filteredRecords, key = { it.id }) { record ->
                    Card(onClick = { onOpen(record.id) }, colors = CardDefaults.cardColors(containerColor = CardSand), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp)) {
                            Text(record.rawText, color = Ink, maxLines = 3, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("${record.capturedAt} · ${record.status}", color = Muted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                record.tags.filterNot { it.excluded }.take(4).forEach { tag ->
                                    AssistChip(onClick = { selectedTag = tag.name }, label = { Text(tag.name) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordDetailScreen(api: CoreApi, recordId: String, onChanged: () -> Unit, onConflict: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var record by remember(recordId) { mutableStateOf<PebbleRecord?>(null) }
    var loading by remember { mutableStateOf(true) }
    var choice by rememberSaveable(recordId) { mutableStateOf("") }
    var experience by rememberSaveable(recordId) { mutableStateOf("") }
    var conditionDraft by rememberSaveable(recordId) { mutableStateOf("") }
    var showCorrection by rememberSaveable { mutableStateOf(false) }
    var correctionScope by rememberSaveable { mutableStateOf(CorrectionScope.THIS_TIME) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedEvidence by remember { mutableStateOf<EvidenceItem?>(null) }

    suspend fun reload() {
        loading = true
        record = api.getRecord(recordId)
        conditionDraft = record?.currentCondition.orEmpty()
        loading = false
    }
    LaunchedEffect(recordId) { reload() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Moss) }
        return
    }
    val item = record
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有找到这条记录", color = Muted) }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("原始记录", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        DetailCard {
            Text(item.rawText, color = Ink, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text("${item.capturedAt} · ${if (item.captureMethod == "android_share") "来自系统分享" else "应用内记录"}", color = Muted, fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("语义标签", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        DetailCard {
            if (item.tags.isEmpty()) Text("暂时没有标签", color = Muted)
            item.tags.forEach { tag ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (tag.excluded) "${tag.name} · 已排除" else tag.name, color = if (tag.excluded) Muted else Ink, fontWeight = FontWeight.Medium)
                        Text("依据：${tag.sourceQuote}\n算法：${tag.algorithmVersion}", color = Muted, fontSize = 11.sp)
                    }
                    if (!tag.excluded) TextButton(onClick = {
                        scope.launch {
                            val receipt = api.reviseTags(item.id, item.revision, tag.id, UUID.randomUUID().toString())
                            if (receipt.success) { status = receipt.message; reload(); onChanged() }
                            else if (receipt.conflict) onConflict(receipt.message) else status = receipt.message
                        }
                    }) { Text("排除") }
                }
                HorizontalDivider(color = Sand)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("建议与依据", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        item.suggestions.forEach { suggestion ->
            DetailCard {
                Text(suggestion.title, fontWeight = FontWeight.Medium, color = Ink)
                Text(suggestion.reason, color = Muted, fontSize = 13.sp)
                suggestion.evidence.forEach { evidence ->
                    Card(
                        onClick = { selectedEvidence = evidence },
                        colors = CardDefaults.cardColors(containerColor = Sand),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text("${evidence.title} · ${evidence.recordDate}", color = Moss, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("“${evidence.quote}”", color = Ink, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("实际反馈", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        DetailCard {
            if (item.outcomes.isEmpty()) Text("还没有补记实际选择和体验。", color = Muted, fontSize = 13.sp)
            item.outcomes.forEach { outcome ->
                Text("实际选择：${outcome.choice}", color = Ink, fontWeight = FontWeight.Medium)
                Text("实际体验：${outcome.experience}", color = Ink)
                Text(outcome.createdAt, color = Muted, fontSize = 11.sp)
                HorizontalDivider(color = Sand)
            }
            OutlinedTextField(value = choice, onValueChange = { choice = it }, label = { Text("实际选择") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = experience, onValueChange = { experience = it }, label = { Text("实际体验／感受") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(
                onClick = {
                    scope.launch {
                        val receipt = api.recordOutcome(item.id, item.revision, choice, experience, UUID.randomUUID().toString())
                        if (receipt.success) { choice = ""; experience = ""; status = receipt.message; reload(); onChanged() }
                        else if (receipt.conflict) onConflict(receipt.message) else status = receipt.message
                    }
                }, enabled = choice.isNotBlank() || experience.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Moss)
            ) { Text("保存实际反馈") }
        }

        Spacer(Modifier.height(16.dp))
        Text("纠正适用条件", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        DetailCard {
            Text(if (item.currentCondition.isBlank()) "尚未设置当前事项条件" else item.currentCondition, color = Ink)
            Text("可以选择只调整当前事项，或将条件保存为以后适用的明确偏好。", color = Muted, fontSize = 13.sp)
            Button(onClick = { conditionDraft = item.currentCondition; showCorrection = true }, colors = ButtonDefaults.buttonColors(containerColor = Moss)) { Text("修改条件") }
        }
        if (status != null) Text(status!!, color = Moss, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.height(28.dp))
    }

    if (showCorrection) {
        AlertDialog(
            onDismissRequest = { showCorrection = false },
            title = { Text("纠正条件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = conditionDraft, onValueChange = { conditionDraft = it }, label = { Text("新的适用条件") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("影响范围", fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = correctionScope == CorrectionScope.THIS_TIME, onClick = { correctionScope = CorrectionScope.THIS_TIME })
                        Column {
                            Text("仅本次", color = Ink)
                            Text("只修改当前事项，不改变长期认识", color = Muted, fontSize = 11.sp)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = correctionScope == CorrectionScope.FUTURE, onClick = { correctionScope = CorrectionScope.FUTURE })
                        Column {
                            Text("以后按此条件", color = Ink)
                            Text("保存为明确偏好，停用受影响的旧推断", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = conditionDraft.isNotBlank(), onClick = {
                    scope.launch {
                        val receipt = api.reviseMemory(item.id, item.revision, conditionDraft, correctionScope, UUID.randomUUID().toString())
                        if (receipt.success) { showCorrection = false; status = receipt.message; reload(); onChanged() }
                        else if (receipt.conflict) onConflict(receipt.message) else status = receipt.message
                    }
                }) { Text("确认修改") }
            },
            dismissButton = { TextButton(onClick = { showCorrection = false }) { Text("取消") } },
            containerColor = CardSand
        )
    }

    if (selectedEvidence != null) {
        val evidence = selectedEvidence!!
        AlertDialog(
            onDismissRequest = { selectedEvidence = null },
            title = { Text("来源与原话") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(evidence.title, color = Moss, fontWeight = FontWeight.Medium)
                    Text(evidence.recordDate, color = Muted, fontSize = 12.sp)
                    Text("“${evidence.quote}”", color = Ink)
                }
            },
            confirmButton = { TextButton(onClick = { selectedEvidence = null }) { Text("关闭") } },
            containerColor = CardSand
        )
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = CardSand),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.large
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content) }
}
