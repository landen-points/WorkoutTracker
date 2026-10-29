package com.example.workouttracker

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

internal data class DatabaseContents(val exercises: List<ExerciseWithSets>, val weights: List<StoredWeight>)

internal object DatabaseBackup {
    private const val MIGRATION = "legacy_preferences_v1"
    private fun temporary(context: Context) = File.createTempFile("workouts-snapshot-", ".sqlite", context.cacheDir)
    private fun open(context: Context, file: File) = Room.databaseBuilder(
        context.applicationContext, WorkoutDatabase::class.java, file.absolutePath)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()

    suspend fun write(context: Context, source: WorkoutDatabase, output: OutputStream) = withContext(Dispatchers.IO) {
        // Copy a transactional logical snapshot into a new standalone SQLite file.
        // Closing a TRUNCATE-journal database makes the file independent of WAL/SHM sidecars.
        val records = source.withTransaction {
            DatabaseContents(source.workouts().allExercises(), source.workouts().allWeights())
        }
        val file = temporary(context)
        try {
            val target = open(context, file)
            try {
                target.withTransaction {
                    records.exercises.forEach { row ->
                        target.workouts().putExercise(row.exercise)
                        target.workouts().putSets(row.sets)
                    }
                    records.weights.forEach { target.workouts().putWeight(it) }
                    target.workouts().markMigrated(MigrationStatus(MIGRATION))
                }
            } finally { target.close() }
            file.inputStream().use { it.copyTo(output) }
            output.flush()
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    suspend fun read(context: Context, input: InputStream): DatabaseContents = withContext(Dispatchers.IO) {
        val file = temporary(context)
        try {
            file.outputStream().use { target ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= 512L * 1024 * 1024) { "Backup exceeds 512 MB." }
                    target.write(buffer, 0, read)
                }
            }
            require(file.length() >= 100) { "This is not a SQLite backup." }
            val signature = file.inputStream().use { stream ->
                val bytes = ByteArray(16)
                require(stream.read(bytes) == 16) { "Incomplete backup file." }
                String(bytes, Charsets.US_ASCII)
            }
            require(signature == "SQLite format 3\u0000") { "Choose a Workout Tracker SQLite backup." }
            // Check the original staged file before Room is allowed to open it.
            SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sqlite ->
                require(sqlite.version == WorkoutDatabase.VERSION) { "This backup uses an unsupported database version." }
                sqlite.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "Backup integrity check failed." }
                }
                sqlite.rawQuery("PRAGMA foreign_key_check", null).use {
                    require(!it.moveToFirst()) { "Backup contains invalid record references." }
                }
            }
            val staged = open(context, file)
            try {
                // Room also checks its schema identity; incompatible schemas never reach live writes.
                require(staged.workouts().migrated(MIGRATION) == 1) { "Not a completed Workout Tracker backup." }
                val records = staged.withTransaction {
                    DatabaseContents(staged.workouts().allExercises(), staged.workouts().allWeights())
                }
                validate(records)
                staged.openHelper.readableDatabase.query("SELECT COUNT(*) FROM exercise_sets").use {
                    require(it.moveToFirst() && it.getLong(0) == records.exercises.sumOf { row -> row.sets.size }.toLong()) {
                        "Backup contains orphaned sets."
                    }
                }
                records
            } finally { staged.close() }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    private fun validate(records: DatabaseContents) {
        for (row in records.exercises) {
            val exercise = row.exercise
            val day = LocalDate.parse(exercise.date)
            require(exercise.key == "$day/${exercise.id}" && exercise.id.isNotBlank() && exercise.ordinal >= 0) { "Invalid exercise identity." }
            require(row.sets.sortedBy { it.position }.map { it.position } == row.sets.indices.toList()) { "Invalid set order." }
            require(row.sets.all { it.exerciseKey == exercise.key && it.weight.isFinite() && it.reps > 0 }) { "Invalid exercise sets." }
            exercise.cardio?.let {
                require(it.zone in 1..5 && it.averageHeartRate in 1..300 && it.minutes.isFinite() && it.minutes > 0) { "Invalid cardio record." }
            }
        }
        records.weights.forEach {
            require(LocalDate.parse(it.date).toString() == it.date && it.pounds.isFinite() && it.pounds > 0) { "Invalid daily weight." }
        }
    }
}
