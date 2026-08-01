package com.david.openassistant.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.david.openassistant.data.database.dao.ActiveConversationDao
import com.david.openassistant.data.database.dao.AttachmentDao
import com.david.openassistant.data.database.dao.ConversationDao
import com.david.openassistant.data.database.dao.MessageDao
import com.david.openassistant.data.database.entity.ActiveConversationEntity
import com.david.openassistant.data.database.entity.AttachmentEntity
import com.david.openassistant.data.database.entity.ConversationEntity
import com.david.openassistant.data.database.entity.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        ActiveConversationEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OpenAssistantDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun activeConversationDao(): ActiveConversationDao

    companion object {
        private const val DATABASE_NAME = "openassistant_db"

        @Volatile
        private var INSTANCE: OpenAssistantDatabase? = null

        fun getDatabase(context: Context): OpenAssistantDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    OpenAssistantDatabase::class.java,
                    DATABASE_NAME
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
