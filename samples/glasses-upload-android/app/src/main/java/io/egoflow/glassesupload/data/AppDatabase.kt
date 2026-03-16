package io.egoflow.glassesupload.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(entities = [UploadRecordEntity::class], version = 1, exportSchema = false)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun uploadRecordDao(): UploadRecordDao

  companion object {
    @Volatile private var instance: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase =
        instance
            ?: synchronized(this) {
              instance
                  ?: Room.databaseBuilder(context, AppDatabase::class.java, "egoflow-upload.db")
                      .fallbackToDestructiveMigration()
                      .build()
                      .also { instance = it }
            }
  }
}

class RoomConverters {
  @TypeConverter fun toStatus(value: String): UploadStatus = UploadStatus.valueOf(value)

  @TypeConverter fun fromStatus(status: UploadStatus): String = status.name
}
