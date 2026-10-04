# Bill Tracker: feature status

All data stays on the device. No cloud, no accounts.

Legend: ✅ built and tested on a phone. 🟡 built, still needs a hands-on check (folder picker, fingerprint, UPI app, home screen).

## Accuracy
- ✅ 1. Variable amounts. "Pay" asks how much was actually paid, prefilled with the average of the last 3.
- ✅ 2. Edit or delete any past payment (tap it in the bill's history).
- 🟡 3. Full backup as a ZIP of data and attachments. Restore accepts the ZIP or an old JSON backup.
- ✅ 4. Automatic daily backup to `Download/BillTracker` (last 14 kept), or to a folder you pick (daily, weekly or monthly, last 5 kept).

## High value
- 🟡 5. Home screen widget: month total, amount left, next 4 bills. Add it by long-pressing the home screen, then Widgets, then Bill Tracker.
- ✅ 6. Snooze 1 day from the notification. Snooze only ever delays a reminder.
- ✅ 7. Monthly budget, shown in the summary card, with one notification per month when bills exceed it.
- 🟡 8. App lock with fingerprint, face or screen lock. Locks again after 60 seconds in the background.
- 🟡 9. Export payments to CSV for Excel or Sheets.

## Nice to have
- ✅ 10. Partial payments. The rest stays due, with a progress bar on the card.
- ✅ 11. Pause a bill (1, 2, 3 or 6 months, or until a date) and "Skip this occurrence". Resuming early restores skipped dates.
- ✅ 12. Year over year comparison in Insights, as a chart and per category.
- 🟡 13. Pay via UPI (GPay, PhonePe, Paytm). Add the biller's UPI ID on the bill.
- ✅ 14. App icon, plus light, dark or system theme.

## UI
- ✅ Bottom navigation: Bills, Calendar, Insights, Settings.
- ✅ Home: month summary card, overdue banner, tabs for Upcoming, Overdue, Paid and All, bills grouped by Today, Tomorrow, This week and Later.
- ✅ Bill detail screen: status, pay, UPI, pause, skip, history, notes, files.
- ✅ Pay sheet: amount, date, partial, note.
- ✅ Edit form in grouped sections with category chips.

## Reliability
- ✅ Atomic saves with a last-good fallback; unreadable files are kept, never overwritten.
- ✅ All changes go through one lock, so a notification action and the app cannot overwrite each other.
- ✅ Stale "Mark as paid" notifications cannot pay the next month.
- ✅ Reminder health check with fixes for notifications, exact alarms and battery optimization.
- ✅ Reminders rescheduled after reboot, update, and time or time zone changes.
- ✅ Android cloud backup disabled.
- ✅ 21 unit tests: recurrence, payments, and storage round trip.
