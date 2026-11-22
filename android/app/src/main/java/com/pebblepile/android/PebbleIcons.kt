package com.pebblepile.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Vector ports of the 24 × 24 originals in assets/icons/manifest.json. */
enum class PebbleGlyph(@DrawableRes val drawable: Int) {
    Home(R.drawable.pp_home),
    Chat(R.drawable.pp_chat),
    Memory(R.drawable.pp_memory),
    Sources(R.drawable.pp_sources),
    Tasks(R.drawable.pp_tasks),
    Settings(R.drawable.pp_settings),
    Subject(R.drawable.pp_subject),
    Timeline(R.drawable.pp_timeline),
    Search(R.drawable.pp_search),
    Add(R.drawable.pp_add),
    Edit(R.drawable.pp_edit),
    Back(R.drawable.pp_back),
    Close(R.drawable.pp_close),
    More(R.drawable.pp_more),
    Delete(R.drawable.pp_delete),
    Export(R.drawable.pp_export),
    Restore(R.drawable.pp_restore),
    Import(R.drawable.pp_import),
    Pause(R.drawable.pp_pause),
    Resume(R.drawable.pp_resume),
    Retry(R.drawable.pp_retry),
    Send(R.drawable.pp_send),
    Check(R.drawable.pp_check),
    Warning(R.drawable.pp_warning),
    Info(R.drawable.pp_info),
    Clock(R.drawable.pp_clock),
    ChevronRight(R.drawable.pp_chevron_right),
    ChevronDown(R.drawable.pp_chevron_down),
    Library(R.drawable.pp_library),
    Tag(R.drawable.pp_tag),
    Voice(R.drawable.pp_voice),
    Connection(R.drawable.pp_connection),
    Appearance(R.drawable.pp_appearance),
    Model(R.drawable.pp_model)
}

@Composable
fun PebbleIcon(
    icon: PebbleGlyph, modifier: Modifier = Modifier, color: Color = PebbleVisuals.Moss,
    description: String? = null,
) {
    Image(painterResource(icon.drawable), description, modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
}
