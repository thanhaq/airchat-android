package dev.offlinemesh.airchat.store

import dev.offlinemesh.airchat.model.ReceivedFile

class InMemoryReceivedFileStore : ReceivedFileStore {
    private var files = emptyList<ReceivedFile>()

    override fun loadReceivedFiles(): List<ReceivedFile> = files

    override fun saveReceivedFiles(files: List<ReceivedFile>) {
        this.files = files
    }

    override fun clear() {
        files = emptyList()
    }
}
