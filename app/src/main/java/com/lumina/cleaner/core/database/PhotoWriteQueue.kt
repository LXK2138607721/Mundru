package com.lumina.cleaner.core.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Enqueue on the UI thread so delete, undo and the next batch observe user action order. */
internal class PhotoWriteQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var tail: Job? = null

    fun enqueue(write: suspend () -> Unit): Job {
        val previous = tail
        val job = scope.launch(dispatcher) {
            previous?.join()
            write()
        }
        tail = job
        return job
    }

    suspend fun awaitPendingWrites() {
        tail?.join()
    }
}
