# Bill Tracker

An Android app for tracking recurring bills and subscriptions. All data stays on the phone. There is no account, no server and no analytics.

Built with Kotlin and Jetpack Compose. Single module, no database library: data is one JSON file in app-private storage.

## Features

**Bills**
- One-time or recurring (every N days, weeks, months or years)
- End never, on a date, or after N payments
- Month-end dates do not drift (a bill on the 31st lands on the 28th in February and back on the 31st in March)
- Categories, notes, photo and file attachments

**Payments**
- Mark paid from the app or straight from the notification
- Variable amounts: enter what you actually paid; the expected amount is the average of the last 3
- Partial payments: the rest stays due, with a progress bar
- Skip one occurrence, or pause a bill until a date (resuming early restores skipped dates)
- Edit or delete any past payment

**Reminders**
- N days before, on the due date, then daily while overdue
- Snooze 1 day (snooze only ever delays a reminder)
- Rescheduled after reboot, app update, and time or time zone changes
- In-app health check for the things Android can switch off: notifications, exact alarms, battery optimization

**Overview**
- Home: amount left this month, overdue banner, bills grouped by Today, Tomorrow, This week, Later
- Calendar with due and paid markers
- Insights: fixed cost per month and year, last 12 months against the year before, cost by category, year over year
- Monthly budget with a one-time warning when bills go over it
- Home screen widget

**Privacy and safety**
- Optional app lock (fingerprint, face or screen lock)
- Android cloud backup is disabled
- Automatic daily ZIP backup to `Download/BillTracker` on the phone (last 14 kept), or to a folder you pick
- Manual export, restore, and CSV export of all payments
- UPI pay button (opens GPay, PhonePe, Paytm and so on with the amount filled in)
- Light, dark or system theme

## Requirements

- Android 8.0 (API 26) or newer. Automatic backup to Downloads needs Android 10 or newer; on older versions pick a folder instead.
- To build: JDK 17+ and the Android SDK (compileSdk 37).

## Build and install

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `install -r` to update an installed copy. Uninstalling deletes all data.

### Signing

If `keystore/keystore.properties` exists, both debug and release builds are signed with that key. Otherwise the standard Android debug key is used. The `keystore/` folder is ignored by git.

Format of `keystore/keystore.properties`:

```properties
storeFile=../keystore/your-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep the same key for the life of an install. A different key means Android refuses the update, and the only way forward is to uninstall, which wipes the data.

## Project layout

```
app/src/main/java/com/local/billtracker/
  Model.kt             Bill, Payment, Settings and all recurrence and payment rules
  Store.kt             Load and save, locking, backup ZIP, restore, CSV
  Reminders.kt         Alarm scheduling, notifications, notification actions, budget check
  AutoBackup.kt        Daily backup worker (WorkManager)
  ReminderHealth.kt    Checks for notifications, exact alarms, battery optimization
  Widget.kt            Home screen widget (RemoteViews)
  MainActivity.kt      Navigation, app lock, theme
  HomeScreen.kt        Home and search
  BillDetailScreen.kt  Bill page, payment history
  EditBillScreen.kt    Add and edit form
  OtherScreens.kt      Calendar, insights, settings
  ui/Theme.kt          Colors, typography, shapes
  ui/Components.kt     Shared composables, pay sheet, UPI intent
app/src/test/          JVM unit tests
```

## Data model

A recurring bill does not store its current due date. It stores `startDate` and `occurrence` (the index of the current unpaid occurrence), and the due date is computed as `startDate + occurrence * interval`. Computing from the start date is what keeps month-end dates stable.

Each `Payment` records the due date it belongs to, so partial payments add up per occurrence. Skipped occurrences are payments with `skipped = true` and amount 0.

Files in the app's private `files/` directory:

| File | Purpose |
|---|---|
| `bills.json` | Live data, written with `AtomicFile` |
| `bills.lastgood.json` | Copy from the last successful save, loaded if `bills.json` cannot be parsed |
| `bills.corrupt-<time>.json` | An unreadable file, kept instead of being overwritten |
| `bills.before-restore.json` | Data as it was just before the last restore |
| `attachments/` | Attached photos and files |

Older files without `occurrence` (version 1) are migrated on load.

## Tests

```sh
./gradlew testDebugUnitTest
```

Covers recurrence and month-end handling, paying, partial payments, undo, skip, pause and resume, end conditions, variable amounts, monthly cost, and a save and load round trip of every field.

## Permissions

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` | Bill reminders |
| `SCHEDULE_EXACT_ALARM` | Reminders at the time you set |
| `RECEIVE_BOOT_COMPLETED` | Restore reminders after a restart |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask to be exempt so reminders are not delayed or dropped |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | App lock (added by androidx.biometric) |
| `WAKE_LOCK`, `FOREGROUND_SERVICE`, `ACCESS_NETWORK_STATE` | Background backup job (added by WorkManager) |

No internet permission is requested, so the app cannot send data anywhere.

## Troubleshooting

**Reminders arrive late or not at all.** Open Settings and fix anything red under Reminder health. On ColorOS, MIUI and similar, also allow background activity and auto-launch in the system App info page.

**Moving to a new phone.** Export a backup in Settings, copy the ZIP over, install the app, then use Restore.
