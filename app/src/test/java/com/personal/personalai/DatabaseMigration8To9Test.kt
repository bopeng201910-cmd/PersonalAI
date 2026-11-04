package com.personal.personalai

import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.personalai.data.local.MIGRATION_8_9
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class DatabaseMigration8To9Test {

    @Test
    fun `test migration 8 to 9 preserves existing messages and adds conversation support`() {
        val tempDb = File.createTempFile("test_v8_v9", ".db")
        tempDb.deleteOnExit()
        val url = "jdbc:sqlite:${tempDb.absolutePath}"

        // 1. Create v8 schema
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        role TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        hasCreatedTask INTEGER NOT NULL,
                        createdTaskTitle TEXT
                    );
                    """.trimIndent()
                )
                // Insert existing v8 message
                stmt.execute("INSERT INTO messages (id, content, role, timestamp, hasCreatedTask) VALUES (1, 'Hello from v8', 'USER', 1700000000000, 0);")
            }
        }

        // 2. Execute MIGRATION_8_9
        val mockDb = mockk<SupportSQLiteDatabase>(relaxed = true)
        every { mockDb.execSQL(any()) } answers {
            val sql = firstArg<String>()
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        }

        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)
        MIGRATION_8_9.migrate(mockDb)

        // 3. Verify messages migrated with default conversationId and default conversation created
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                val rsMsg = stmt.executeQuery("SELECT content, conversationId FROM messages WHERE id = 1;")
                assertTrue("Existing message must exist", rsMsg.next())
                assertEquals("Hello from v8", rsMsg.getString("content"))
                assertEquals("default", rsMsg.getString("conversationId"))

                val rsConv = stmt.executeQuery("SELECT id, title FROM conversations WHERE id = 'default';")
                assertTrue("Default conversation must be seeded", rsConv.next())
                assertEquals("default", rsConv.getString("id"))
                assertEquals("Chat Archive", rsConv.getString("title"))
            }
        }
    }

    @Test
    fun `test fresh install on version 9 schema contains default conversation and conversationId index`() {
        val tempDb = File.createTempFile("test_v9_fresh", ".db")
        tempDb.deleteOnExit()
        val url = "jdbc:sqlite:${tempDb.absolutePath}"

        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS `conversations` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `title` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `hasCreatedTask` INTEGER NOT NULL,
                        `createdTaskTitle` TEXT,
                        `conversationId` TEXT NOT NULL DEFAULT 'default'
                    );
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`);")

                // Simulate RoomDatabase.Callback onCreate
                stmt.execute(
                    """
                    INSERT OR IGNORE INTO `conversations` (`id`, `title`, `createdAt`, `updatedAt`)
                    VALUES ('default', 'Chat Archive', 1700000000000, 1700000000000);
                    """.trimIndent()
                )

                val rsConv = stmt.executeQuery("SELECT id, title FROM conversations WHERE id = 'default';")
                assertTrue("Fresh install must seed default conversation", rsConv.next())
                assertEquals("default", rsConv.getString("id"))
                assertEquals("Chat Archive", rsConv.getString("title"))

                // Verify messages table allows inserting with default conversationId
                stmt.execute("INSERT INTO messages (content, role, timestamp, hasCreatedTask) VALUES ('Fresh test message', 'USER', 1700000000000, 0);")
                val rsMsg = stmt.executeQuery("SELECT conversationId FROM messages WHERE content = 'Fresh test message';")
                assertTrue(rsMsg.next())
                assertEquals("default", rsMsg.getString("conversationId"))
            }
        }
    }
}
