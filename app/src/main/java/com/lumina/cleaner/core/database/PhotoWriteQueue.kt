package com.lumina.cleaner.core.database

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 线程安全的串行数据库写队列 (PhotoWriteQueue)
 *
 * 保证主线程用户动作（左滑保留、上滑回收、恢复）与后台元数据写入严格按入队先后顺序落盘，
 * 绝不因多线程竞争丢失 tail 或出现乱序覆盖。
 */
internal class PhotoWriteQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val lock = Any()
    private var tail: Job? = null

    fun enqueue(write: suspend () -> Unit): Job {
        synchronized(lock) {
            val previous = tail
            val job = scope.launch(dispatcher) {
                previous?.join()
                write()
            }
            tail = job
            return job
        }
    }

    suspend fun awaitPendingWrites() {
        while (true) {
            val current = synchronized(lock) { tail } ?: return
            current.join()
            if (synchronized(lock) { tail === current }) return
        }
    }
}

/**
 * 快速连滑审阅写入合并器 (ReviewWriteCoalescer)
 *
 * 极速连续左滑时将多张照片的 `markViewed` 写入合并为单次 SQLite 事务提交，
 * 大幅削减 WAL checkpoint 频率与 IO 争抢；任何强顺序操作（上滑删除、换批、同步、进设置、退后台）
 * 前调用 [flushNow] 同步排入 [PhotoWriteQueue]，保证 100% 顺序一致性与零丢失。
 */
internal class ReviewWriteCoalescer(
    private val writeQueue: PhotoWriteQueue,
    private val scope: CoroutineScope,
    private val commitBatch: suspend (List<Pair<Long, Long>>) -> Unit,
    private val flushThreshold: Int = 8,
    private val flushDelayMs: Long = 380L,
    private val delayDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val lock = Any()
    private val pending = ArrayList<Pair<Long, Long>>(flushThreshold)
    private var scheduledFlushJob: Job? = null

    fun recordReview(photoId: Long, viewedAt: Long) {
        val batchToFlush: List<Pair<Long, Long>>?
        synchronized(lock) {
            pending.add(photoId to viewedAt)
            if (pending.size >= flushThreshold) {
                scheduledFlushJob?.cancel()
                scheduledFlushJob = null
                batchToFlush = ArrayList(pending)
                pending.clear()
            } else {
                batchToFlush = null
                scheduledFlushJob?.cancel()
                scheduledFlushJob = scope.launch(delayDispatcher) {
                    delay(flushDelayMs)
                    flushNow()
                }
            }
        }
        if (batchToFlush != null) {
            enqueueBatch(batchToFlush)
        }
    }

    /** 同步抽取当前缓冲并立刻排入写队列（保证后续入队的回收/查询严格观测到之前的保留审阅） */
    fun flushNow(): Job? {
        val batchToFlush: List<Pair<Long, Long>> = synchronized(lock) {
            scheduledFlushJob?.cancel()
            scheduledFlushJob = null
            if (pending.isEmpty()) return null
            ArrayList(pending).also { pending.clear() }
        }
        return enqueueBatch(batchToFlush)
    }

    private fun enqueueBatch(batch: List<Pair<Long, Long>>): Job =
        writeQueue.enqueue { commitBatch(batch) }
}
