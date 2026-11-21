package com.pebblepile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pebblepile.android.model.PebbleRecord
import com.pebblepile.android.service.CoreApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val HOME_ROUTE = "home"
private const val LIBRARY_ROUTE = "library"
private const val DETAIL_ROUTE = "detail/{recordId}"

private data class PendingShare(val text: String, val operationId: String)
private data class UiToast(val message: String, val state: PebbleFeedbackState)

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
            PebbleComponentTheme {
                PebbleApp(api = api, pendingShare = pendingShare) { consumed ->
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
private fun PebbleApp(
    api: CoreApi,
    pendingShare: PendingShare?,
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
    var shareSaving by remember { mutableStateOf(false) }

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
                title = when {
                    currentRoute == HOME_ROUTE -> "叠石 · PebblePile"
                    currentRoute == LIBRARY_ROUTE -> "记录库"
                    else -> "记录详情"
                },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                leading = {
                    if (currentRoute != HOME_ROUTE) {
                        PebbleIconButton(PebbleGlyph.Back, "返回", {
                            navController.popBackStack()
                        }, style = PebbleIconButtonStyle.Plain)
                    }
                },
                trailing = {
                    if (currentRoute == HOME_ROUTE) {
                        PebbleIconButton(PebbleGlyph.Library, "记录库", {
                            navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true }
                        }, style = PebbleIconButtonStyle.Plain)
                    }
                },
            )

            NavHost(
                navController = navController,
                startDestination = HOME_ROUTE,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(HOME_ROUTE) {
                    HomeEntryScreen(
                        api = api,
                        draft = draft,
                        onDraftChange = { draft = it },
                        captureMode = captureMode,
                        onCaptureModeChange = { captureMode = it },
                        onOpenLibrary = {
                            navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true }
                        },
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
                        onChanged = { refreshKey++ },
                        onMessage = ::showToast,
                    )
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
                scope.launch {
                    val receipt = api.recordExperience(
                        pendingShare.text,
                        "android_share",
                        pendingShare.operationId,
                    )
                    shareSaving = false
                    if (receipt.success && receipt.recordId != null) {
                        refreshKey++
                        onShareConsumed(pendingShare)
                        showToast("已保存 · ${receipt.recordId}")
                        openRecord(receipt.recordId)
                    } else {
                        showToast(receipt.message, PebbleFeedbackState.FAILED)
                    }
                }
            },
        ) {
            PebbleText("以下文字将作为一条新记录保存：", color = PebbleVisuals.Muted)
            PebbleQuote(pendingShare.text, source = "来自其他应用")
        }
    }
}
