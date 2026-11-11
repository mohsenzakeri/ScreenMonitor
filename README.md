# ScreenTrack (Android)

A native Android screen-time journal built with Kotlin, Jetpack Compose, and Room.

## Features
- Manual daily entry in total minutes (0–1440)
- Local on-device SQLite storage via Room; no account or network permission
- Edit and delete entries
- Weekly average and recent-week chart
- Monthly average and previous-month comparison
- Monthly daily bar chart and calendar heatmap
- CSV export through Android's document picker
- Optional notes for each day

## Open and run
1. Install Android Studio (a recent stable version).
2. Open this folder as an existing Gradle project.
3. Allow Gradle sync to finish. Use JDK 17.
4. Install Android SDK Platform 35 if prompted.
5. Select an emulator or connected Android phone and press Run.

## Notes
- Data is stored in the app's private database (`screentrack.db`).
- Uninstalling the app or clearing its storage deletes local entries. Export CSV regularly for a backup.
- CSV export is initiated from the save/export icon in the top app bar.
- This starter project does not request internet access and does not collect or transmit data.

## Project structure
- `MainActivity.kt`: Compose UI, entry editor, analytics, heatmap, CSV export
- `data/ScreenTimeDatabase.kt`: Room entity, DAO, database
