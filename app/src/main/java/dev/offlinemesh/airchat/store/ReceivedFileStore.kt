package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.ReceivedFile

interface ReceivedFileStore {
    fun loadReceivedFiles(): List<ReceivedFile>
    fun saveReceivedFiles(files: List<ReceivedFile>)
    fun clear()
}
