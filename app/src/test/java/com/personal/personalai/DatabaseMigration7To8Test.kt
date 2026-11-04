package com.personal.personalai

import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.personalai.data.local.MIGRATION_7_8
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class DatabaseMigration7To8Test {

    @Test
    fun testMigration7To8PreservesDataAndValidatesSchemaUsingSqlite() {
        val tempDb = File.createTempFile("test_v7_v8", ".db")
        tempDb.deleteOnExit()
        val url = "jdbc:sqlite:${tempDb.absolutePath}"

        // 1. Connect via SQLite JDBC and create the full V7 database schema
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
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        scheduledAt INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        workerId TEXT,
                        taskType TEXT NOT NULL DEFAULT 'REMINDER',
                        aiPrompt TEXT,
                        outputTarget TEXT NOT NULL DEFAULT 'NOTIFICATION',
                        recurrenceType TEXT NOT NULL DEFAULT 'NONE'
                    );
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        content TEXT NOT NULL,
                        topic TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                stmt.execute(
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
                        createdAt INTEGER NOT NULL,
                        locationName TEXT NOT NULL DEFAULT ''
                    );
                    """.trimIndent()
                )
                stmt.execute(
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
                    );
                    """.trimIndent()
                )

                // 2. Insert pre-existing V7 user data
                stmt.execute("INSERT INTO messages (id, content, role, timestamp, hasCreatedTask) VALUES (1, 'V7 Message 1', 'USER', 1700000000000, 0);")
                stmt.execute("INSERT INTO memories (id, content, topic, createdAt) VALUES (10, 'V7 Memory Fact', 'preferences', 1700000001000);")
                stmt.execute("INSERT INTO scheduled_tasks (id, title, description, scheduledAt, isCompleted, createdAt) VALUES (20, 'V7 Scheduled Task', 'Desc', 1700000002000, 0, 1700000003000);")
            }
        }

        // 3. Execute MIGRATION_7_8 on the real SQLite database via SupportSQLiteDatabase wrapper
        val mockDb = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlCommands = mutableListOf<String>()
        every { mockDb.execSQL(capture(sqlCommands)) } answers {
            val sql = firstArg<String>()
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        }

        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)
        MIGRATION_7_8.migrate(mockDb)

        // 4. Verify existing V7 data was preserved intact
        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                val rsMsg = stmt.executeQuery("SELECT content, role FROM messages WHERE id = 1;")
                assertTrue("V7 Message must exist", rsMsg.next())
                assertEquals("V7 Message 1", rsMsg.getString("content"))
                assertEquals("USER", rsMsg.getString("role"))

                val rsMem = stmt.executeQuery("SELECT content, topic FROM memories WHERE id = 10;")
                assertTrue("V7 Memory must exist", rsMem.next())
                assertEquals("V7 Memory Fact", rsMem.getString("content"))

                val rsTask = stmt.executeQuery("SELECT title FROM scheduled_tasks WHERE id = 20;")
                assertTrue("V7 Task must exist", rsTask.next())
                assertEquals("V7 Scheduled Task", rsTask.getString("title"))
            }

            // 5. Verify candidate_memories table structure, columns, nullability, and default values
            val columns = mutableMapOf<String, ColumnMeta>()
            conn.createStatement().use { stmt ->
                val rsTableInfo = stmt.executeQuery("PRAGMA table_info(candidate_memories);")
                while (rsTableInfo.next()) {
                    val name = rsTableInfo.getString("name")
                    val type = rsTableInfo.getString("type")
                    val notNull = rsTableInfo.getInt("notnull") == 1
                    val dfltValue = rsTableInfo.getString("dflt_value")
                    val pk = rsTableInfo.getInt("pk") == 1
                    columns[name] = ColumnMeta(name, type, notNull, dfltValue, pk)
                }
            }

            assertEquals(10, columns.size)

            // id: INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
            val idCol = columns["id"]
            assertNotNull(idCol)
            assertTrue(idCol!!.pk)
            assertEquals("INTEGER", idCol.type)
            assertTrue(idCol.notNull)

            // content: TEXT NOT NULL, no default
            val contentCol = columns["content"]
            assertNotNull(contentCol)
            assertEquals("TEXT", contentCol!!.type)
            assertTrue(contentCol.notNull)

            // topic: TEXT NOT NULL DEFAULT ''
            val topicCol = columns["topic"]
            assertNotNull(topicCol)
            assertEquals("TEXT", topicCol!!.type)
            assertTrue(topicCol.notNull)
            assertEquals("''", topicCol.dfltValue)

            // originalSnippet: TEXT NOT NULL DEFAULT ''
            val snippetCol = columns["originalSnippet"]
            assertNotNull(snippetCol)
            assertEquals("TEXT", snippetCol!!.type)
            assertTrue(snippetCol.notNull)
            assertEquals("''", snippetCol.dfltValue)

            // category: TEXT NOT NULL DEFAULT 'OTHER_IMPORTANT'
            val catCol = columns["category"]
            assertNotNull(catCol)
            assertEquals("TEXT", catCol!!.type)
            assertTrue(catCol.notNull)
            assertEquals("'OTHER_IMPORTANT'", catCol.dfltValue)

            // sourceType: TEXT NOT NULL DEFAULT 'EXPLICIT_FACT'
            val srcTypeCol = columns["sourceType"]
            assertNotNull(srcTypeCol)
            assertEquals("TEXT", srcTypeCol!!.type)
            assertTrue(srcTypeCol.notNull)
            assertEquals("'EXPLICIT_FACT'", srcTypeCol.dfltValue)

            // sourceMessageId: INTEGER NOT NULL DEFAULT 0
            val srcMsgCol = columns["sourceMessageId"]
            assertNotNull(srcMsgCol)
            assertEquals("INTEGER", srcMsgCol!!.type)
            assertTrue(srcMsgCol.notNull)
            assertEquals("0", srcMsgCol.dfltValue)

            // sourceTimestamp: INTEGER NOT NULL DEFAULT 0
            val srcTsCol = columns["sourceTimestamp"]
            assertNotNull(srcTsCol)
            assertEquals("INTEGER", srcTsCol!!.type)
            assertTrue(srcTsCol.notNull)
            assertEquals("0", srcTsCol.dfltValue)

            // status: TEXT NOT NULL DEFAULT 'PENDING'
            val statusCol = columns["status"]
            assertNotNull(statusCol)
            assertEquals("TEXT", statusCol!!.type)
            assertTrue(statusCol.notNull)
            assertEquals("'PENDING'", statusCol.dfltValue)

            // createdAt: INTEGER NOT NULL, no default
            val createdCol = columns["createdAt"]
            assertNotNull(createdCol)
            assertEquals("INTEGER", createdCol!!.type)
            assertTrue(createdCol.notNull)

            // 6. Verify indices on candidate_memories table
            val indices = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rsIndices = stmt.executeQuery("PRAGMA index_list(candidate_memories);")
                while (rsIndices.next()) {
                    indices.add(rsIndices.getString("name"))
                }
            }
            assertTrue("Index on status must exist", indices.contains("index_candidate_memories_status"))
            assertTrue("Index on sourceMessageId must exist", indices.contains("index_candidate_memories_sourceMessageId"))

            // 7. Verify insertion with defaults functions as expected in SQLite
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO candidate_memories (content, createdAt) VALUES ('Default Candidate', 1700000010000);")
                val rsCheck = stmt.executeQuery("SELECT * FROM candidate_memories WHERE content = 'Default Candidate';")
                assertTrue(rsCheck.next())
                assertEquals("", rsCheck.getString("topic"))
                assertEquals("", rsCheck.getString("originalSnippet"))
                assertEquals("OTHER_IMPORTANT", rsCheck.getString("category"))
                assertEquals("EXPLICIT_FACT", rsCheck.getString("sourceType"))
                assertEquals(0L, rsCheck.getLong("sourceMessageId"))
                assertEquals(0L, rsCheck.getLong("sourceTimestamp"))
                assertEquals("PENDING", rsCheck.getString("status"))
                assertEquals(1700000010000L, rsCheck.getLong("createdAt"))
            }
        }
    }

    private data class ColumnMeta(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val dfltValue: String?,
        val pk: Boolean
    )
}
