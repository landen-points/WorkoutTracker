package com.example.workouttracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class WorkoutRepository internal constructor(
    private val db: WorkoutDatabase,
    private val legacy: () -> LegacyRecords
) {
    private val mutex = Mutex()
    @Volatile private var snapshot: LegacyRecords? = null
    private val changes = MutableStateFlow(0)
    val revision = changes.asStateFlow()
    private val dao get() = db.workouts()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (snapshot != null) return@withLock
            db.withTransaction {
                if (dao.migrated("legacy_preferences_v1") == 0) {
                    val records = legacy() // Decode both files before inserting anything.
                    for ((date, exercises) in records.workouts) {
                        exercises.forEachIndexed { ordinal, exercise -> put(date, exercise, ordinal) }
                    }
                    records.weights.forEach { (date, pounds) -> dao.putWeight(StoredWeight(date.toString(), pounds)) }
                    val copied = readDatabase()
                    check(records.workouts.filterValues { it.isNotEmpty() } == copied.workouts && records.weights == copied.weights) {
                        "Legacy migration verification failed. Original data has been retained."
                    }
                    dao.markMigrated(MigrationStatus("legacy_preferences_v1"))
                }
            }
            reload()
        }
    }

    // UI reads use an immutable snapshot loaded on IO, so Compose never blocks on SQLite.
    private fun current() = checkNotNull(snapshot) { "Workout storage is not ready." }
    fun getExercises(date: LocalDate) = current().workouts[date].orEmpty()
    fun allWeights() = current().weights
    fun daysWithEntries(month: YearMonth) = current().workouts.filter { (day, list) ->
        YearMonth.from(day) == month && list.isNotEmpty()
    }.keys.map { it.dayOfMonth }.toSet()
    fun allExerciseNames() = current().workouts.values.flatten().map { it.title }
        .distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
    fun history(name: String) = current().workouts.flatMap { (date, list) ->
        list.filter { it.title.equals(name, ignoreCase = true) }.map { HistoryEntry(date, it) }
    }.sortedByDescending { it.date }

    private suspend fun readDatabase(): LegacyRecords {
        val workouts = dao.allExercises().groupBy { LocalDate.parse(it.exercise.date) }
            .mapValues { (_, rows) -> rows.map { it.model() } }
        return LegacyRecords(workouts, dao.allWeights().associate { LocalDate.parse(it.date) to it.pounds })
    }
    private suspend fun reload() {
        snapshot = db.withTransaction { readDatabase() }
        changes.value++
    }
    private suspend fun put(date: LocalDate, exercise: Exercise, ordinal: Int) {
        val key = "$date/${exercise.id}"
        dao.putExercise(StoredExercise(key, exercise.id, date.toString(), exercise.title, ordinal, exercise.cardio))
        dao.deleteSets(key)
        dao.putSets(exercise.sets.mapIndexed { i, set -> StoredSet(key, i, set.weight, set.reps) })
    }
    private suspend fun write(block: suspend () -> Unit) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock {
            current()
            db.withTransaction { block() }
            reload()
        }
    }
    suspend fun addExercise(date: LocalDate, exercise: Exercise) = write {
        put(date, exercise, dao.ordinal("$date/${exercise.id}") ?: dao.nextOrdinal(date.toString()))
    }
    suspend fun updateExercise(date: LocalDate, exercise: Exercise) = write {
        val ordinal = checkNotNull(dao.ordinal("$date/${exercise.id}")) { "Exercise no longer exists." }
        put(date, exercise, ordinal)
    }
    suspend fun deleteExercise(date: LocalDate, exerciseId: String) = write { dao.deleteExercise("$date/$exerciseId") }
    suspend fun saveWeight(date: LocalDate, pounds: Double) = write {
        require(pounds.isFinite() && pounds > 0)
        dao.putWeight(StoredWeight(date.toString(), pounds))
    }
    suspend fun clearAllData() = write {
        // Keep the migration marker: deleted records must never reappear from legacy files.
        dao.deleteAllExercises() // Foreign keys cascade to every set and cardio entry.
        dao.deleteAllWeights()
    }
    suspend fun backupDatabase(context: Context, output: java.io.OutputStream) {
        current()
        DatabaseBackup.write(context, db, output)
    }
    suspend fun restoreDatabase(context: Context, input: java.io.InputStream) {
        val records = DatabaseBackup.read(context, input) // Validate everything before touching live data.
        write {
            dao.deleteAllExercises()
            dao.deleteAllWeights()
            records.exercises.forEach { row ->
                dao.putExercise(row.exercise)
                dao.putSets(row.sets)
            }
            records.weights.forEach { dao.putWeight(it) }
            val restored = readDatabase()
            val expectedWorkouts = records.exercises.groupBy { LocalDate.parse(it.exercise.date) }
                .mapValues { (_, rows) -> rows.map { it.model() } }
            val expectedWeights = records.weights.associate { LocalDate.parse(it.date) to it.pounds }
            check(restored == LegacyRecords(expectedWorkouts, expectedWeights)) { "Restore verification failed." }
        }
    }
    suspend fun exportCsv(options: ExportOptions, preferences: UserPreferences = UserPreferences()): String = withContext(Dispatchers.IO) {
        // Always query a consistent SQLite snapshot on demand; no export cache or legacy-file reads.
        options.validate()
        val records = db.withTransaction {
            val workouts = if (options.strength || options.cardio) {
                dao.exportExercises(options.from?.toString(), options.through?.toString(), options.exerciseName, options.strength, options.cardio)
                    .groupBy { LocalDate.parse(it.exercise.date) }.mapValues { (_, rows) -> rows.map { it.model() } }
            } else emptyMap()
            val weights = if (options.bodyWeight) dao.exportWeights(options.from?.toString(), options.through?.toString())
                .associate { LocalDate.parse(it.date) to it.pounds } else emptyMap()
            LegacyRecords(workouts, weights)
        }
        analysisCsv(records, options, preferences)
    }

    companion object {
        @Volatile private var instance: WorkoutRepository? = null
        fun get(context: Context): WorkoutRepository = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: WorkoutRepository(WorkoutDatabase.get(app)) {
                decodeLegacyRecords(
                    app.getSharedPreferences("workout_data", Context.MODE_PRIVATE).getString("workouts_json", null),
                    app.getSharedPreferences("body_weight", Context.MODE_PRIVATE).getString("daily", null)
                )
            }.also { instance = it }
        }
    }
}
