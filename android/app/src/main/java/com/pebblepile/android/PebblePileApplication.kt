package com.pebblepile.android

import android.app.Application
import com.pebblepile.android.service.CoreApi

class PebblePileApplication : Application() {
    val coreApi: CoreApi by lazy { DemoCoreApi(this) }
}
