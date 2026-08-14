package com.bit.engine.bridge

fun interface GenStream {
    fun onToken(token: String): Boolean
}
