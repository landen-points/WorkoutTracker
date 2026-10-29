package com.example.workouttracker

import java.time.LocalDate

enum class GraphPeriod(val label: String) {
    WEEK("1 week"), TWO_WEEKS("2 weeks"), MONTH("1 month"), THREE_MONTHS("3 months"),
    SIX_MONTHS("6 months"), YEAR("1 year"), ALL_TIME("All time");

    fun start(today: LocalDate, firstLoggedDate: LocalDate? = null): LocalDate = when (this) {
        WEEK -> today.minusDays(6)
        TWO_WEEKS -> today.minusDays(13)
        MONTH -> today.minusMonths(1).plusDays(1)
        THREE_MONTHS -> today.minusMonths(3).plusDays(1)
        SIX_MONTHS -> today.minusMonths(6).plusDays(1)
        YEAR -> today.minusYears(1).plusDays(1)
        ALL_TIME -> minOf(firstLoggedDate ?: today.minusDays(6), today.minusDays(6))
    }
}

data class GraphSummary(
    val loggedDays: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val first: Double,
    val last: Double,
    val minimum: Double,
    val maximum: Double,
    val average: Double,
    val change: Double?,
    val percentChange: Double?
)

/** Statistics use recorded daily values only; missing days are not interpolated or counted as zero. */
fun summarizeGraph(values: Map<LocalDate, Double>): GraphSummary? {
    if (values.isEmpty()) return null
    val ordered = values.toSortedMap()
    val first = ordered.values.first()
    val last = ordered.values.last()
    val change = if (ordered.size >= 2) last - first else null
    return GraphSummary(ordered.size, ordered.firstKey(), ordered.lastKey(), first, last,
        ordered.values.min(), ordered.values.max(), ordered.values.average(), change,
        if (change != null && first != 0.0) change / kotlin.math.abs(first) * 100 else null)
}
