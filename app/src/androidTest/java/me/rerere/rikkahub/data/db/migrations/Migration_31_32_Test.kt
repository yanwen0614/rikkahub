package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_31_32_Test {
    private val testDb = "migration-31-32-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun addsParentIdSubAgentColumns() {
        helper.createDatabase(testDb, 31).apply {
            execSQL(
                "INSERT INTO conversationentity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 32, true, Migration_31_32)

        db.query("SELECT parent_id, sub_agent_config_id, sub_agent_snapshot FROM conversationentity WHERE id = 'conv'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("", cursor.getString(2))
        }

        // Verify index exists
        db.query("PRAGMA index_list('conversationentity')").use { cursor ->
            val hasIndex = buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(1))
                }
            }.any { it == "index_ConversationEntity_parent_id" }
            assertTrue("parent_id index not found", hasIndex)
        }

        db.close()
    }
}
