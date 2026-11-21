package com.pebblepile.android.service

import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream

/** Frozen issue #10 inputs shared by every repository implementation. */
internal object Issue10Fixtures {
    private val fixtures = requireNotNull(fixtureStream())
        .bufferedReader(Charsets.UTF_8)
        .use { JsonParser.parseReader(it).asJsonObject }

    private fun fixtureStream(): InputStream? =
        Issue10Fixtures::class.java.getResourceAsStream("/fixtures.json")
            ?: listOf(
                File("../../docs/testing/issue10/fixtures.json"),
                File("../docs/testing/issue10/fixtures.json"),
            ).firstOrNull(File::isFile)?.inputStream()

    fun text(group: String, id: String): String = fixtures.getAsJsonArray(group)
        .map { it.asJsonObject }
        .single { it["id"].asString == id }["text"].asString

    val E01 = text("records", "E01")
    val E02 = text("records", "E02")
    val C01 = text("corrections", "C01")
}
