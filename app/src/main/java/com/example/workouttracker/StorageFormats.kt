package com.example.workouttracker

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class LegacyRecords(val workouts: Map<LocalDate, List<Exercise>>, val weights: Map<LocalDate, Double>)

/** Strict decoding: an unreadable legacy file aborts migration; no empty fallback is written. */
fun decodeLegacyRecords(workoutsJson: String?, weightsJson: String?): LegacyRecords {
    val workouts = linkedMapOf<LocalDate, List<Exercise>>()
    val root = JSONObject(workoutsJson ?: "{}")
    for (dateKey in root.keys()) {
        val date = LocalDate.parse(dateKey)
        val array = root.getJSONArray(dateKey)
        val exercises = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val sets = item.getJSONArray("sets")
            val cardio = item.optJSONObject("cardio")?.let {
                Cardio(it.getInt("zone"), it.getInt("averageHeartRate"), it.getDouble("minutes"))
            }
            Exercise(item.getString("id"), item.getString("title"), (0 until sets.length()).map { j ->
                val set = sets.getJSONObject(j)
                ExerciseSet(set.getDouble("weight"), set.getInt("reps"))
            }, cardio)
        }
        require(exercises.map { it.id }.distinct().size == exercises.size) { "Duplicate exercise IDs on $dateKey." }
        require(!workouts.containsKey(date)) { "Duplicate legacy date: $dateKey." }
        workouts[date] = exercises
    }
    val weights = linkedMapOf<LocalDate, Double>()
    val weightsRoot = JSONObject(weightsJson ?: "{}")
    for (dateKey in weightsRoot.keys()) {
        val date = LocalDate.parse(dateKey)
        val weight = weightsRoot.getDouble(dateKey)
        require(weight.isFinite() && weight > 0) { "Invalid legacy weight on $dateKey." }
        weights[date] = weight
    }
    return LegacyRecords(workouts, weights)
}

data class ExportOptions(
    val strength: Boolean = true,
    val cardio: Boolean = true,
    val bodyWeight: Boolean = true,
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val exerciseName: String? = null
) {
    fun validate() {
        require(strength || cardio || bodyWeight) { "Select at least one data type." }
        require(from == null || through == null || !from.isAfter(through)) { "Start date must be on or before end date." }
    }
    fun includes(date: LocalDate) = (from == null || !date.isBefore(from)) && (through == null || !date.isAfter(through))
}

/** One rectangular table, explicit units, ISO dates, UTF-8 BOM and CRLF for Excel. */
fun analysisCsv(records: LegacyRecords, options: ExportOptions, preferences: UserPreferences = UserPreferences()): String {
    options.validate()
    val unit = preferences.units
    val output = StringBuilder("\uFEFFrecord_type,date,exercise_id,exercise,set_number,weight_${unit.symbol},reps,volume_${unit.symbol},cardio_zone,average_heart_rate_bpm,duration_minutes,body_weight_${unit.symbol}\r\n")
    fun row(vararg fields: Any?) {
        output.append(fields.joinToString(",") { field ->
            var text = field?.toString() ?: ""
            // Exercise names are untrusted spreadsheet text; keep them from becoming formulas.
            if (field is String && text.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) text = "'" + text
            if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
        }).append("\r\n")
    }
    val dates = (records.workouts.keys + records.weights.keys).sorted()
    for (date in dates) {
        if (!options.includes(date)) continue
        for (exercise in records.workouts[date].orEmpty()) {
            if (options.exerciseName != null && !exercise.title.equals(options.exerciseName, ignoreCase = true)) continue
            if (options.strength && exercise.cardio == null) exercise.sets.forEachIndexed { i, set ->
                row("strength", preferences.dateText(date), exercise.id, exercise.title, i + 1, unit.fromPounds(set.weight), set.reps, unit.fromPounds(set.weight) * set.reps, null, null, null, null)
            }
            if (options.cardio) exercise.cardio?.let {
                row("cardio", preferences.dateText(date), exercise.id, exercise.title, null, null, null, null, it.zone, it.averageHeartRate, it.minutes, null)
            }
        }
        if (options.bodyWeight) records.weights[date]?.let {
            row("body_weight", preferences.dateText(date), null, null, null, null, null, null, null, null, null, unit.fromPounds(it))
        }
    }
    return output.toString()
}
