package com.example.workouttracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class WeightUnit(val symbol: String) {
    LB("lb"), KG("kg");
    fun fromPounds(pounds: Double) = if (this == KG) pounds * 0.45359237 else pounds
    fun toPounds(value: Double) = if (this == KG) value / 0.45359237 else value
}

enum class DateOrder(val label: String, private val pattern: String) {
    MONTH_FIRST("MM/DD/YYYY", "MM/dd/uuuu"), DAY_FIRST("DD/MM/YYYY", "dd/MM/uuuu");
    private fun formatter() = DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT)
    fun format(date: LocalDate): String = date.format(formatter())
    fun parse(text: String): LocalDate = LocalDate.parse(text.trim(), formatter())
}

data class UserPreferences(
    val units: WeightUnit = WeightUnit.LB,
    val dates: DateOrder = DateOrder.MONTH_FIRST,
    val restSeconds: Int = 120
) {
    fun weightText(pounds: Double): String = BigDecimal.valueOf(units.fromPounds(pounds))
        .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    fun dateText(date: LocalDate) = dates.format(date)
}

fun restSecondsFromMinutes(text: String): Int {
    val minutes = text.trim().toDoubleOrNull()
    require(minutes != null && minutes.isFinite() && minutes * 60 >= 1 && minutes <= 1440) {
        "Enter a duration from 1 second to 1440 minutes."
    }
    return (minutes * 60).roundToInt()
}
