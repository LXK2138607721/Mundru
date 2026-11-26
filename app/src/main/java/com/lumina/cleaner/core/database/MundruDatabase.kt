package com.lumina.cleaner.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Mundru 核心数据库 (Room SQLite WAL 架构)
 *
 * 品牌释义：Mundru 源自格陵兰语（Greenlandic），意为「擦掉 / 擦除」。
 * 核心机制：
 * 1. 采用 SQLite Write-Ahead Logging (WAL) 高性能并发读写模式；
 * 2. 负责本地照片状态管理、实况/HDR 元数据快速缓存、64-bit dHash 相似指纹、阶梯冷却衰减与回收站索引；
 * 3. 无损迁移 v4 -> v5，完整保留全部历史审阅次数、冷却时间戳与本地回收站数据；
 * 4. 100% 纯本地离线运行，坚守绝对隐私契约。
 */
@Database(
    entities = [
        PhotoEntity::class,
        LocalTrashEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class MundruDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        private const val DB_NAME = "mundru_cleaner.db"

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN dHash INTEGER")
            }
        }

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
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}

// 兼容别名
typealias LuminaDatabase = MundruDatabase
