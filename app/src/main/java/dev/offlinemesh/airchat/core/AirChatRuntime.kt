package dev.offlinemesh.airchat.core

import android.content.Context

object AirChatRuntime {
    @Volatile
    private var currentContainer: AppContainer? = null

    fun get(context: Context): AppContainer {
        currentContainer?.let { return it }
        return synchronized(this) {
            currentContainer ?: AppContainer(context.applicationContext).also { currentContainer = it }
        }
    }

    fun current(): AppContainer? = currentContainer

    fun shutdown() {
        synchronized(this) {
            currentContainer?.close()
            currentContainer = null
        }
    }
}
