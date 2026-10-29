package com.example.workouttracker

import java.time.LocalDate
import java.util.UUID

data class Cardio(val zone: Int, val averageHeartRate: Int, val minutes: Double) {
    fun description() = "Zone $zone | $averageHeartRate bpm | $minutes minutes"
}

data class ExerciseSet(val weight: Double, val reps: Int)

data class Exercise(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sets: List<ExerciseSet>,
    val cardio: Cardio? = null
)

data class HistoryEntry(val date: LocalDate, val exercise: Exercise)

fun formatWeight(weight: Double): String =
    if (weight % 1.0 == 0.0) weight.toInt().toString() else weight.toString()

