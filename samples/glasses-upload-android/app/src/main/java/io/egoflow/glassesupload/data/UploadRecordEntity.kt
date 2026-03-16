package io.egoflow.glassesupload.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_records",
    indices = [Index(value = ["sourcePath"], unique = true)],
)
data class UploadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourcePath: String,
    val title: String,
    val durationMs: Long?,
    val discoveredAt: Long,
    val status: UploadStatus,
    val errorMessage: String?,
    val uploadedAt: Long?,
)
