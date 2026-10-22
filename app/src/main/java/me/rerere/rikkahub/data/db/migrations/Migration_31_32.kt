package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN parent_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN sub_agent_config_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversationentity ADD COLUMN sub_agent_snapshot TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ConversationEntity_parent_id` ON `conversationentity` (`parent_id`)")
    }
}
