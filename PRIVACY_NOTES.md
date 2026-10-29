# Privacy policy preparation notes

These are implementation notes for the future privacy policy, not a published policy.

## Product decisions

- Initial distribution target: F-Droid. Google Play preparation is deferred.
- The user has verified backup -> clear -> restore on their physical phone and confirmed timer persistence through their tested phone actions.
- Permanent application ID, app name and icon remain undecided. Git/repository setup and further device testing are handled by the project owner.

## Local storage and user-controlled exports

- Workouts, exercise sets, cardio details and daily body weights are stored locally in the app-private Room/SQLite database.
- Units, date format, rest duration and timer state are stored locally in Preferences DataStore. Original local SharedPreferences files are retained for migration fallback, with migration markers preventing automatic reimport after clearing.
- Android automatic backup is disabled in the manifest. Legacy backup rules and Android 12+ cloud/device-transfer rules explicitly exclude all app data domains. No automatic cloud backup or automatic device-transfer feature is offered by the app.
- SQLite Backup and Restore are manual, user-initiated operations. SQLite snapshots contain workout and scale records, not DataStore settings.
- CSV exports are generated only when requested by the user, for external analysis.
- Files are written to/read from the destination selected in Android's system file picker. A user may choose a local destination or a third-party cloud/document provider; that choice does not mean the app itself offers automatic cloud backup or sync. Avoid claiming exports can never leave the device.
- The app does not operate its own data-upload or synchronization service. No analytics or advertising service has been added. Recheck release dependencies and permissions before finalizing these claims in the published policy.
- Disabling automatic backup does not itself delete backups previously created by Android or files the user has already exported. Do not claim those historical copies have been erased.
- CLEAR ALL DATA removes active SQLite workout and scale records. Display settings, retained legacy migration files, and separately exported files have distinct lifecycles; the privacy policy must describe deletion accurately rather than promising it erases every external or historical copy.

## Release metadata

About reads VERSION_NAME and VERSION_CODE from the generated BuildConfig so displayed release metadata follows the build configuration.
