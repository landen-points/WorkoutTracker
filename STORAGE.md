# Storage and CSV exports

Workout Tracker stores exercises, ordered sets, cardio details and daily scale readings in the local Room/SQLite database `workouts.db`. The application ID remains unchanged.

## First launch after upgrading

The repository reads the old `workout_data/workouts_json` and `body_weight/daily` preferences off the UI thread. It decodes both files before writing, copies the records in a single SQLite transaction, compares the copied records with the originals, and records completion in that same transaction. IDs, exercise order, repeated sets, cardio fields and weight dates are retained. A failed read or verification rolls back the copy and leaves migration incomplete so it can be retried. Original preference files are retained and are never used again after successful migration. Do not uninstall the previous app before installing this update.

Small persisted state uses Preferences DataStore (`settings.preferences_pb`). The existing rest-timer deadline is copied once from its old preferences; subsequent Start/Reset actions update DataStore.

## CSV analysis

Open Export CSV, select strength sets, cardio and/or daily scale, optionally select an exercise and an inclusive date range, and choose a destination file. Blank dates include all dates. Exercise selection filters exercise records; selected daily scale readings are independent of that filter.

The export is generated on demand from a consistent database transaction, with database queries applying the selected filters. It is a single rectangular UTF-8 CSV with a BOM, CRLF record endings, dates in the selected app-wide format and explicit selected units. Strength records have one row per set, cardio has one row per exercise entry, and scale readings have one row per date. Non-applicable fields are blank. `volume_lb` or `volume_kg` is set weight multiplied by reps. Exercise text is quoted as needed and formula-like text is escaped for spreadsheets.

CSV import has been removed. CSV is an analysis export, not a complete database backup/restore format.

## Maintenance and verification

Room's version-1 schema is saved under `app/schemas/`. Future schema changes must use explicit, tested migrations; do not add destructive fallback migrations.

Local format tests cover decoding, corrupt input, duplicates, 5,000 days of history, all export categories, filters, CSV escaping and Excel formula text. Instrumented `WorkoutRegressionTest` uses an isolated in-memory Room database to check first-launch copying, reruns, rollback, updates/deletes and export queries. Run those tests on an emulator before distributing the database update. They do not touch the installed app's real database or legacy preferences.


## App-wide settings

Preferences DataStore persists unit selection (lb by default, optionally kg), date order (MM/DD/YYYY by default, optionally DD/MM/YYYY), and rest duration (120 seconds by default). Existing weights remain stored in pounds; display values and newly entered weights are converted at the UI boundary. CSV numeric weights, volume and unit column names follow the selected unit, while exported dates and date-filter inputs follow the selected date format. Internal SQLite dates stay ISO so sorting and migration remain stable.

The rest duration textbox is in minutes and supports fractions, from one second through 1440 minutes. A saved duration affects idle/reset timers and newly started timers. An already-running timer keeps its deadline.

Clear All Data requires two confirmation dialogs and a checked acknowledgement. Deletion is one SQLite transaction and cascades to sets; it removes workout/cardio and daily weight records. The migration-complete marker remains so old preferences cannot resurrect deleted history. Display settings are retained. The in-memory UI snapshot refreshes after deletion. Instrumented tests cover the confirmation gate, cancellation, and preventing legacy reimport after clearing. No production data is cleared by source editing or test authoring.

## SQLite Backup and Restore

Settings provides Backup SQLite and Restore SQLite side by side, above Clear All Data. About is the last option.

Backup reads one consistent transaction from the live database, creates a fresh Room SQLite snapshot with a TRUNCATE journal, closes the snapshot, and writes its standalone `.sqlite` file to the user-selected Storage Access Framework destination. This captures committed records even when the live database uses WAL; it does not copy an open live database file. Workouts, ordered sets, cardio, daily weights, Room schema information and the migration marker are included. DataStore settings are separate and are not included.

Restore asks before replacing current records. It stages the selected file in the app cache, checks the SQLite header and version, integrity, foreign keys, Room schema identity, record fields and set references, and only then replaces live records in a transaction. A comparison verifies the inserted records before commit. Settings are retained. The existing database connection is not replaced underneath Room. Temporary snapshot files and sidecars are removed after use. Invalid input fails before live writes; a failed replacement transaction rolls back. Test coverage in WorkoutRegressionTest includes full and empty backup round trips and rejection of invalid or unsupported files.

Rest duration is now edited inline and saved when leaving Settings by sidebar or Android Back, or when the app enters the background. Invalid values do not overwrite the saved duration. Units and date order still save immediately. The duration's default-description text, manual Save button and divider above Clear All Data have been removed.


## Manual backup only

Android automatic backup is disabled (`allowBackup=false`), and both legacy and Android 12+ cloud/device-transfer XML rules exclude all app data domains. Manual SQLite backup/restore and CSV exports remain available. See PRIVACY_NOTES.md for the local-storage policy, export-destination caveat and deletion details to carry into the future privacy policy. Initial distribution is planned for F-Droid.
