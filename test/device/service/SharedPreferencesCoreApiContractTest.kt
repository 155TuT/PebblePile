package com.pebblepile.android.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pebblepile.android.db.DemoLibraryStore
import java.util.UUID
import org.junit.After
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesCoreApiContractTest : CoreApiContractSuite() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "issue10-contract-${UUID.randomUUID()}"

    override fun createRepository() =
        DemoLibraryStore(context.getSharedPreferences(name, Context.MODE_PRIVATE)).repository()

    @After fun removeTestLibrary() {
        context.deleteSharedPreferences(name)
    }
}
