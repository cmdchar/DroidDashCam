package com.helge.prodashcam.data.db

import androidx.room.*

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val timestamp: Long,
    val isLocked: Boolean = false,
    val cameraType: String // FRONT or BACK
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    suspend fun getAll(): List<RecordingEntity>

    @Insert
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Delete
    suspend fun delete(recording: RecordingEntity)
}

@Database(entities = [RecordingEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}
