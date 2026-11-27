package com.pebblepile.expo

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.activity.ComponentActivity
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pebblepile.android.PebbleClientContent
import com.pebblepile.android.R
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.views.ExpoView

class PebbleClientModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("PebbleClient")
        View(PebbleClientView::class) {
            Prop("homeTitle") { view: PebbleClientView, title: String ->
                view.homeTitle = title
            }
        }
    }
}

class PebbleClientView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
    override val shouldUseAndroidLayout = true
    internal var homeTitle by mutableStateOf("叠石")

    init {
        // React can detach its views as the keyboard or dev menu changes the window.
        // Keep navigation, activity results and saved state bound to this Activity.
        val activity = appContext.throwingActivity as ComponentActivity
        val content = ComposeView(activity).apply {
            id = R.id.pebble_client_content
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewTreeOnBackPressedDispatcherOwner(activity)
            setContent { PebbleClientContent(homeTitle = homeTitle) }
        }
        addView(content, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
}
