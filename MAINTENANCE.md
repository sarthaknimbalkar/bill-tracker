# Bill Tracker: maintenance

The app on the phone holds real data. Everything below keeps it safe.

## Update the app (keeps data)
```
cd "E:\Bill Tracker"
%USERPROFILE%\.gradle-bootstrap\gradle-9.8.0\bin\gradle.bat testDebugUnitTest assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
`install -r` updates in place. **Never** run `adb uninstall`: it deletes all bills.
Updates only work if signed with `keystore\billtracker.jks`. Keep a copy of the `keystore` folder off this PC.

## Copy the data off the phone (before anything risky)
```
adb exec-out run-as com.local.billtracker cat files/bills.json > data-backup\bills-YYYY-MM-DD.json
```

## Backups on the phone
- Automatic: daily ZIP in `Download/BillTracker` (last 14 kept), or the folder picked in Settings.
- Manual: Settings → Export backup.
- Restore: Settings → Restore from backup → pick a ZIP (or an old .json). The data it replaces is saved
  inside the app as `bills.before-restore.json`.

## Recovery files inside the app (`files/`)
| File | What |
|---|---|
| `bills.json` | live data (written atomically) |
| `bills.lastgood.json` | copy from the last successful save; loaded automatically if `bills.json` is unreadable |
| `bills.corrupt-*.json` | an unreadable file that was set aside instead of overwritten |
| `bills.before-restore.json` | data as it was just before the last restore |

## If reminders stop arriving
Open the app → Settings → Reminder health and fix anything red. On ColorOS also:
App info → Battery usage → allow background activity and auto-launch.
