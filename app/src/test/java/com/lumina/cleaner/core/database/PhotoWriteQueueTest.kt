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

    @Test fun `concurrent producers across threads never drop writes and execute serially`() = runBlocking {
        val queue = PhotoWriteQueue(this, Dispatchers.Default)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val activeWriters = java.util.concurrent.atomic.AtomicInteger(0)
        val overlapDetected = java.util.concurrent.atomic.AtomicBoolean(false)

        val producers = (0 until 8).map { worker ->
            launch(Dispatchers.Default) {
                repeat(25) {
                    queue.enqueue {
                        if (activeWriters.incrementAndGet() > 1) {
                            overlapDetected.set(true)
                        }
                        completed.incrementAndGet()
                        activeWriters.decrementAndGet()
                    }
                }
            }
        }
        producers.forEach { it.join() }
        queue.awaitPendingWrites()
        assertFalse("Queued writes must never overlap", overlapDetected.get())
        assertEquals(200, completed.get())
    }

    @Test fun `review coalescer batches rapid left swipes and preserves strict order before trash`() = runBlocking {
        val queue = PhotoWriteQueue(this, Dispatchers.Unconfined)
        val committedBatches = mutableListOf<List<Pair<Long, Long>>>()
        val orderLog = mutableListOf<String>()
        val coalescer = ReviewWriteCoalescer(
            writeQueue = queue,
            scope = this,
            commitBatch = { batch ->
                committedBatches.add(batch)
                orderLog.add("reviews:${batch.map { it.first }}")
            },
            flushThreshold = 4,
            flushDelayMs = 60_000L,
            delayDispatcher = Dispatchers.Default
        )

        // 4 rapid swipes trigger exactly 1 threshold-batched transaction
        coalescer.recordReview(1L, 100L)
        coalescer.recordReview(2L, 101L)
        coalescer.recordReview(3L, 102L)
        assertTrue(committedBatches.isEmpty())
        coalescer.recordReview(4L, 103L)
        queue.awaitPendingWrites()
        assertEquals(1, committedBatches.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), committedBatches[0].map { it.first })

        // 2 more left swipes followed by an up-swipe trash: flushNow ensures reviews land BEFORE trash
        coalescer.recordReview(5L, 104L)
        coalescer.recordReview(6L, 105L)
        coalescer.flushNow()
        queue.enqueue { orderLog.add("trash:7") }
        queue.awaitPendingWrites()

        assertEquals(
            listOf("reviews:[1, 2, 3, 4]", "reviews:[5, 6]", "trash:7"),
            orderLog
        )
    }
}
