package com.pebblepile.utils

private const val LOWER_HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(LOWER_HEX_DIGITS[value ushr 4])
        append(LOWER_HEX_DIGITS[value and 0x0f])
    }
}
