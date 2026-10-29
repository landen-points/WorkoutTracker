package com.example.workouttracker

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate

/** Only an isolated in-memory database is used by these confirmation tests. */
class SettingsConfirmationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: WorkoutDatabase
    private lateinit var repo: WorkoutRepository
    private val day = LocalDate.of(2026, 1, 2)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, WorkoutDatabase::class.java).build()
        repo = WorkoutRepository(db) {
            LegacyRecords(mapOf(day to listOf(Exercise("id", "Bench", listOf(ExerciseSet(100.0, 5))))), mapOf(day to 180.0))
        }
        repo.initialize()
    }
    @After fun cleanup() { db.close() }
    @Test fun clearRequiresTwoConfirmationsAndCheckedAcknowledgement() {
        compose.setContent { MaterialTheme { SettingsScreen(repo, restMinutes = "2", onRestMinutesChange = {}) } }
        compose.onNodeWithText("CLEAR ALL DATA").performScrollTo().performClick()
        assertEquals(1, repo.getExercises(day).size)
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Delete all data").assertIsNotEnabled()
        assertEquals(1, repo.getExercises(day).size)
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Delete all data").performClick()
        compose.waitUntil(5000) { repo.getExercises(day).isEmpty() && repo.allWeights().isEmpty() }
    }
    @Test fun cancellingFinalConfirmationPreservesData() {
        compose.setContent { MaterialTheme { SettingsScreen(repo, restMinutes = "2", onRestMinutesChange = {}) } }
        compose.onNodeWithText("CLEAR ALL DATA").performScrollTo().performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, repo.getExercises(day).size)
        assertEquals(180.0, repo.allWeights().getValue(day), 0.0)
    }
}
