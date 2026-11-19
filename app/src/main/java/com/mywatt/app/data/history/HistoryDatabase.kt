package com.mywatt.app.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.mywatt.app.engine.SessionResult

@Dao
abstract class HistoryDao {
    @Insert
    abstract suspend fun insertSession(session: SessionRecord): Long

    @Insert
    abstract suspend fun insertReps(reps: List<RepRecord>)

    /** Saves a session and its reps together; returns the session's row id. */
    @Transaction
    open suspend fun insert(result: SessionResult, startedAtMs: Long, endedAtMs: Long): Long {
        val id = insertSession(result.toRecord(startedAtMs, endedAtMs))
        insertReps(result.reps.map { it.toRecord(id) })
        return id
    }

    /** Every session, newest first. */
    @Query("SELECT * FROM session_results ORDER BY startedAtMs DESC, id DESC")
    abstract suspend fun sessions(): List<SessionRecord>

    @Query("SELECT * FROM session_results WHERE id = :id")
    abstract suspend fun session(id: Long): SessionRecord?

    @Query("SELECT * FROM rep_results WHERE sessionRecordId = :sessionRecordId ORDER BY repNumber")
    abstract suspend fun reps(sessionRecordId: Long): List<RepRecord>

    /** A session and its reps, or null if there is no such session. */
    @Transaction
    open suspend fun sessionWithReps(id: Long): SessionWithReps? {
        val session = session(id) ?: return null
        return SessionWithReps(session, reps(id))
    }
}

/** The training history: one row per session, one per rep. */
@Database(entities = [SessionRecord::class, RepRecord::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        const val FILE_NAME = "mywatt.db"

        @Volatile private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: open(context, FILE_NAME).also { instance = it }
        }

        /** Opens the database in [fileName]; [get] for the app's own history. */
        fun open(context: Context, fileName: String): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, fileName).build()
    }
}
