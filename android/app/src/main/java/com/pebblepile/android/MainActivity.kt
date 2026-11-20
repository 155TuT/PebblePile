package com.pebblepile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pebblepile.android.data.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val HOME_ROUTE = "home"
private const val LIBRARY_ROUTE = "library"
private const val DETAIL_ROUTE = "detail/{recordId}"

private val Sand = Color(0xFFF3F0E8)
private val CardSand = Color(0xFFFAF8F2)
private val Moss = Color(0xFF657B60)
private val Ink = Color(0xFF343A32)
private val Muted = Color(0xFF777B70)

private enum class InputMode { DIALOG, RECORD }

private data class PendingShare(val text: String, val operationId: String)

class MainActivity : ComponentActivity() {
    private var pendingShare by mutableStateOf<PendingShare?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = (application as PebblePileApplication).coreApi
        if (savedInstanceState == null) {
            acceptShare(intent)
        } else {
            val text = savedInstanceState.getString("shareText")
            val id = savedInstanceState.getString("shareOperationId")
            if (text != null && id != null) pendingShare = PendingShare(text, id)
        }
        setContent {
            PebbleTheme {
                PebbleApp(api, pendingShare) { consumed ->
                    if (pendingShare == consumed) pendingShare = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingShare?.let {
            outState.putString("shareText", it.text)
            outState.putString("shareOperationId", it.operationId)
        }
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) pendingShare = PendingShare(text, UUID.randomUUID().toString())
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
private fun PebbleApp(api: CoreApi, pendingShare: PendingShare?, onShareConsumed: (PendingShare) -> Unit) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: HOME_ROUTE
    var refreshKey by remember { mutableIntStateOf(0) }
    var shareSaving by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var inputMode by rememberSaveable { mutableStateOf(InputMode.RECORD) }

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun openRecord(recordId: String) {
        refreshKey++
        navController.navigate("detail/$recordId") { launchSingleTop = true }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        containerColor = Sand,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBarSimple(
                title = when {
                    currentRoute == HOME_ROUTE -> "叠石 · PebblePile"
                    currentRoute == LIBRARY_ROUTE -> "记录库"
                    else -> "记录详情"
                },
                showBack = currentRoute != HOME_ROUTE,
                onBack = { navController.popBackStack() },
                onOpenLibrary = if (currentRoute == HOME_ROUTE) {
                    { navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true } }
                } else null
            )
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HOME_ROUTE,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(HOME_ROUTE) {
                HomeEntryScreen(
                    api = api,
                    draft = draft,
                    onDraftChange = { draft = it },
                    inputMode = inputMode,
                    onInputModeChange = { inputMode = it },
                    onOpenLibrary = { navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true } },
                    onMessage = ::showMessage,
                    onSaved = { recordId ->
                        draft = ""
                        showMessage("已保存 · $recordId")
                        openRecord(recordId)
                    }
                )
            }
            composable(LIBRARY_ROUTE) {
                LibraryScreen(
                    api = api,
                    refreshKey = refreshKey,
                    onOpen = ::openRecord,
                    onMessage = ::showMessage
                )
            }
            composable(
                route = DETAIL_ROUTE,
                arguments = listOf(navArgument("recordId") { type = NavType.StringType })
            ) { entry ->
                val recordId = entry.arguments?.getString("recordId").orEmpty()
                RecordDetailScreen(
                    api = api,
                    recordId = recordId,
                    onChanged = { refreshKey++ }
                )
            }
        }
    }

    if (pendingShare != null) {
        AlertDialog(
            onDismissRequest = { if (!shareSaving) onShareConsumed(pendingShare) },
            title = { Text("保存分享内容") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下文字将作为一条新记录保存：", color = Muted, fontSize = 13.sp)
                    Surface(color = Sand, shape = MaterialTheme.shapes.medium) {
                        Text(pendingShare.text, Modifier.padding(12.dp), color = Ink, maxLines = 8)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !shareSaving, onClick = {
                    if (shareSaving) return@TextButton
                    shareSaving = true
                    scope.launch {
                        val receipt = api.recordExperience(pendingShare.text, "android_share", pendingShare.operationId)
                        shareSaving = false
                        if (receipt.success && receipt.recordId != null) {
                            refreshKey++
                            onShareConsumed(pendingShare)
                            showMessage("已保存 · ${receipt.recordId}")
                            openRecord(receipt.recordId)
                        } else showMessage(receipt.message)
                    }
                }) { Text(if (shareSaving) "保存中…" else "确认保存") }
            },
            dismissButton = { TextButton(enabled = !shareSaving, onClick = { onShareConsumed(pendingShare) }) { Text("取消") } },
            containerColor = CardSand
        )
    }

}

@Composable
private fun TopAppBarSimple(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenLibrary: (() -> Unit)? = null
) {
    Surface(color = Sand, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onOpenLibrary != null) TextButton(onClick = onOpenLibrary) { Text("记录库") }
        }
    }
}

@Composable
private fun HomeEntryScreen(
    api: CoreApi,
    draft: String,
    onDraftChange: (String) -> Unit,
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    onOpenLibrary: () -> Unit,
    onMessage: (String) -> Unit,
    onSaved: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var operationId by rememberSaveable(draft, inputMode) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("留下今天的原话", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("切换对话或记录不会清空正在输入的内容。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = inputMode == InputMode.DIALOG,
                onClick = { onInputModeChange(InputMode.DIALOG) },
                label = { Text("对话") }
            )
            FilterChip(
                selected = inputMode == InputMode.RECORD,
                onClick = { onInputModeChange(InputMode.RECORD) },
                label = { Text("记录") }
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text(if (inputMode == InputMode.DIALOG) "想说什么" else "记录原话") },
            placeholder = { Text("写下此刻真实经历或想法") },
            minLines = 6,
            enabled = !saving
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenLibrary, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.BookmarkBorder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("打开记录库")
            }
            Button(
                enabled = draft.isNotBlank() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = Moss),
                onClick = {
                    if (saving) return@Button
                    saving = true
                    val id = operationId ?: UUID.randomUUID().toString().also { operationId = it }
                    scope.launch {
                        val receipt = api.recordExperience(
                            text = draft,
                            captureMethod = if (inputMode == InputMode.DIALOG) "app_home_dialog" else "app_home_record",
                            operationId = id
                        )
                        saving = false
                        if (receipt.success && receipt.recordId != null) {
                            onSaved(receipt.recordId)
                        } else {
                            onMessage(receipt.message.ifBlank { "保存失败，请重试" })
                        }
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (inputMode == InputMode.DIALOG) "发送并保存" else "保存记录")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LibraryScreen(
    api: CoreApi,
    refreshKey: Int,
    onOpen: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var records by remember { mutableStateOf<List<PebbleRecord>>(emptyList()) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        runCatching { api.recentRecords() }
            .onSuccess { loaded ->
                records = loaded
                if (loaded.none { record -> record.tags.any { !it.excluded && it.name == selectedTag } }) {
                    selectedTag = null
                }
            }
            .onFailure { onMessage("记录库读取失败：${it.message ?: "未知错误"}") }
    }

    val availableTags = records
        .flatMap { it.tags }
        .filterNot { it.excluded }
        .map { it.name }
        .distinct()
        .sorted()
    val filtered = if (selectedTag == null) {
        records
    } else {
        records.filter { record -> record.tags.any { !it.excluded && it.name == selectedTag } }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("时间线", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text("记录与系统分享共用同一资料库。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

        if (availableTags.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTag == null,
                    onClick = { selectedTag = null },
                    label = { Text("全部") }
                )
                availableTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTag == tag,
                        onClick = { selectedTag = tag },
                        label = { Text(tag) }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            Surface(color = CardSand, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = Moss, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (records.isEmpty()) "还没有记录" else "这个标签下没有记录", fontWeight = FontWeight.Medium, color = Ink)
                    Text("可以从首页输入，或通过系统分享保存。", color = Muted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                filtered.groupBy { it.capturedAt.take(10) }.forEach { (date, dateRecords) ->
                    item(key = "date_$date") {
                        Text(date, color = Moss, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(dateRecords, key = { it.id }) { record ->
                        Card(
                            onClick = { onOpen(record.id) },
                            colors = CardDefaults.cardColors(containerColor = CardSand),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                Text(record.rawText, color = Ink, maxLines = 3, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("${record.capturedAt.take(16)} · ${record.status}", color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    record.tags.filterNot { it.excluded }.take(4).forEach { tag ->
                                        AssistChip(
                                            onClick = { selectedTag = tag.name },
                                            label = { Text(tag.name) }
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
@Composable
private fun RecordDetailScreen(api: CoreApi, recordId: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var record by remember(recordId) { mutableStateOf<PebbleRecord?>(null) }
    var loading by remember { mutableStateOf(true) }
    var choice by rememberSaveable(recordId) { mutableStateOf("") }
    var experience by rememberSaveable(recordId) { mutableStateOf("") }
    var conditionDraft by rememberSaveable(recordId) { mutableStateOf("") }
    var showCorrection by rememberSaveable { mutableStateOf(false) }
    var correctionScope by rememberSaveable { mutableStateOf(CorrectionScope.THIS_TIME) }
    var status by remember { mutableStateOf<String?>(null) }
    var conflictMessage by rememberSaveable(recordId) { mutableStateOf<String?>(null) }
    var evidenceId by rememberSaveable(recordId) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        record = api.getRecord(recordId)
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
    var feedbackOperation by rememberSaveable(recordId, item.revision, choice, experience) {
        mutableStateOf<String?>(null)
    }
    var correctionOperation by rememberSaveable(recordId, item.revision, conditionDraft, correctionScope) {
        mutableStateOf<String?>(null)
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
                var tagOperation by rememberSaveable(recordId, tag.id, item.revision) { mutableStateOf<String?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (tag.excluded) "${tag.name} · 已排除" else tag.name, color = if (tag.excluded) Muted else Ink, fontWeight = FontWeight.Medium)
                        Text("依据：${tag.sourceQuote}\n算法：${tag.algorithmVersion}", color = Muted, fontSize = 11.sp)
                    }
                    if (!tag.excluded) TextButton(onClick = {
                        val id = tagOperation ?: UUID.randomUUID().toString().also { tagOperation = it }
                        scope.launch {
                            val receipt = api.reviseTags(item.id, item.revision, tag.id, id)
                            if (receipt.success) { status = receipt.message; reload(); onChanged() }
                            else if (receipt.conflict) conflictMessage = receipt.message else status = receipt.message
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
                    Surface(onClick = { evidenceId = evidence.id }, color = Sand, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            Text("${evidence.title} · ${evidence.recordDate}", color = Moss, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("“${evidence.quote}”", color = Ink, fontSize = 13.sp)
                            Text("查看来源与原话", color = Moss, fontSize = 13.sp)
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
                    val id = feedbackOperation ?: UUID.randomUUID().toString().also { feedbackOperation = it }
                    scope.launch {
                        val receipt = api.recordOutcome(item.id, item.revision, choice, experience, id)
                        if (receipt.success) { choice = ""; experience = ""; status = receipt.message; reload(); onChanged() }
                        else if (receipt.conflict) conflictMessage = receipt.message else status = receipt.message
                    }
                }, enabled = choice.isNotBlank() || experience.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Moss)
            ) { Text("保存实际反馈") }
        }

        Spacer(Modifier.height(16.dp))
        Text("纠正适用条件", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Ink)
        DetailCard {
            Text(if (item.currentCondition.isBlank()) "尚未设置当前事项条件" else item.currentCondition, color = Ink)
            item.conditionScope?.let { Text("适用范围：${it.label}", color = Moss, fontSize = 13.sp) }
            Text("可以选择只调整当前事项，或将条件保存为以后适用的明确偏好。", color = Muted, fontSize = 13.sp)
            Button(onClick = {
                conditionDraft = item.currentCondition
                correctionScope = item.conditionScope ?: CorrectionScope.THIS_TIME
                showCorrection = true
            }, colors = ButtonDefaults.buttonColors(containerColor = Moss)) { Text("修改条件") }
        }
        if (status != null) Text(status!!, color = Moss, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.height(28.dp))
    }

    val evidence = item.suggestions.flatMap { it.evidence }.firstOrNull { it.id == evidenceId }
    if (evidence != null) {
        var source by remember(api, evidence.sourceRecordId) { mutableStateOf<PebbleRecord?>(null) }
        LaunchedEffect(api, evidence.sourceRecordId) {
            source = api.getRecord(evidence.sourceRecordId)
        }
        AlertDialog(
            onDismissRequest = { evidenceId = null },
            title = { Text("来源与原话") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${evidence.title} · ${evidence.recordDate}", color = Moss)
                    Text("“${evidence.quote}”")
                    source?.let {
                        Text("来源记录", fontWeight = FontWeight.Medium)
                        Text(it.rawText)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { evidenceId = null }) { Text("关闭") } }
        )
    }

    if (showCorrection && conflictMessage == null) {
        AlertDialog(
            onDismissRequest = { showCorrection = false },
            title = { Text("纠正条件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.currentCondition.isNotBlank()) Text("当前条件：${item.currentCondition}", color = Muted)
                    OutlinedTextField(value = conditionDraft, onValueChange = { conditionDraft = it }, label = { Text("新的适用条件") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("影响范围", fontWeight = FontWeight.Medium)
                    Column(Modifier.selectableGroup()) {
                        CorrectionScope.entries.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = correctionScope == option,
                                    role = Role.RadioButton,
                                    onClick = { correctionScope = option }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = correctionScope == option, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(option.label, color = Ink)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = conditionDraft.isNotBlank(), onClick = {
                    val id = correctionOperation ?: UUID.randomUUID().toString().also { correctionOperation = it }
                    scope.launch {
                        val receipt = api.reviseMemory(item.id, item.revision, conditionDraft, correctionScope, id)
                        if (receipt.success) { showCorrection = false; status = receipt.message; reload(); onChanged() }
                        else if (receipt.conflict) conflictMessage = receipt.message else status = receipt.message
                    }
                }) { Text("确认修改") }
            },
            dismissButton = { TextButton(onClick = { showCorrection = false }) { Text("取消") } },
            containerColor = CardSand
        )
    }
    conflictMessage?.let { message ->
        fun refreshRecord() {
            scope.launch {
                reload()
                conflictMessage = null
            }
        }
        AlertDialog(
            onDismissRequest = ::refreshRecord,
            title = { Text("检测到修订冲突") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = ::refreshRecord) { Text("查看最新版本") } },
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
