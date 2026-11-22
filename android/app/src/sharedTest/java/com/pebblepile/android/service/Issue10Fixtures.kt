package com.pebblepile.android.service

import com.google.gson.JsonParser

/** Frozen issue #10 inputs shared by every repository implementation. */
internal object Issue10Fixtures {
    private val fixtures = requireNotNull(Issue10Fixtures::class.java.getResourceAsStream("/fixtures.json")) {
        "Expected docs/testing/issue10/fixtures.json on the test classpath"
    }.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }

    fun text(group: String, id: String): String = fixtures.getAsJsonArray(group)
        .map { it.asJsonObject }
        .single { it["id"].asString == id }["text"].asString

    val E01 = text("records", "E01")
    val E02 = text("records", "E02")
    val C01 = text("corrections", "C01")
}
