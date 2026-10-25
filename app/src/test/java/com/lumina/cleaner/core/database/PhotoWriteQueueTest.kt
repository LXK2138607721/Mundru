package com.lumina.cleaner.core.database

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhotoWriteQueueTest {
    @Test fun `undo waits for slow delete and a batch read waits for both`() = runBlocking {
        val queue = PhotoWriteQueue(this, Dispatchers.Unconfined)
        val releaseDelete = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var trashed = false
        queue.enqueue {
            releaseDelete.await()
            trashed = true
            events.add("delete")
        }
        queue.enqueue {
            assertTrue(trashed)
            trashed = false
            events.add("undo")
        }
        val read = launch(Dispatchers.Unconfined) {
            queue.awaitPendingWrites()
            assertFalse(trashed)
            events.add("read")
        }
        assertTrue(events.isEmpty())
        releaseDelete.complete(Unit)
        read.join()
        assertEquals(listOf("delete", "undo", "read"), events)
    }

    @Test fun `next batch sees the completed cooldown write`() = runBlocking {
        val queue = PhotoWriteQueue(this, Dispatchers.Unconfined)
        val releaseWrite = CompletableDeferred<Unit>()
        var lastViewedAt = 0L
        queue.enqueue { releaseWrite.await(); lastViewedAt = 12345L }
        val nextBatch = launch(Dispatchers.Unconfined) {
            queue.awaitPendingWrites()
            assertEquals(12345L, lastViewedAt)
        }
        assertFalse(nextBatch.isCompleted)
        releaseWrite.complete(Unit)
        nextBatch.join()
    }

    @Test fun `accepted undo survives caller destruction while UI feedback is cancelled`() = runBlocking {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val uiScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val queue = PhotoWriteQueue(applicationScope, Dispatchers.Unconfined)
            val releaseDelete = CompletableDeferred<Unit>()
            var trashed = false
            var uiUpdated = false
            queue.enqueue { releaseDelete.await(); trashed = true }
            val undo = queue.enqueue { trashed = false }
            uiScope.launch { undo.join(); uiUpdated = true }
            uiScope.cancel()
            releaseDelete.complete(Unit)
            queue.awaitPendingWrites()
            assertTrue(undo.isCompleted)
            assertFalse(undo.isCancelled)
            assertFalse(trashed)
            assertFalse(uiUpdated)
        } finally {
            uiScope.cancel()
            applicationScope.cancel()
        }
    }

    @Test fun `recreated caller sync merges only after pending review and undo writes`() = runBlocking {
        val queue = PhotoWriteQueue(this, Dispatchers.Unconfined)
        val releaseReview = CompletableDeferred<Unit>()
        var persistedViewedAt = 0L
        var trashed = true
        var startupViewedAt = -1L
        var startupTrashed = true
        queue.enqueue { releaseReview.await(); persistedViewedAt = 12345L }
        queue.enqueue { trashed = false }
        // Startup read/merge/REPLACE is one queued operation, rather than an out-of-queue old snapshot.
        val sync = queue.enqueue {
            startupViewedAt = persistedViewedAt
            startupTrashed = trashed
            persistedViewedAt = startupViewedAt
        }
        assertFalse(sync.isCompleted)
        releaseReview.complete(Unit)
        sync.join()
        assertEquals(12345L, startupViewedAt)
        assertEquals(12345L, persistedViewedAt)
        assertFalse(startupTrashed)
    }
}
