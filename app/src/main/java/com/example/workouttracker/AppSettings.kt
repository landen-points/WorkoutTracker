package com.example.workouttracker

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

private val Context.smallSettings by preferencesDataStore(name = "settings")

object AppSettings {
    private val unitsKey = stringPreferencesKey("weight_units")
    private val datesKey = stringPreferencesKey("date_order")
    private val durationKey = intPreferencesKey("rest_seconds")
    fun preferences(context: Context): Flow<UserPreferences> = context.applicationContext.smallSettings.data.map {
        UserPreferences(
            it[unitsKey]?.let { name -> WeightUnit.valueOf(name) } ?: WeightUnit.LB,
            it[datesKey]?.let { name -> DateOrder.valueOf(name) } ?: DateOrder.MONTH_FIRST,
            it[durationKey] ?: 120)
    }
    suspend fun setUnits(context: Context, units: WeightUnit) {
        context.applicationContext.smallSettings.edit { it[unitsKey] = units.name }
    }
    suspend fun setDates(context: Context, dates: DateOrder) {
        context.applicationContext.smallSettings.edit { it[datesKey] = dates.name }
    }
    suspend fun setRestSeconds(context: Context, seconds: Int) {
        require(seconds in 1..86400)
        context.applicationContext.smallSettings.edit { it[durationKey] = seconds }
    }
    suspend fun restSeconds(context: Context) = preferences(context).first().restSeconds
    private val deadlineKey = longPreferencesKey("rest_deadline")
    private val migratedKey = booleanPreferencesKey("legacy_timer_migrated")
    private suspend fun store(context: Context): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val store = context.applicationContext.smallSettings
        // Copy only after a successful read, retaining the old preferences as a fallback.
        if (store.data.first()[migratedKey] != true) store.edit { preferences ->
            if (preferences[migratedKey] != true) {
                preferences[deadlineKey] = context.getSharedPreferences("rest_timer", Context.MODE_PRIVATE).getLong("deadline", 0L)
                preferences[migratedKey] = true
            }
        }
        return store
    }
    suspend fun deadline(context: Context) = store(context).data.first()[deadlineKey] ?: 0L
    suspend fun setDeadline(context: Context, value: Long) {
        store(context).edit { it[deadlineKey] = value }
    }
}
