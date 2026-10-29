package com.example.workouttracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.time.LocalDate

val LocalUserPreferences = staticCompositionLocalOf { UserPreferences() }

@Composable
fun WorkoutTrackerApp(repo: WorkoutRepository) {
    val context = LocalContext.current
    var preferences by remember { mutableStateOf<UserPreferences?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, retry) {
        error = false
        try { AppSettings.preferences(context).collect { preferences = it } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = true }
    }
    if (error) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Unable to read app settings.")
            Button(onClick = { retry++ }) { Text("Retry") }
        }
    } else if (preferences == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        CompositionLocalProvider(LocalUserPreferences provides preferences!!) { WorkoutTrackerContent(repo) }
    }
}

@Composable
fun SettingsScreen(
    repo: WorkoutRepository,
    modifier: Modifier = Modifier,
    restMinutes: String,
    onRestMinutesChange: (String) -> Unit
) {
    val context = LocalContext.current
    val preferences = LocalUserPreferences.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf("") }
    var clearStep by rememberSaveable { mutableIntStateOf(0) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    fun update(action: suspend () -> Unit) {
        scope.launch {
            try { action(); status = "Settings saved." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = "Unable to save settings. Please try again." }
        }
    }
    var working by remember { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw java.io.IOException("Unable to open the backup destination.")
                        output.use { repo.backupDatabase(context, it) }
                    }
                    status = "SQLite backup saved."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status = "Backup failed. Please try again; the selected file may be incomplete." }
                finally { working = false }
            }
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri.toString()
    }
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Units:", Modifier.width(106.dp), style = MaterialTheme.typography.titleMedium)
            BoxDropdown(preferences.units.symbol, WeightUnit.entries.map { it.symbol }, Modifier.weight(1f)) { symbol ->
                update { AppSettings.setUnits(context, WeightUnit.entries.first { it.symbol == symbol }) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Date format:", Modifier.width(106.dp), style = MaterialTheme.typography.titleMedium)
            BoxDropdown(preferences.dates.label, DateOrder.entries.map { it.label }, Modifier.weight(1f)) { label ->
                update { AppSettings.setDates(context, DateOrder.entries.first { it.label == label }) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Rest timer:", Modifier.width(106.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(restMinutes, onRestMinutesChange, modifier = Modifier.weight(1f),
                placeholder = { Text("Minutes") }, suffix = { Text("min") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (runCatching { restSecondsFromMinutes(restMinutes) }.isFailure) {
            Text("Enter a duration from 1 second to 1440 minutes.")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = !working && !clearing, modifier = Modifier.weight(1f), onClick = {
                try { backupPicker.launch("workout_backup_${preferences.dateText(LocalDate.now()).replace('/', '-')}.sqlite") }
                catch (failure: Exception) { status = "Unable to open the backup file picker." }
            }) { Text("Backup SQLite") }
            OutlinedButton(enabled = !working && !clearing, modifier = Modifier.weight(1f), onClick = {
                try { restorePicker.launch(arrayOf("*/*")) }
                catch (failure: Exception) { status = "Unable to open the restore file picker." }
            }) { Text("Restore SQLite") }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
        Button(enabled = !working, onClick = { acknowledged = false; clearStep = 1 },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("CLEAR ALL DATA") }
        TextButton(onClick = { about = true }) { Text("About") }
    }
    if (restoreUri != null) AlertDialog(
        onDismissRequest = { if (!working) restoreUri = null },
        title = { Text("Restore SQLite data?") },
        text = { Text("This replaces all current workouts, sets, cardio and daily weights with the selected backup. App settings are kept. The backup will be checked before any records are replaced.") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            val selected = restoreUri ?: return@TextButton
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(Uri.parse(selected))
                            ?: throw java.io.IOException("Unable to open the selected backup.")
                        input.use { repo.restoreDatabase(context, it) }
                    }
                    restoreUri = null
                    status = "SQLite backup restored."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { restoreUri = null; status = "Restore failed: ${failure.message ?: "Unable to read backup."}" }
                finally { working = false }
            }
        }) { Text(if (working) "Restoring..." else "Restore") } },
        dismissButton = { TextButton(enabled = !working, onClick = { restoreUri = null }) { Text("Cancel") } })
    if (clearStep == 1) AlertDialog(
        onDismissRequest = { clearStep = 0 }, title = { Text("Clear all data? (1 of 2)") },
        text = { Text("This permanently deletes all workouts, sets, cardio and daily scale records. Export any data you want to keep before continuing.") },
        confirmButton = { TextButton(onClick = { acknowledged = false; clearStep = 2 }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { clearStep = 0 }) { Text("Cancel") } })
    if (clearStep == 2) AlertDialog(
        onDismissRequest = { if (!clearing) { clearStep = 0; acknowledged = false } },
        title = { Text("Final confirmation (2 of 2)") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(acknowledged, { acknowledged = it }, enabled = !clearing)
            Text("I understand this permanently deletes all my workout and daily scale data and cannot be undone.")
        } },
        confirmButton = { TextButton(enabled = acknowledged && !clearing, onClick = {
            clearing = true
            scope.launch {
                try {
                    repo.clearAllData()
                    clearStep = 0; acknowledged = false
                    status = "All workout and daily scale data cleared."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { status = "Unable to clear data. Please try again." }
                finally { clearing = false }
            }
        }) { Text(if (clearing) "Clearing..." else "Delete all data") } },
        dismissButton = { TextButton(enabled = !clearing, onClick = { clearStep = 0; acknowledged = false }) { Text("Cancel") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About") },
        text = { Column { Text("Workout Tracker"); Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") } },
        confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
}
