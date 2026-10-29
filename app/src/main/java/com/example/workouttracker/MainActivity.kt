package com.example.workouttracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.IOException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.LocalDate
import java.util.UUID
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = WorkoutRepository.get(applicationContext)
        setContent {
            MaterialTheme {
                WorkoutTrackerApp(repo)
            }
        }
    }
}

enum class Screen { DAY, CALENDAR, HISTORY, GRAPHING, SETTINGS }

internal val DateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = { LocalDate.parse(it) })
internal val MonthSaver = Saver<YearMonth, String>(save = { it.toString() }, restore = { YearMonth.parse(it) })
internal val SetsSaver = listSaver<List<ExerciseSet>, String>(
    save = { sets -> sets.map { "${it.weight},${it.reps}" } },
    restore = { rows -> rows.map { row ->
        val parts = row.split(',')
        ExerciseSet(parts[0].toDouble(), parts[1].toInt())
    } }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutTrackerContent(repo: WorkoutRepository) {
    val preferences = LocalUserPreferences.current
    var screen by rememberSaveable { mutableStateOf(Screen.DAY) }
    var selectedDate by rememberSaveable(stateSaver = DateSaver) { mutableStateOf(LocalDate.now()) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyName by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var restMinutesDraft by rememberSaveable(preferences.restSeconds) {
        mutableStateOf(formatWeight(preferences.restSeconds / 60.0))
    }
    var savingRest by remember { mutableStateOf(false) }
    fun navigateTo(next: Screen) {
        if (screen == Screen.SETTINGS && next != Screen.SETTINGS) {
            if (savingRest) return
            val seconds = runCatching { restSecondsFromMinutes(restMinutesDraft) }.getOrNull()
            if (seconds == null) { message = "Enter a valid rest timer duration before leaving Settings."; return }
            savingRest = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) { AppSettings.setRestSeconds(context, seconds) }
                    historyName = null
                    screen = next
                    drawerState.close()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { message = "Unable to save the rest timer. Please try again." }
                finally { savingRest = false }
            }
        } else {
            historyName = null
            screen = next
            scope.launch { drawerState.close() }
        }
    }
    // Also save when the app leaves the foreground (Home, another app, or a document picker).
    DisposableEffect(context, screen, restMinutesDraft, preferences.restSeconds) {
        var candidate: android.content.Context = context
        while (candidate is ContextWrapper && candidate !is ComponentActivity) candidate = candidate.baseContext
        val lifecycle = (candidate as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && screen == Screen.SETTINGS) {
                val seconds = runCatching { restSecondsFromMinutes(restMinutesDraft) }.getOrNull()
                if (seconds == null) message = "Rest timer was not saved. Enter a valid duration."
                else if (seconds != preferences.restSeconds) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { withContext(NonCancellable + Dispatchers.IO) { AppSettings.setRestSeconds(context, seconds) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = "Unable to save the rest timer. Please try again." }
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    var retry by remember { mutableIntStateOf(0) }
    val storageCheck by produceState<Result<Unit>?>(null, repo, retry) {
        value = null
        try { repo.initialize(); value = Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { value = Result.failure(error) }
    }
    if (storageCheck == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (storageCheck?.isFailure == true) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Unable to open workout history", style = MaterialTheme.typography.titleLarge)
            Text("Your original records have been retained. Please retry; do not uninstall or clear app data.")
            Button(onClick = { retry++ }) { Text("Retry") }
        }
        return
    }
    val revision by repo.revision.collectAsState()
    LaunchedEffect(revision) { refresh++ }
    val exercises = remember(selectedDate, refresh) { repo.getExercises(selectedDate) }
    val editingExercise = exercises.firstOrNull { it.id == editingId }

    var showExport by rememberSaveable { mutableStateOf(false) }
    var exportStrength by rememberSaveable { mutableStateOf(true) }
    var exportCardio by rememberSaveable { mutableStateOf(true) }
    var exportWeight by rememberSaveable { mutableStateOf(true) }
    var exportFrom by rememberSaveable { mutableStateOf("") }
    var exportThrough by rememberSaveable { mutableStateOf("") }
    var exportExercise by rememberSaveable { mutableStateOf<String?>(null) }
    var exportDateOrder by rememberSaveable { mutableStateOf(preferences.dates) }
    LaunchedEffect(preferences.dates) {
        if (exportDateOrder != preferences.dates) {
            exportFrom = runCatching { preferences.dates.format(exportDateOrder.parse(exportFrom)) }.getOrDefault("")
            exportThrough = runCatching { preferences.dates.format(exportDateOrder.parse(exportThrough)) }.getOrDefault("")
            exportDateOrder = preferences.dates
        }
    }
    var pendingExport by remember { mutableStateOf(false) }
    fun exportOptions() = ExportOptions(exportStrength, exportCardio, exportWeight,
        exportFrom.takeIf { it.isNotBlank() }?.let { preferences.dates.parse(it) },
        exportThrough.takeIf { it.isNotBlank() }?.let { preferences.dates.parse(it) }, exportExercise)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val contents = repo.exportCsv(exportOptions(), preferences)
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: throw IOException("The destination could not be opened.")
                        stream.use { it.write(contents.toByteArray(Charsets.UTF_8)) }
                    }
                    message = "Workout history exported."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    message = "Export failed. Your workout history is unchanged. The destination file may be incomplete; please export again."
                } finally { pendingExport = false }
            }
        } else { pendingExport = false }
    }

    BackHandler(enabled = historyName != null || screen != Screen.DAY) {
        if (historyName != null && screen == Screen.HISTORY) historyName = null
        else navigateTo(Screen.DAY)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Workout Tracker",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Day View") },
                    selected = screen == Screen.DAY,
                    onClick = {
                        navigateTo(Screen.DAY)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Calendar") },
                    selected = screen == Screen.CALENDAR,
                    onClick = {
                        navigateTo(Screen.CALENDAR)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("Exercise History") },
                    selected = screen == Screen.HISTORY,
                    onClick = {
                        navigateTo(Screen.HISTORY)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(label = { Text("Graphing (BETA)") }, selected = screen == Screen.GRAPHING,
                    onClick = { navigateTo(Screen.GRAPHING) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                NavigationDrawerItem(label = { Text("Settings") }, selected = screen == Screen.SETTINGS,
                    onClick = { navigateTo(Screen.SETTINGS) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Export CSV") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (!pendingExport) showExport = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (screen) {
                                Screen.DAY -> "Exercises"
                                Screen.CALENDAR -> "Calendar"
                                Screen.HISTORY -> "Exercise History"
                                Screen.GRAPHING -> "Graphing"
                                Screen.SETTINGS -> "Settings"
                            }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                if (screen == Screen.DAY) {
                    FloatingActionButton(onClick = {
                        editingId = null
                        draftId = UUID.randomUUID().toString()
                        showDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add exercise")
                    }
                }
            }
        ) { padding ->
            when (screen) {
                Screen.SETTINGS -> SettingsScreen(repo, Modifier.padding(padding), restMinutesDraft, { restMinutesDraft = it })
                Screen.GRAPHING -> GraphingScreen(repo, Modifier.padding(padding))
                Screen.DAY -> DayScreen(
                    date = selectedDate,
                    repo = repo,
                    exercises = exercises,
                    onPrevDay = { selectedDate = selectedDate.minusDays(1) },
                    onNextDay = { selectedDate = selectedDate.plusDays(1) },
                    onEdit = { exercise ->
                        editingId = exercise.id
                        showDialog = true
                    },
                    onDelete = { id ->
                        pendingDeleteId = id
                    },
                    modifier = Modifier.padding(padding)
                )
                Screen.CALENDAR -> CalendarScreen(
                    repo = repo,
                    refresh = refresh,
                    onDaySelected = { date ->
                        selectedDate = date
                        screen = Screen.DAY
                    },
                    modifier = Modifier.padding(padding)
                )
                Screen.HISTORY -> HistoryScreen(
                    repo = repo,
                    refresh = refresh,
                    selectedName = historyName,
                    onSelectName = { historyName = it },
                    onJumpToDate = { date ->
                        selectedDate = date
                        historyName = null
                        screen = Screen.DAY
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showExport) {
        val validOptions = runCatching { exportOptions().also { it.validate() } }
        AlertDialog(onDismissRequest = { showExport = false }, title = { Text("Export CSV") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose data for Excel analysis. Leave dates blank for all history.")
                Text("Units: ${preferences.units.symbol}; dates: ${preferences.dates.label}")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportStrength, { exportStrength = it }); Text("Strength sets") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportCardio, { exportCardio = it }); Text("Cardio") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportWeight, { exportWeight = it }); Text("Daily scale") }
                ChoiceMenu(exportExercise ?: "All exercises", listOf("All exercises") + repo.allExerciseNames()) {
                    exportExercise = it.takeUnless { name -> name == "All exercises" }
                }
                OutlinedTextField(exportFrom, { exportFrom = it }, label = { Text("From (${preferences.dates.label})") }, singleLine = true)
                OutlinedTextField(exportThrough, { exportThrough = it }, label = { Text("Through (${preferences.dates.label})") }, singleLine = true)
                if (validOptions.isFailure) Text("Select data and enter valid dates in chronological order.")
            } },
            confirmButton = { TextButton(enabled = validOptions.isSuccess && !pendingExport, onClick = {
                try {
                    pendingExport = true
                    exportLauncher.launch("workout_analysis_${preferences.dateText(LocalDate.now()).replace('/', '-')}.csv")
                    showExport = false
                } catch (error: Exception) { pendingExport = false; message = "Could not open the export file picker." }
            }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } })
    }
    val pendingDelete = exercises.firstOrNull { it.id == pendingDeleteId }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete exercise?") },
            text = { Text("Delete ${pendingDelete.title} and its sets from ${preferences.dateText(selectedDate)}?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    val dateToDelete = selectedDate
                    scope.launch {
                    try {
                        repo.deleteExercise(dateToDelete, pendingDelete.id)
                        refresh++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        message = "Could not confirm the deletion was saved. Please try again."
                    }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } }
        )
    }
    if (showDialog) {
        ExerciseDialog(
            existing = editingExercise,
            repo = repo,
            suggestions = remember(refresh) { repo.allExerciseNames() },
            onDismiss = { showDialog = false },
            onSave = { title, sets, cardio ->
                val dateToSave = selectedDate
                val exerciseToEdit = editingExercise
                val idToSave = draftId
                scope.launch {
                try {
                    if (editingId != null) {
                        val editing = exerciseToEdit ?: throw IOException("Exercise no longer exists.")
                        repo.updateExercise(dateToSave, editing.copy(title = title, sets = sets, cardio = cardio))
                    } else {
                        repo.addExercise(dateToSave, Exercise(id = idToSave, title = title, sets = sets, cardio = cardio))
                    }
                    refresh++
                    showDialog = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    message = "Could not confirm the exercise was saved. Your draft is still open; please try again."
                }
                }
            }
        )
    }
    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }

}

@Composable
fun DayScreen(
    date: LocalDate,
    repo: WorkoutRepository,
    exercises: List<Exercise>,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onEdit: (Exercise) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val preferences = LocalUserPreferences.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = LocalDateTime.now(); delay(1000) }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            IconButton(onClick = onPrevDay) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            val label = if (date == now.toLocalDate()) {
                preferences.dateText(date) + ", " + now.format(DateTimeFormatter.ofPattern("h:mm a"))
            } else preferences.dateText(date)
            Text(label, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onNextDay) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
            }
        }

        RestTimerPanel(date)

        if (exercises.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No exercises logged for this day.\nTap + to add one.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(exercises, key = { it.id }) { exercise ->
                    ExerciseCard(
                        exercise = exercise,
                        onEdit = { onEdit(exercise) },
                        onDelete = { onDelete(exercise.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ExerciseCard(exercise: Exercise, onEdit: () -> Unit, onDelete: () -> Unit) {
    val preferences = LocalUserPreferences.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exercise.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit exercise")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete exercise")
                }
            }
            exercise.cardio?.let { Text(it.description()) }
            exercise.sets.forEachIndexed { index, set ->
                Text(
                    "Set ${index + 1}:  ${preferences.weightText(set.weight)} ${preferences.units.symbol} × ${set.reps} reps",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun ExerciseDialog(
    existing: Exercise?,
    repo: WorkoutRepository,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, List<ExerciseSet>, Cardio?) -> Unit
) {
    val preferences = LocalUserPreferences.current
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var sets by rememberSaveable(stateSaver = SetsSaver) {
        mutableStateOf(existing?.sets ?: emptyList())
    }
    var weightInput by rememberSaveable { mutableStateOf("") }
    var repsInput by rememberSaveable { mutableStateOf("") }
    var cardioMode by rememberSaveable { mutableStateOf(existing?.cardio != null) }
    var zone by rememberSaveable { mutableStateOf(existing?.cardio?.zone ?: 2) }
    var heartRate by rememberSaveable { mutableStateOf(existing?.cardio?.averageHeartRate?.toString() ?: "") }
    var minutes by rememberSaveable { mutableStateOf(existing?.cardio?.minutes?.toString() ?: "") }
    var showPrevious by rememberSaveable { mutableStateOf(false) }
    val cardio = if (cardioMode && (heartRate.toIntOrNull() ?: 0) in 1..300 &&
        minutes.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true)
        Cardio(zone, heartRate.toInt(), minutes.toDouble()) else null
    var showSuggestions by remember { mutableStateOf(false) }

    val filtered = remember(title, suggestions) {
        val query = title.trim()
        if (query.isBlank()) emptyList()
        else suggestions
            .filter { it.contains(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }
            .take(5)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    if (existing != null) "Edit Exercise" else "Add Exercise",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(12.dp))

                Box {
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            showSuggestions = true
                        },
                        label = { Text("Exercise name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = showSuggestions && filtered.isNotEmpty(),
                        onDismissRequest = { showSuggestions = false },
                        properties = PopupProperties(focusable = false)
                    ) {
                        filtered.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    title = name
                                    showSuggestions = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cardioMode, onCheckedChange = { cardioMode = it })
                    Text("Cardio")
                    if (cardioMode) {
                        TextButton(onClick = { showPrevious = true }, enabled = title.isNotBlank()) { Text("Last Logged") }
                    }
                }
                if (cardioMode) {
                    ChoiceMenu("Zone $zone", (1..5).map { "Zone $it" }) { zone = it.substringAfter(" ").toInt() }
                    OutlinedTextField(heartRate, { heartRate = it }, label = { Text("Average heart rate (bpm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(minutes, { minutes = it }, label = { Text("Duration (minutes)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showPrevious = true }, enabled = title.isNotBlank()) { Text("Last Logged") }
                }
                sets.forEachIndexed { index, set ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Set ${index + 1}:  ${preferences.weightText(set.weight)} ${preferences.units.symbol} × ${set.reps} reps",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { sets = sets.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove set")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row {
                    OutlinedTextField(
                        value = weightInput,
                        onValueChange = { weightInput = it },
                        label = { Text("Weight (${preferences.units.symbol})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = repsInput,
                        onValueChange = { repsInput = it },
                        label = { Text("Reps") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))

                val canAddSet = weightInput.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true &&
                        (repsInput.toIntOrNull() ?: 0) > 0
                OutlinedButton(
                    onClick = {
                        sets = sets + ExerciseSet(preferences.units.toPounds(weightInput.toDouble()), repsInput.toInt())
                        weightInput = ""
                        repsInput = ""
                    },
                    enabled = canAddSet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Set")
                }
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(title.trim(), if (cardioMode) emptyList() else sets.toList(), cardio) },
                        enabled = title.isNotBlank() && (if (cardioMode) cardio != null else sets.isNotEmpty())
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
    if (showPrevious) {
        val last = repo.history(title.trim()).firstOrNull { it.exercise.id != existing?.id }
        AlertDialog(onDismissRequest = { showPrevious = false }, title = { Text(title.trim()) },
            text = { Column { if (last == null) Text("No previous entries.") else {
                Text(preferences.dateText(last.date))
                last.exercise.cardio?.let { Text(it.description()) }
                last.exercise.sets.forEachIndexed { i, set -> Text("Set ${i + 1}: ${preferences.weightText(set.weight)} ${preferences.units.symbol} x ${set.reps} reps") }
            } } }, confirmButton = { TextButton(onClick = { showPrevious = false }) { Text("Close") } })
    }

}

@Composable
fun CalendarScreen(
    repo: WorkoutRepository,
    refresh: Int,
    onDaySelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var month by rememberSaveable(stateSaver = MonthSaver) { mutableStateOf(YearMonth.now()) }
    val markedDays = remember(month, refresh) { repo.daysWithEntries(month) }
    val today = LocalDate.now()

    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
            TextButton(onClick = { onDaySelected(LocalDate.now()) }) {
                Text("Today")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { day ->
                Text(
                    day,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        val firstDayOffset = month.atDay(1).dayOfWeek.value % 7 // Sunday = 0
        val daysInMonth = month.lengthOfMonth()
        val totalRows = (firstDayOffset + daysInMonth + 6) / 7

        for (row in 0 until totalRows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - firstDayOffset + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (day in 1..daysInMonth) {
                            val date = month.atDay(day)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { onDaySelected(date) }
                            ) {
                                Text(
                                    "$day",
                                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (date == today) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (day in markedDays) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    )
                                } else {
                                    Spacer(Modifier.size(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    repo: WorkoutRepository,
    refresh: Int,
    selectedName: String?,
    onSelectName: (String?) -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val preferences = LocalUserPreferences.current
    if (selectedName == null) {
        val names = remember(refresh) { repo.allExerciseNames() }
        if (names.isEmpty()) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No exercises logged yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(names) { name ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectName(name) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    } else {
        val entries = remember(selectedName, refresh) { repo.history(selectedName) }
        Column(modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = { onSelectName(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to exercise list")
                }
                Text(selectedName, style = MaterialTheme.typography.titleLarge)
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries) { entry ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJumpToDate(entry.date) }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                preferences.dateText(entry.date),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            entry.exercise.cardio?.let { Text(it.description()) }
                            entry.exercise.sets.forEachIndexed { index, set ->
                                Text(
                                    "Set ${index + 1}:  ${preferences.weightText(set.weight)} ${preferences.units.symbol} × ${set.reps} reps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}