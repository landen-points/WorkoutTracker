package com.example.workouttracker

import android.content.Context
import androidx.room.*

@Entity(tableName = "exercises", indices = [Index("date"), Index("title")])
data class StoredExercise(
    @PrimaryKey val key: String,
    val id: String,
    val date: String,
    val title: String,
    val ordinal: Int,
    @Embedded(prefix = "cardio_") val cardio: Cardio? = null
)

@Entity(tableName = "exercise_sets", primaryKeys = ["exerciseKey", "position"],
    foreignKeys = [ForeignKey(entity = StoredExercise::class, parentColumns = ["key"],
        childColumns = ["exerciseKey"], onDelete = ForeignKey.CASCADE)])
data class StoredSet(val exerciseKey: String, val position: Int, val weight: Double, val reps: Int)

@Entity(tableName = "daily_weights")
data class StoredWeight(@PrimaryKey val date: String, val pounds: Double)

@Entity(tableName = "migration_status")
data class MigrationStatus(@PrimaryKey val name: String)

data class ExerciseWithSets(
    @Embedded val exercise: StoredExercise,
    @Relation(parentColumn = "key", entityColumn = "exerciseKey") val sets: List<StoredSet>
) {
    fun model() = Exercise(exercise.id, exercise.title,
        sets.sortedBy { it.position }.map { ExerciseSet(it.weight, it.reps) }, exercise.cardio)
}

@Dao
interface WorkoutDao {
    @Transaction @Query("SELECT * FROM exercises ORDER BY date, ordinal, `key`")
    suspend fun allExercises(): List<ExerciseWithSets>
    @Query("SELECT * FROM daily_weights ORDER BY date") suspend fun allWeights(): List<StoredWeight>
    @Transaction
    @Query("""SELECT * FROM exercises
        WHERE (:fromDate IS NULL OR date >= :fromDate) AND (:throughDate IS NULL OR date <= :throughDate)
        AND (:name IS NULL OR title = :name COLLATE NOCASE)
        AND ((:strength AND cardio_zone IS NULL) OR (:cardio AND cardio_zone IS NOT NULL))
        ORDER BY date, ordinal, `key`""")
    suspend fun exportExercises(fromDate: String?, throughDate: String?, name: String?, strength: Boolean, cardio: Boolean): List<ExerciseWithSets>
    @Query("""SELECT * FROM daily_weights
        WHERE (:fromDate IS NULL OR date >= :fromDate) AND (:throughDate IS NULL OR date <= :throughDate)
        ORDER BY date""")
    suspend fun exportWeights(fromDate: String?, throughDate: String?): List<StoredWeight>

    @Query("SELECT COUNT(*) FROM migration_status WHERE name = :name") suspend fun migrated(name: String): Int
    @Insert suspend fun markMigrated(status: MigrationStatus)
    @Upsert suspend fun putExercise(exercise: StoredExercise)
    @Insert suspend fun putSets(sets: List<StoredSet>)
    @Query("DELETE FROM exercise_sets WHERE exerciseKey = :key") suspend fun deleteSets(key: String)
    @Query("DELETE FROM exercises WHERE `key` = :key") suspend fun deleteExercise(key: String)
    @Query("SELECT ordinal FROM exercises WHERE `key` = :key") suspend fun ordinal(key: String): Int?
    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM exercises WHERE date = :date") suspend fun nextOrdinal(date: String): Int
    @Query("DELETE FROM exercises") suspend fun deleteAllExercises()
    @Query("DELETE FROM daily_weights") suspend fun deleteAllWeights()
    @Upsert suspend fun putWeight(weight: StoredWeight)
}

@Database(entities = [StoredExercise::class, StoredSet::class, StoredWeight::class, MigrationStatus::class],
    version = WorkoutDatabase.VERSION, exportSchema = true)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao
    companion object {
        const val VERSION = 1
        @Volatile private var instance: WorkoutDatabase? = null
        fun get(context: Context): WorkoutDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkoutDatabase::class.java, "workouts.db")
                .build().also { instance = it }
        }
    }
}
