package com.pebblepile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pebblepile.android.model.ItemStatus
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val HOME_ROUTE = "home"
private const val LIBRARY_ROUTE = "library"
private const val DETAIL_ROUTE = "detail/{recordId}"
private const val SETTINGS_ROUTE = "settings"
private const val MEMORY_ROUTE = "settings/memory"
private const val RULES_ROUTE = "settings/rules"
private const val DATA_ROUTE = "settings/data"

private data class PendingShare(val text: String, val operationId: String)
private data class UiToast(val message: String, val state: PebbleFeedbackState)

private val StringSetSaver = Saver<Set<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

class MainActivity : ComponentActivity() {
    private var pendingShare by mutableStateOf<PendingShare?>(null)
    private var settings by mutableStateOf(PebbleSettings())
    private val settingsStore by lazy { PebbleSettingsStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = settingsStore.load()
        val api = (application as PebblePileApplication).coreApi
        api.setAutoOrganize(settings.autoOrganize)
        if (savedInstanceState == null) {
            acceptShare(intent)
        } else {
            val text = savedInstanceState.getString("shareText")
            val id = savedInstanceState.getString("shareOperationId")
            if (text != null && id != null) pendingShare = PendingShare(text, id)
        }
        setContent {
            val density = LocalDensity.current
            val adjustedDensity = remember(density, settings.textScale) {
                Density(density.density, density.fontScale * settings.textScale.multiplier)
            }
            CompositionLocalProvider(LocalDensity provides adjustedDensity) {
                PebbleComponentTheme {
                    PebbleApp(
                        api = api,
                        settings = settings,
                        pendingShare = pendingShare,
                        onSettingsChange = ::updateSettings,
                        onShareConsumed = { consumed ->
                            if (pendingShare == consumed) pendingShare = null
                        },
                    )
                }
            }
        }
    }

    private fun updateSettings(updated: PebbleSettings) {
        settings = updated
        settingsStore.save(updated)
        (application as PebblePileApplication).coreApi.setAutoOrganize(updated.autoOrganize)
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
private fun PebbleApp(
    api: CoreApi,
    settings: PebbleSettings,
    pendingShare: PendingShare?,
    onSettingsChange: (PebbleSettings) -> Unit,
    onShareConsumed: (PendingShare) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: HOME_ROUTE
    var refreshKey by remember { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var captureMode by rememberSaveable { mutableStateOf(PebbleCaptureMode.Record) }
    var toast by remember { mutableStateOf<UiToast?>(null) }
    var shareSaving by remember(pendingShare?.operationId) { mutableStateOf(false) }
    var shareFailure by remember(pendingShare?.operationId) { mutableStateOf<String?>(null) }

    var libraryView by rememberSaveable { mutableStateOf(PebbleLibraryView.Timeline) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var libraryTags by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet()) }
    var libraryPerson by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryStatus by rememberSaveable { mutableStateOf<ItemStatus?>(null) }

    fun showToast(message: String, state: PebbleFeedbackState = PebbleFeedbackState.SAVED) {
        toast = UiToast(message, state)
    }

    fun openRecord(recordId: String) {
        refreshKey++
        navController.navigate("detail/$recordId") { launchSingleTop = true }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3_600)
            toast = null
        }
    }

    Box(
        Modifier.fillMaxSize().background(PebbleVisuals.Sand)
            .safeDrawingPadding().imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            PebbleTopBar(
                title = when (currentRoute) {
                    HOME_ROUTE -> "叠石"
                    LIBRARY_ROUTE -> "记录库"
                    SETTINGS_ROUTE -> "设置"
                    MEMORY_ROUTE -> "记忆与偏好"
                    RULES_ROUTE -> "标签与规则"
                    DATA_ROUTE -> "本地数据"
                    else -> "记录详情"
                },
                modifier = Modifier.padding(horizontal = 20.dp),
                leading = {
                    if (currentRoute == HOME_ROUTE) {
                        PebbleIconButton(PebbleGlyph.Library, "记录库", {
                            navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true }
                        }, tint = PebbleVisuals.Moss)
                    } else {
                        PebbleIconButton(PebbleGlyph.Back, "返回", { navController.popBackStack() })
                    }
                },
                trailing = {
                    if (currentRoute == HOME_ROUTE) {
                        PebbleIconButton(PebbleGlyph.Settings, "设置", {
                            navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                        })
                    }
                },
            )

            NavHost(
                navController = navController,
                startDestination = HOME_ROUTE,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(HOME_ROUTE) {
                    HomeEntryScreen(
                        api = api,
                        draft = draft,
                        onDraftChange = { draft = it },
                        captureMode = captureMode,
                        onCaptureModeChange = { captureMode = it },
                        onOpenRecord = ::openRecord,
                        onMessage = ::showToast,
                        onSaved = { recordId ->
                            draft = ""
                            showToast("已保存 · $recordId")
                            openRecord(recordId)
                        },
                    )
                }
                composable(LIBRARY_ROUTE) {
                    LibraryScreen(
                        api = api,
                        refreshKey = refreshKey,
                        view = libraryView,
                        onViewChange = { libraryView = it },
                        query = libraryQuery,
                        onQueryChange = { libraryQuery = it },
                        selectedTags = libraryTags,
                        onSelectedTagsChange = { libraryTags = it },
                        selectedPerson = libraryPerson,
                        onSelectedPersonChange = { libraryPerson = it },
                        selectedStatus = libraryStatus,
                        onSelectedStatusChange = { libraryStatus = it },
                        onOpen = ::openRecord,
                        onMessage = ::showToast,
                    )
                }
                composable(
                    route = DETAIL_ROUTE,
                    arguments = listOf(navArgument("recordId") { type = NavType.StringType })
                ) { entry ->
                    RecordDetailScreen(
                        api = api,
                        recordId = entry.arguments?.getString("recordId").orEmpty(),
                        inferenceAssistEnabled = settings.inferenceAssist,
                        onChanged = { refreshKey++ },
                        onMessage = ::showToast,
                    )
                }
                composable(SETTINGS_ROUTE) {
                    SettingsHomeScreen(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onOpenMemory = { navController.navigate(MEMORY_ROUTE) },
                        onOpenRules = { navController.navigate(RULES_ROUTE) },
                        onOpenData = { navController.navigate(DATA_ROUTE) },
                    )
                }
                composable(MEMORY_ROUTE) {
                    MemorySettingsScreen(api, ::openRecord)
                }
                composable(RULES_ROUTE) {
                    TagRulesScreen(api)
                }
                composable(DATA_ROUTE) {
                    DataManagementScreen(api)
                }
            }
        }

        toast?.let {
            PebbleToast(
                it.message,
                it.state.icon,
                Modifier.align(Alignment.BottomCenter).padding(24.dp),
                tone = it.state.ink,
            )
        }
    }

    if (pendingShare != null) {
        PebbleDialog(
            title = "保存分享内容",
            onDismiss = { if (!shareSaving) onShareConsumed(pendingShare) },
            confirmLabel = if (shareSaving) "保存中…" else "确认保存",
            confirmEnabled = !shareSaving,
            onConfirm = {
                if (shareSaving) return@PebbleDialog
                shareSaving = true
                scope.launch(Dispatchers.Main.immediate) {
                    shareFailure = null
                    val receipt = try {
                        withContext(Dispatchers.IO) {
                            api.recordExperience(pendingShare.text, "android_share", pendingShare.operationId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        shareFailure = failure.message ?: "保存失败，请重试"
                        return@launch
                    } finally {
                        shareSaving = false
                    }
                    if (receipt.success && receipt.recordId != null) {
                        onShareConsumed(pendingShare)
                        showToast("已保存 · ${receipt.recordId}")
                        openRecord(receipt.recordId)
                    } else {
                        shareFailure = receipt.message
                    }
                }
            },
        ) {
            PebbleText("以下文字将作为一条新记录保存：", color = PebbleVisuals.Muted)
            PebbleQuote(pendingShare.text, source = "来自其他应用")
            shareFailure?.let { PebbleFeedback(PebbleFeedbackState.FAILED, detail = it) }
        }
    }
}
