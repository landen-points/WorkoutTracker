package com.example.workouttracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import org.json.JSONObject
import org.json.JSONArray

class WorkoutRepositoryTest {
    private val day = LocalDate.of(2026, 1, 2)
    private val legacy = """{"2026-01-02":[{"id":"original-id","title":"Bench, press","sets":[{"weight":135.5,"reps":8},{"weight":135.5,"reps":8}]},{"id":"cardio-id","title":"Cycling","sets":[],"cardio":{"zone":2,"averageHeartRate":130,"minutes":25.5}}]}"""
    private fun records() = decodeLegacyRecords(legacy, """{"2026-01-02":180.5}""")

    @Test fun legacyDecoderPreservesIdsOrderRepeatedSetsCardioAndWeight() {
        val data = records()
        val entries = data.workouts.getValue(day)
        assertEquals("original-id", entries[0].id)
        assertEquals("Bench, press", entries[0].title)
        assertEquals(listOf(ExerciseSet(135.5, 8), ExerciseSet(135.5, 8)), entries[0].sets)
        assertEquals(Cardio(2, 130, 25.5), entries[1].cardio)
        assertEquals(180.5, data.weights.getValue(day), 0.0)
    }
    @Test fun absentLegacyFilesAreAnEmptyDatabase() {
        assertEquals(LegacyRecords(emptyMap(), emptyMap()), decodeLegacyRecords(null, null))
    }
    @Test fun corruptEitherLegacyFileAbortsDecoding() {
        assertTrue(runCatching { decodeLegacyRecords("bad", "{}") }.isFailure)
        assertTrue(runCatching { decodeLegacyRecords(legacy, "bad") }.isFailure)
        assertTrue(runCatching { decodeLegacyRecords(legacy, """{"2026-01-02":-5}""") }.isFailure)
    }
    @Test fun duplicateIdsAbortInsteadOfSilentlyOverwriting() {
        val root = JSONObject(legacy)
        val list = root.getJSONArray(day.toString())
        list.put(list.getJSONObject(0))
        assertTrue(runCatching { decodeLegacyRecords(root.toString(), null) }.isFailure)
    }
    @Test fun decodesLargeLegacyHistory() {
        val root = JSONObject()
        repeat(5000) { i -> root.put(day.plusDays(i.toLong()).toString(), JSONArray("""[{"id":"id-$i","title":"Bench","sets":[{"weight":135,"reps":8}]}]""")) }
        assertEquals(5000, decodeLegacyRecords(root.toString(), null).workouts.size)
    }
    @Test fun combinedExportHasOneRectangularTableWithUnitsAndAllData() {
        val csv = analysisCsv(records(), ExportOptions())
        assertTrue(csv.startsWith("\uFEFFrecord_type,date,exercise_id,exercise,set_number,weight_lb,reps,volume_lb,"))
        assertTrue(csv.contains("strength,01/02/2026,original-id,\"Bench, press\",1,135.5,8,1084.0,,,,\r\n"))
        assertTrue(csv.contains("cardio,01/02/2026,cardio-id,Cycling,,,,,2,130,25.5,\r\n"))
        assertTrue(csv.contains("body_weight,01/02/2026,,,,,,,,,,180.5\r\n"))
        assertEquals(5, csv.split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun exportFiltersDatesCategoriesAndExerciseWithoutDroppingScaleSelection() {
        val onlyCardio = analysisCsv(records(), ExportOptions(strength = false, bodyWeight = false))
        assertFalse(onlyCardio.contains("original-id"))
        assertFalse(onlyCardio.contains("180.5"))
        assertTrue(onlyCardio.contains("cardio-id"))
        val selected = analysisCsv(records(), ExportOptions(exerciseName = "BENCH, PRESS"))
        assertTrue(selected.contains("original-id"))
        assertFalse(selected.contains("cardio-id"))
        assertTrue(selected.contains("180.5"))
        val excluded = analysisCsv(records(), ExportOptions(from = day.plusDays(1)))
        assertEquals(1, excluded.split("\r\n").filter { it.isNotEmpty() }.size)
    }
    @Test fun spreadsheetTextIsQuotedAndFormulaSafe() {
        val exercise = Exercise(id = "x", title = "=HYPERLINK(\"bad\")\nrow", sets = listOf(ExerciseSet(1.0, 2)))
        val csv = analysisCsv(LegacyRecords(mapOf(day to listOf(exercise)), emptyMap()), ExportOptions())
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\nrow\""))
    }
    @Test fun invalidExportSelectionsAreRejected() {
        assertTrue(runCatching { analysisCsv(records(), ExportOptions(false, false, false)) }.isFailure)
        assertTrue(runCatching { analysisCsv(records(), ExportOptions(from = day.plusDays(1), through = day)) }.isFailure)
    }
}
