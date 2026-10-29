package com.example.workouttracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UserPreferencesTest {
    @Test fun defaultsUsePoundsMonthFirstAndTwoMinutes() {
        val preferences = UserPreferences()
        assertEquals(WeightUnit.LB, preferences.units)
        assertEquals(120, preferences.restSeconds)
        assertEquals("01/02/2026", preferences.dateText(LocalDate.of(2026, 1, 2)))
    }
    @Test fun dateFormatsRoundTripAndRejectImpossibleDates() {
        val date = LocalDate.of(2024, 2, 29)
        assertEquals("02/29/2024", DateOrder.MONTH_FIRST.format(date))
        assertEquals("29/02/2024", DateOrder.DAY_FIRST.format(date))
        for (format in DateOrder.entries) {
            assertEquals(date, format.parse(format.format(date)))
            assertTrue(runCatching { format.parse("2024-02-29") }.isFailure)
        }
        assertTrue(runCatching { DateOrder.MONTH_FIRST.parse("02/29/2025") }.isFailure)
        assertTrue(runCatching { DateOrder.DAY_FIRST.parse("31/04/2026") }.isFailure)
        assertEquals(LocalDate.of(2026, 4, 3), DateOrder.DAY_FIRST.parse("03/04/2026"))
        assertEquals(LocalDate.of(2026, 3, 4), DateOrder.MONTH_FIRST.parse("03/04/2026"))
    }
    @Test fun unitChangesConvertValuesAndInputsWithoutRewritingStoredPounds() {
        val pounds = 225.0
        val kilos = WeightUnit.KG.fromPounds(pounds)
        assertEquals(102.05828325, kilos, 0.00000001)
        assertEquals(pounds, WeightUnit.KG.toPounds(kilos), 0.00000001)
        assertEquals("102.06", UserPreferences(units = WeightUnit.KG).weightText(pounds))
        assertEquals("225", UserPreferences().weightText(pounds))
        assertEquals(220.46226218, WeightUnit.KG.toPounds(100.0), 0.000001)
        assertEquals(135.5, WeightUnit.LB.toPounds(135.5), 0.0)
    }
    @Test fun exportsConvertAllWeightFieldsAndUseSelectedDateOrder() {
        val date = LocalDate.of(2026, 3, 4)
        val records = LegacyRecords(mapOf(date to listOf(Exercise("id", "Bench", listOf(ExerciseSet(100.0, 2))))), mapOf(date to 200.0))
        val selected = UserPreferences(WeightUnit.KG, DateOrder.DAY_FIRST)
        val csv = analysisCsv(records, ExportOptions(), selected)
        assertTrue(csv.contains("weight_kg,reps,volume_kg"))
        assertTrue(csv.contains("body_weight_kg"))
        assertTrue(csv.contains("strength,04/03/2026,id,Bench,1,45.359237,2,90.718474"))
        assertTrue(csv.contains("body_weight,04/03/2026,,,,,,,,,,90.718474"))
        assertFalse(csv.contains("2026-03-04"))
        assertEquals(100.0, records.workouts.getValue(date).single().sets.single().weight, 0.0)
        val defaultCsv = analysisCsv(records, ExportOptions())
        assertTrue(defaultCsv.contains("strength,03/04/2026,id,Bench,1,100.0,2,200.0"))
    }
    @Test fun restDurationSupportsMinutesAndFractionsAndRejectsInvalidValues() {
        assertEquals(120, restSecondsFromMinutes("2"))
        assertEquals(30, restSecondsFromMinutes("0.5"))
        assertEquals(195, restSecondsFromMinutes("3.25"))
        for (invalid in listOf("", "0", "-1", "NaN", "Infinity", "0.001", "1441")) {
            assertTrue(invalid, runCatching { restSecondsFromMinutes(invalid) }.isFailure)
        }
    }
}
