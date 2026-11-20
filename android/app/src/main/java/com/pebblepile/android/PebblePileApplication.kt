package com.pebblepile.android

import android.app.Application
import com.pebblepile.android.data.CoreApi
import com.pebblepile.android.data.DemoCoreApi

class PebblePileApplication : Application() {
    val coreApi: CoreApi by lazy { DemoCoreApi(this) }
}
