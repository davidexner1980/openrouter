package com.david.openassistant.data.database.dao

import androidx.room.*
import com.david.openassistant.data.database.entity.AttachmentEntity

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)
}
