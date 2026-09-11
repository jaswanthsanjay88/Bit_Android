package com.bit.network.server

import android.content.Context
import android.util.Log
import com.bit.service.WebServerService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebAccessManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        @Volatile
        private var instance: WebAccessManager? = null

        fun getInstance(context: Context): WebAccessManager {
            return instance ?: synchronized(this) {
                instance ?: WebAccessManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        instance = this
    }

    val server = BitWebAccessServer(context)

    val isRunning: StateFlow<Boolean> = server.isRunning
    val serverUrl: StateFlow<String> = server.serverUrl
    val activePort: StateFlow<Int> = server.activePort
    val clientCount: StateFlow<Int> = server.clientCount
    val requestLogs: StateFlow<List<ServerRequestLog>> = server.requestLogs

    fun clearRequestLogs() {
        server.clearRequestLogs()
    }

    fun startServer(port: Int = 7070): Boolean {
        val started = server.start(port)
        if (started) {
            try {
                WebServerService.start(context, port)
            } catch (e: Exception) {
                Log.e("WebAccessManager", "Failed to start WebServerService foreground", e)
            }
        }
        return started
    }

    fun stopServer() {
        try {
            WebServerService.stop(context)
        } catch (ignored: Exception) {}
        server.stop()
    }

    fun getLocalIpAddress(): String {
        return server.getLocalIpAddress()
    }
}
