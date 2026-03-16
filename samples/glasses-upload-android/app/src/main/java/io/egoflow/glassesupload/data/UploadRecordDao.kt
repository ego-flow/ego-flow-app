package io.egoflow.glassesupload.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UploadRecordDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(record: UploadRecordEntity): Long

  @Query("SELECT * FROM upload_records WHERE id = :id LIMIT 1")
  suspend fun getById(id: Long): UploadRecordEntity?

  @Query("SELECT * FROM upload_records ORDER BY discoveredAt DESC, id DESC LIMIT :limit OFFSET :offset")
  suspend fun historyPage(limit: Int, offset: Int): List<UploadRecordEntity>

  @Query("SELECT COUNT(*) FROM upload_records")
  suspend fun totalCount(): Int

  @Query("SELECT * FROM upload_records WHERE status IN (:statuses)")
  suspend fun loadByStatuses(statuses: List<String>): List<UploadRecordEntity>

  @Query(
      """
      UPDATE upload_records
      SET status = :status,
          errorMessage = :errorMessage,
          uploadedAt = :uploadedAt
      WHERE id = :id
      """)
  suspend fun updateStatus(id: Long, status: UploadStatus, errorMessage: String?, uploadedAt: Long?)
}
