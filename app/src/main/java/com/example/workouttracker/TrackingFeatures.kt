package com.example.workouttracker

import android.app.*
import android.content.*
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

@Composable
fun ChoiceMenu(label: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { expanded = false; onSelect(choice) }) }
        }
    }
}

@Composable
fun DailyWeightPanel(date: LocalDate) {
    val preferences = LocalUserPreferences.current
    val context = LocalContext.current
    val repo = remember { WorkoutRepository.get(context) }
    val scope = rememberCoroutineScope()
    var opened by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var input by rememberSaveable(date.toString()) { mutableStateOf("") }
    var error by rememberSaveable(date.toString()) { mutableStateOf("") }
    IconButton(onClick = {
        error = ""
        runCatching { repo.allWeights()[date] }.fold(
            { input = it?.let { pounds -> preferences.weightText(pounds) } ?: "" },
            { error = "Unable to read saved weight. Please close and try again." })
        opened = true
    }) { Icon(Icons.Default.MonitorWeight, contentDescription = "Weight") }
    if (opened) {
        AlertDialog(onDismissRequest = { opened = false },
            title = { Text("Daily Scale") },
            text = { Column {
                OutlinedTextField(input, { input = it }, label = { Text("Weight (${preferences.units.symbol})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (error.isNotEmpty()) Text(error)
            } },
            confirmButton = { TextButton(onClick = {
                val pounds = preferences.units.toPounds(input.toDouble())
                scope.launch {
                    try { repo.saveWeight(date, pounds); opened = false }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = "Unable to save weight. Please try again." }
                }
            }, enabled = !error.startsWith("Unable to read") && input.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true) { Text("Save") } },
            dismissButton = { TextButton(onClick = { opened = false }) { Text("Cancel") } })
    }
}

@Composable
fun GraphingScreen(repo: WorkoutRepository, modifier: Modifier = Modifier) {
    val preferences = LocalUserPreferences.current
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(GraphPeriod.MONTH) }
    val today = LocalDate.now()
    val names = repo.allExerciseNames().filter { name -> repo.history(name).any { it.exercise.cardio == null } }
    fun exerciseLabel(name: String) = if (name == "Body weight") "Body weight (exercise)" else name
    val choices = listOf("Body weight") + names.map(::exerciseLabel)
    val allValues = if (selectedName == null) repo.allWeights() else
        repo.history(selectedName!!).filter { it.exercise.cardio == null }.groupBy { it.date }
            .mapValues { (_, entries) -> entries.flatMap { it.exercise.sets }.maxOfOrNull { it.weight } ?: 0.0 }
    val start = period.start(today, allValues.keys.filter { !it.isAfter(today) }.minOrNull())
    val values = allValues.filterKeys { !it.isBefore(start) && !it.isAfter(today) }.toSortedMap()
    val summary = summarizeGraph(values)
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxDropdown(selectedName?.let(::exerciseLabel) ?: "Body weight", choices, Modifier.weight(0.58f)) { label ->
                selectedName = names.firstOrNull { exerciseLabel(it) == label }
            }
            BoxDropdown(period.label, GraphPeriod.entries.map { it.label }, Modifier.weight(0.42f)) { label ->
                period = GraphPeriod.entries.first { it.label == label }
            }
        }
        Text(if (selectedName == null) "Body weight (${preferences.units.symbol})" else "Heaviest set per day (${preferences.units.symbol})")
        if (summary == null) {
            Text("No entries in this time range.")
        } else {
            val unit = preferences.units.symbol
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Range", style = MaterialTheme.typography.labelMedium)
                    Text("${preferences.weightText(summary.minimum)} - ${preferences.weightText(summary.maximum)} $unit")
                    Text("Average: ${preferences.weightText(summary.average)} $unit")
                    Text("Logged days: ${summary.loggedDays}")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total change", style = MaterialTheme.typography.labelMedium)
                    if (summary.change == null) Text("Need 2 logged days") else {
                        val prefix = if (summary.change > 0) "+" else ""
                        Text("$prefix${preferences.weightText(summary.change)} $unit")
                        summary.percentChange?.let { Text(String.format(java.util.Locale.US, "%+.1f%%", it)) }
                            ?: Text("Percentage unavailable from zero")
                    }
                    Text("First: ${preferences.weightText(summary.first)} $unit")
                    Text("Last: ${preferences.weightText(summary.last)} $unit")
                }
            }
            val color = MaterialTheme.colorScheme.primary
            val points = values.entries.toList()
            Canvas(Modifier.fillMaxWidth().height(220.dp).padding(12.dp)) {
                val coordinates = points.map { point ->
                    Offset(((point.key.toEpochDay() - start.toEpochDay()).toFloat() / (today.toEpochDay() - start.toEpochDay()).coerceAtLeast(1)) * size.width,
                        if (summary.maximum == summary.minimum) size.height / 2 else
                            size.height * (1 - ((point.value - summary.minimum) / (summary.maximum - summary.minimum)).toFloat()))
                }
                coordinates.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 4f) }
                coordinates.forEach { drawCircle(color, 6f, it) }
            }
            Text("${preferences.dateText(start)} to ${preferences.dateText(today)}")
            if (summary.change != null) Text(
                "Change compares logged values on ${preferences.dateText(summary.firstDate)} and ${preferences.dateText(summary.lastDate)}.",
                style = MaterialTheme.typography.bodySmall)
            points.forEach { Text("${preferences.dateText(it.key)}: ${preferences.weightText(it.value)} $unit") }
        }
    }
}

object RestTimer {
    const val CHANNEL = "rest_timer"
    const val ID = 201
    suspend fun deadline(context: Context) = AppSettings.deadline(context)
    private fun alarm(context: Context) = PendingIntent.getBroadcast(context, ID, Intent(context, RestTimerReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun start(context: Context) {
        val durationSeconds = AppSettings.restSeconds(context)
        val end = System.currentTimeMillis() + durationSeconds * 1000L
        AppSettings.setDeadline(context, end)
        show(context, end, false)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, alarm(context))
    }
    suspend fun reset(context: Context) {
        AppSettings.setDeadline(context, 0L)
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context))
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }
    fun show(context: Context, end: Long, finished: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val timerView = RemoteViews(context.packageName, R.layout.notification_countdown)
        val base = SystemClock.elapsedRealtime() + (end - System.currentTimeMillis()).coerceAtLeast(0)
        timerView.setChronometer(R.id.countdown, base, null, !finished)
        timerView.setChronometerCountDown(R.id.countdown, true)
        if (finished) timerView.setTextViewText(R.id.countdown, "0:00")
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(timerView).setCustomBigContentView(timerView)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!finished)
            .setShowWhen(false).build()
        try { manager.notify(ID, notification) } catch (_: SecurityException) { /* Timer still works without notification permission. */ }
    }
}

class RestTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == "reset") RestTimer.reset(context) else {
                    val end = RestTimer.deadline(context)
                    if (end > 0 && end <= System.currentTimeMillis()) RestTimer.show(context, end, true)
                }
            } catch (failure: Exception) {
                android.util.Log.e("RestTimer", "Unable to update timer", failure)
            } finally { pending.finish() }
        }
    }
}

@Composable
fun RestTimerPanel(date: LocalDate) {
    val preferences = LocalUserPreferences.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var end by remember { mutableLongStateOf(0L) }
    var ready by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = "Notifications are disabled. The timer will still run in the app."
        else scope.launch { runCatching {
            val deadline = RestTimer.deadline(context)
            if (deadline > 0) RestTimer.show(context, deadline, deadline <= System.currentTimeMillis())
        }.onFailure { error = "Could not read the timer." } }
    }
    LaunchedEffect(Unit) {
        try {
            while (true) {
                val deadline = RestTimer.deadline(context)
                // Read the clock after the suspending settings read, alongside this deadline.
                now = System.currentTimeMillis()
                end = deadline
                ready = true
                delay(250)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { ready = false; error = "Could not read timer settings." }
    }
    val seconds = if (end == 0L) preferences.restSeconds else ((end - now + 999) / 1000).coerceIn(0, 86400).toInt()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("%d:%02d".format(seconds / 60, seconds % 60))
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                scope.launch {
                    runCatching {
                        RestTimer.start(context)
                        val deadline = RestTimer.deadline(context)
                        now = System.currentTimeMillis()
                        end = deadline
                    }.onFailure { error = "Could not start the timer." }
                }
            }, enabled = ready && (end == 0L || seconds == 0)) { Text("Start") }
            TextButton(enabled = ready, onClick = { scope.launch { runCatching { RestTimer.reset(context); end = 0 }.onFailure { error = "Could not reset timer." } } }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            DailyWeightPanel(date)
        }
        if (error.isNotEmpty()) Text(error)
    }
}
