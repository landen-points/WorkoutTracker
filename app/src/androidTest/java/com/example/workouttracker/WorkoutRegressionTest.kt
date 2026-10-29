package com.example.workouttracker

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

/** Uses an isolated in-memory database; never opens the user's workouts.db or preferences. */
class WorkoutRegressionTest {
    private lateinit var db: WorkoutDatabase
    private val day = LocalDate.of(2026, 1, 2)
    private val strength = Exercise("old-id", "Bench", listOf(ExerciseSet(135.5, 8), ExerciseSet(135.5, 8)))
    private val cardio = Exercise("cardio-id", "Bike", emptyList(), Cardio(2, 130, 20.0))
    private fun legacy() = LegacyRecords(mapOf(day to listOf(strength, cardio)), mapOf(day to 180.5))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WorkoutDatabase::class.java).build()
    }
    @After fun cleanup() { db.close() }

    @Test fun migrationPreservesEveryFieldAndDoesNotRepeat() = runBlocking {
        var reads = 0
        val repo = WorkoutRepository(db) { reads++; legacy() }
        repo.initialize()
        assertEquals(listOf(strength, cardio), repo.getExercises(day))
        assertEquals(180.5, repo.allWeights().getValue(day), 0.0)
        repo.deleteExercise(day, strength.id)
        val reopened = WorkoutRepository(db) { error("Legacy data must not be reimported") }
        reopened.initialize()
        assertEquals(listOf(cardio), reopened.getExercises(day))
        assertEquals(1, reads)
    }
    @Test fun unreadableLegacyDataLeavesDatabaseUnmigratedAndCanRetry() = runBlocking {
        var broken = true
        val repo = WorkoutRepository(db) {
            if (broken) decodeLegacyRecords("{}", "bad") else legacy()
        }
        assertTrue(runCatching { repo.initialize() }.isFailure)
        withContext(Dispatchers.IO) {
            assertTrue(db.workouts().allExercises().isEmpty())
            assertEquals(0, db.workouts().migrated("legacy_preferences_v1"))
        }
        broken = false
        repo.initialize()
        assertEquals(listOf(strength, cardio), repo.getExercises(day))
    }
    @Test fun failedVerificationRollsBackInsertedDataAndMarker() = runBlocking {
        withContext(Dispatchers.IO) {
            db.workouts().putWeight(StoredWeight("2020-01-01", 150.0))
        }
        val repo = WorkoutRepository(db) { legacy() }
        assertTrue(runCatching { repo.initialize() }.isFailure)
        withContext(Dispatchers.IO) {
            assertTrue(db.workouts().allExercises().isEmpty())
            assertEquals(listOf(StoredWeight("2020-01-01", 150.0)), db.workouts().allWeights())
            assertEquals(0, db.workouts().migrated("legacy_preferences_v1"))
        }
    }
    @Test fun databaseChangesAndExportRemainConsistent() = runBlocking {
        val repo = WorkoutRepository(db) { legacy() }
        repo.initialize()
        repo.updateExercise(day, strength.copy(sets = listOf(ExerciseSet(140.0, 5))))
        repo.addExercise(day, cardio.copy(cardio = Cardio(3, 140, 25.0)))
        repo.saveWeight(day, 179.0)
        withContext(Dispatchers.IO) {
            // Bypass the UI cache to prove export queries the database on demand.
            db.workouts().putWeight(StoredWeight(day.toString(), 178.0))
        }
        val csv = repo.exportCsv(ExportOptions())
        assertTrue(csv.contains("140.0,5,700.0"))
        assertTrue(csv.contains(",3,140,25.0,"))
        assertTrue(csv.contains(",178.0\r\n"))
        repo.deleteExercise(day, strength.id)
        withContext(Dispatchers.IO) {
            assertEquals(1, db.workouts().allExercises().size)
            assertTrue(db.workouts().allExercises().single().sets.isEmpty())
        }
    }

    @Test fun clearRemovesEveryUserRecordAndCannotResurrectLegacyHistory() = runBlocking {
        val repo = WorkoutRepository(db) { legacy() }
        repo.initialize()
        repo.clearAllData()
        assertTrue(repo.getExercises(day).isEmpty())
        assertTrue(repo.allWeights().isEmpty())
        withContext(Dispatchers.IO) {
            assertTrue(db.workouts().allExercises().isEmpty())
            assertTrue(db.workouts().allWeights().isEmpty())
            assertEquals(1, db.workouts().migrated("legacy_preferences_v1"))
        }
        val reopened = WorkoutRepository(db) { error("Cleared data must not be restored") }
        reopened.initialize()
        assertTrue(reopened.getExercises(day).isEmpty())
        assertTrue(reopened.allWeights().isEmpty())
    }

    @Test fun sqliteBackupRoundTripRestoresAllFieldsIncludingDataNotInUiCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WorkoutRepository(db) { legacy() }
        repo.initialize()
        withContext(Dispatchers.IO) { db.workouts().putWeight(StoredWeight(day.toString(), 175.25)) }
        val bytes = java.io.ByteArrayOutputStream()
        repo.backupDatabase(context, bytes)
        val backup = bytes.toByteArray()
        assertEquals("SQLite format 3\u0000", String(backup.copyOfRange(0, 16), Charsets.US_ASCII))
        repo.clearAllData()
        repo.restoreDatabase(context, java.io.ByteArrayInputStream(backup))
        assertEquals(listOf(strength, cardio), repo.getExercises(day))
        assertEquals(175.25, repo.allWeights().getValue(day), 0.0)
        val reopened = WorkoutRepository(db) { error("Restore must not reimport legacy records") }
        reopened.initialize()
        assertEquals(listOf(strength, cardio), reopened.getExercises(day))
    }

    @Test fun invalidOrUnsupportedBackupLeavesCurrentRecordsIntact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WorkoutRepository(db) { legacy() }
        repo.initialize()
        assertTrue(runCatching { repo.restoreDatabase(context, java.io.ByteArrayInputStream("not sqlite".toByteArray())) }.isFailure)
        assertEquals(listOf(strength, cardio), repo.getExercises(day))
        val output = java.io.ByteArrayOutputStream()
        repo.backupDatabase(context, output)
        val unsupported = output.toByteArray()
        // SQLite stores user_version as a big-endian integer at offsets 60..63.
        unsupported[60] = 0; unsupported[61] = 0; unsupported[62] = 0; unsupported[63] = (WorkoutDatabase.VERSION + 1).toByte()
        assertTrue(runCatching { repo.restoreDatabase(context, java.io.ByteArrayInputStream(unsupported)) }.isFailure)
        val reopened = WorkoutRepository(db) { error("Current database must be retained") }
        reopened.initialize()
        assertEquals(listOf(strength, cardio), reopened.getExercises(day))
        assertEquals(180.5, reopened.allWeights().getValue(day), 0.0)
    }

    @Test fun emptySqliteBackupRestoresAnEmptyDatabaseWithoutLegacyResurrection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WorkoutRepository(db) { LegacyRecords(emptyMap(), emptyMap()) }
        repo.initialize()
        val output = java.io.ByteArrayOutputStream()
        repo.backupDatabase(context, output)
        repo.addExercise(day, strength)
        repo.saveWeight(day, 180.0)
        repo.restoreDatabase(context, java.io.ByteArrayInputStream(output.toByteArray()))
        assertTrue(repo.getExercises(day).isEmpty())
        assertTrue(repo.allWeights().isEmpty())
    }
}
