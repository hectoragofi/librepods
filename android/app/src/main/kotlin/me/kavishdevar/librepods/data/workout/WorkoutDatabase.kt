/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data.workout

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import java.io.File

/**
 * Workout sessions and samples, kept in their own "workouts.db" file.
 *
 * Version history:
 * - 1: written by the earlier Room 2 heart-rate build. Room 2 and Room 3 compute different identity
 *   hashes for the same schema, so opening a v1 file as v1 here would fail the integrity check.
 * - 2: same tables and columns as v1. [MIGRATION_1_2] is intentionally empty; Room validates the
 *   existing tables against the entities after running it and then stores the Room 3 hash.
 */
@Database(
    entities = [WorkoutSessionEntity::class, WorkoutSampleEntity::class],
    version = 2,
)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

    companion object {
        private const val TAG = "WorkoutDatabase"
        const val FILE_NAME = "workouts.db"
        private const val KEY_RECOVERED_BACKUP = "workout_db_recovered_backup"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Schema unchanged; only the Room identity hash changes.
            }
        }

        private fun build(context: Context): WorkoutDatabase = Room.databaseBuilder(
            context.applicationContext,
            WorkoutDatabase::class.java,
            FILE_NAME,
        )
            .addMigrations(MIGRATION_1_2)
            .build()

        /**
         * Opens the database and forces Room to run migration and schema validation right away.
         *
         * Never deletes data: if the existing file cannot be opened (unexpected schema, corruption,
         * a future version), it is renamed to a timestamped backup next to it, a fresh database is
         * created, and the backup name is recorded so the UI can tell the user.
         */
        suspend fun open(context: Context, preferences: SharedPreferences): WorkoutDatabase {
            val database = build(context)
            try {
                database.useWriterConnection { }
                return database
            } catch (error: Exception) {
                Log.e(TAG, "Could not open $FILE_NAME, moving it aside and starting fresh", error)
                database.close()
            }

            val original = context.getDatabasePath(FILE_NAME)
            val backupName = "workouts-unreadable-${System.currentTimeMillis()}.db"
            val moved = moveAside(original, File(original.parentFile, backupName))
            if (moved) {
                preferences.edit { putString(KEY_RECOVERED_BACKUP, backupName) }
            } else {
                // Could not move the file out of the way: keep it untouched and fail loudly
                // rather than letting Room overwrite it.
                throw IllegalStateException("$FILE_NAME could not be opened or backed up")
            }
            return build(context).also { it.useWriterConnection { } }
        }

        /** Name of a backup made by [open], if any, so the UI can mention it. */
        fun recoveredBackupName(preferences: SharedPreferences): String? =
            preferences.getString(KEY_RECOVERED_BACKUP, null)

        fun clearRecoveredBackupNotice(preferences: SharedPreferences) {
            preferences.edit { remove(KEY_RECOVERED_BACKUP) }
        }

        private fun moveAside(original: File, backup: File): Boolean {
            if (!original.exists()) return true
            if (!original.renameTo(backup)) return false
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val side = File(original.path + suffix)
                if (side.exists()) side.renameTo(File(backup.path + suffix))
            }
            return true
        }
    }
}
