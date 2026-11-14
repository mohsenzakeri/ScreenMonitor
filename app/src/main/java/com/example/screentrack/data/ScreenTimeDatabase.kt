package com.example.screentrack.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Entity(tableName = "daily_entries")
data class DailyEntry(
    @PrimaryKey val date: String,
    val minutes: Int,
    val note: String = ""
)

@Dao
interface DailyEntryDao {
    @Query("SELECT * FROM daily_entries ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyEntry>>

    @Query("SELECT * FROM daily_entries WHERE date = :date LIMIT 1")
    suspend fun get(date: String): DailyEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DailyEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DailyEntry>)

    @Query("DELETE FROM daily_entries WHERE date = :date")
    suspend fun delete(date: String)
}

@Database(entities = [DailyEntry::class], version = 1, exportSchema = false)
abstract class ScreenTimeDatabase : RoomDatabase() {
    abstract fun entries(): DailyEntryDao

    companion object {
        @Volatile private var INSTANCE: ScreenTimeDatabase? = null
        fun get(context: Context): ScreenTimeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScreenTimeDatabase::class.java,
                    "screentrack.db"
                ).build().also { INSTANCE = it }
            }
    }
}
