package com.lumina.cleaner.domain.album

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

// Room is a review history, not an inventory. Missing or inaccessible IDs stay in Room.
internal fun activeLibraryCount(visible: Set<Long>, trash: Collection<Long>): Int =
    visible.size - trash.count { it in visible } // local_trash.photoId is the primary key

/** Observe only while the statistics are on screen. Refresh once on each subscription. */
internal fun ContentResolver.visibleImageIds() = callbackFlow<Unit> {
    val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) { trySend(Unit) }
    }
    val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    registerContentObserver(images, true, observer)
    trySend(Unit)
    awaitClose { unregisterContentObserver(observer) }
}.conflate().mapNotNull {
    try {
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            null, null, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
    } catch (_: SecurityException) {
        emptySet<Long>() // permission changed between resume and the query
    }
}.flowOn(Dispatchers.IO)
