package com.pebblepile.android.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Gson is used to persist complex domain objects as JSON columns, preserving every field for replay. */
internal object DbJson {
    private val gson = Gson()

    fun <T> encode(value: T): String = gson.toJson(value)

    inline fun <reified T> decode(json: String?): T? =
        if (json.isNullOrBlank()) null else gson.fromJson(json, object : TypeToken<T>() {}.type)

    fun encodeSet(values: Set<String>): String = gson.toJson(values)

    fun decodeSet(json: String?): Set<String> =
        if (json.isNullOrBlank()) emptySet()
        else gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type) ?: emptySet()
}
