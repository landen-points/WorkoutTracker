package com.example.workouttracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GraphDataTest {
    private val today = LocalDate.of(2026, 10, 29)
    @Test fun weekAndTwoWeekRangesIncludeExactlySevenAndFourteenDays() {
        assertEquals(today.minusDays(6), GraphPeriod.WEEK.start(today))
        assertEquals(today.minusDays(13), GraphPeriod.TWO_WEEKS.start(today))
    }
    @Test fun longerRangesUseCalendarMonthsAndYears() {
        assertEquals(LocalDate.of(2026, 9, 30), GraphPeriod.MONTH.start(today))
        assertEquals(LocalDate.of(2026, 7, 30), GraphPeriod.THREE_MONTHS.start(today))
        assertEquals(LocalDate.of(2026, 4, 30), GraphPeriod.SIX_MONTHS.start(today))
        assertEquals(LocalDate.of(2025, 10, 30), GraphPeriod.YEAR.start(today))
        assertEquals(LocalDate.of(2024, 3, 1), GraphPeriod.MONTH.start(LocalDate.of(2024, 3, 31)))
    }
    @Test fun allTimeUsesEarliestRecordWithAtLeastAWeekVisible() {
        val old = today.minusYears(4)
        assertEquals(old, GraphPeriod.ALL_TIME.start(today, old))
        assertEquals(today.minusDays(6), GraphPeriod.ALL_TIME.start(today))
        assertEquals(today.minusDays(6), GraphPeriod.ALL_TIME.start(today, today))
        assertEquals(today.minusDays(6), GraphPeriod.ALL_TIME.start(today, today.plusDays(1)))
    }
    @Test fun changeUsesChronologicalEndpointsRatherThanMinimumAndMaximum() {
        val first = today.minusDays(6)
        val middle = today.minusDays(4)
        val summary = summarizeGraph(linkedMapOf(today to 198.4, middle to 185.0, first to 189.2))!!
        assertEquals(first, summary.firstDate)
        assertEquals(today, summary.lastDate)
        assertEquals(189.2, summary.first, 0.0)
        assertEquals(198.4, summary.last, 0.0)
        assertEquals(185.0, summary.minimum, 0.0)
        assertEquals(198.4, summary.maximum, 0.0)
        assertEquals(9.2, summary.change!!, 0.000001)
        assertEquals(9.2 / 189.2 * 100, summary.percentChange!!, 0.000001)
        assertEquals((198.4 + 185.0 + 189.2) / 3, summary.average, 0.000001)
        assertEquals(3, summary.loggedDays)
    }
    @Test fun fallingAndUnchangedValuesHaveSignedChanges() {
        val falling = summarizeGraph(mapOf(today.minusDays(6) to 200.0, today to 190.0))!!
        assertEquals(-10.0, falling.change!!, 0.0)
        assertEquals(-5.0, falling.percentChange!!, 0.0)
        val same = summarizeGraph(mapOf(today.minusDays(6) to 200.0, today to 200.0))!!
        assertEquals(0.0, same.change!!, 0.0)
        assertEquals(0.0, same.percentChange!!, 0.0)
    }
    @Test fun missingDaysDoNotDiluteTheAverageAndKgChangeConvertsCorrectly() {
        val summary = summarizeGraph(mapOf(today.minusDays(13) to 100.0, today to 110.0))!!
        assertEquals(105.0, summary.average, 0.0)
        assertEquals(2, summary.loggedDays)
        assertEquals(4.5359237, WeightUnit.KG.fromPounds(summary.change!!), 0.0000001)
    }
    @Test fun emptySingleAndZeroBaselineDoNotInventChanges() {
        assertNull(summarizeGraph(emptyMap()))
        val single = summarizeGraph(mapOf(today to 195.0))!!
        assertEquals(195.0, single.average, 0.0)
        assertNull(single.change)
        assertNull(single.percentChange)
        val zero = summarizeGraph(mapOf(today.minusDays(1) to 0.0, today to 10.0))!!
        assertEquals(10.0, zero.change!!, 0.0)
        assertNull(zero.percentChange)
    }
}
