package com.lumina.cleaner.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Mundru 核心数据库 (Room SQLite WAL 架构)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心机制：
 * 1. 采用 SQLite Write-Ahead Logging (WAL) 高性能并发读写模式；
 * 2. 负责本地照片状态管理、实况/HDR 元数据快速缓存、24h 冷却衰减与回收站物理销毁双重索引；
 * 3. 100% 纯本地离线运行，坚守绝对隐私契约。
 */
@Database(
    entities = [
        PhotoEntity::class,
        LocalTrashEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MundruDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        private const val DB_NAME = "mundru_cleaner.db"

        @Volatile
        private var instance: MundruDatabase? = null

        fun getInstance(context: Context): MundruDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MundruDatabase::class.java,
                    DB_NAME
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING) // 启用 WAL 并发读取模式
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
        }
    }
}

// 兼容别名
typealias LuminaDatabase = MundruDatabase
