package com.personal.personalai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.personalai.data.local.dao.ApiUsageLogDao
import com.personal.personalai.data.local.dao.CandidateMemoryDao
import com.personal.personalai.data.local.dao.ConversationDao
import com.personal.personalai.data.local.dao.GeofenceTaskDao
import com.personal.personalai.data.local.dao.MemoryDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.dao.ScheduledTaskDao
import com.personal.personalai.data.local.entity.ApiUsageLogEntity
import com.personal.personalai.data.local.entity.CandidateMemoryEntity
import com.personal.personalai.data.local.entity.ConversationEntity
import com.personal.personalai.data.local.entity.GeofenceTaskEntity
import com.personal.personalai.data.local.entity.MemoryEntity
import com.personal.personalai.data.local.entity.MessageEntity
import com.personal.personalai.data.local.entity.ScheduledTaskEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                content TEXT NOT NULL,
                topic TEXT NOT NULL DEFAULT '',
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN taskType TEXT NOT NULL DEFAULT 'REMINDER'")
        database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN aiPrompt TEXT")
        database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN outputTarget TEXT NOT NULL DEFAULT 'NOTIFICATION'")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN recurrenceType TEXT NOT NULL DEFAULT 'NONE'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS geofence_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                radiusMeters REAL NOT NULL DEFAULT 100.0,
                transitionType TEXT NOT NULL DEFAULT 'ENTER',
                taskType TEXT NOT NULL DEFAULT 'REMINDER',
                description TEXT NOT NULL DEFAULT '',
                aiPrompt TEXT,
                outputTarget TEXT NOT NULL DEFAULT 'NOTIFICATION',
                isActive INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE geofence_tasks ADD COLUMN locationName TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS api_usage_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                provider TEXT NOT NULL,
                model TEXT NOT NULL,
                apiType TEXT NOT NULL,
                inputTokens INTEGER NOT NULL DEFAULT 0,
                outputTokens INTEGER NOT NULL DEFAULT 0,
                cachedInputTokens INTEGER NOT NULL DEFAULT 0,
                totalTokens INTEGER NOT NULL DEFAULT 0,
                audioDurationSeconds REAL,
                estimatedCostUsd REAL NOT NULL DEFAULT 0.0,
                latencyMs INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL DEFAULT 1,
                errorMessage TEXT
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `candidate_memories` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `content` TEXT NOT NULL,
                `topic` TEXT NOT NULL DEFAULT '',
                `originalSnippet` TEXT NOT NULL DEFAULT '',
                `category` TEXT NOT NULL DEFAULT 'OTHER_IMPORTANT',
                `sourceType` TEXT NOT NULL DEFAULT 'EXPLICIT_FACT',
                `sourceMessageId` INTEGER NOT NULL DEFAULT 0,
                `sourceTimestamp` INTEGER NOT NULL DEFAULT 0,
                `status` TEXT NOT NULL DEFAULT 'PENDING',
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_candidate_memories_status` ON `candidate_memories` (`status`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_candidate_memories_sourceMessageId` ON `candidate_memories` (`sourceMessageId`)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` TEXT PRIMARY KEY NOT NULL,
                `title` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL("ALTER TABLE `messages` ADD COLUMN `conversationId` TEXT NOT NULL DEFAULT 'default'")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")

        // Seed default conversation if any historical messages exist
        database.execSQL(
            """
            INSERT OR IGNORE INTO `conversations` (`id`, `title`, `createdAt`, `updatedAt`)
            VALUES ('default', 'Chat Archive', strftime('%s','now') * 1000, strftime('%s','now') * 1000)
            """.trimIndent()
        )
    }
}

@Database(
    entities = [
        MessageEntity::class,
        ScheduledTaskEntity::class,
        MemoryEntity::class,
        GeofenceTaskEntity::class,
        ApiUsageLogEntity::class,
        CandidateMemoryEntity::class,
        ConversationEntity::class,
    ],
    version = 9,
    exportSchema = false
)
abstract class PersonalAIDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun geofenceTaskDao(): GeofenceTaskDao
    abstract fun apiUsageLogDao(): ApiUsageLogDao
    abstract fun candidateMemoryDao(): CandidateMemoryDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        val ROOM_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Ensure fresh installs on version 9 have the default conversation initialized
                seedDefaultConversation(db)
            }

            fun seedDefaultConversation(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `conversations` (`id`, `title`, `createdAt`, `updatedAt`)
                    VALUES ('default', 'Chat Archive', strftime('%s','now') * 1000, strftime('%s','now') * 1000)
                    """.trimIndent()
                )
            }
        }
    }
}
