# Bill Tracker — Roadmap

All data stays on the device. No cloud, no accounts.
Legend: ✅ built & tested on phone · 🟡 built, needs a hands-on check (needs your input: folder picker, fingerprint, UPI app, home screen)

## Accuracy
- ✅ 1. Variable amounts — "Pay" asks how much was actually paid (prefilled with the usual amount / avg of last 3)
- ✅ 2. Edit / delete any past payment (tap a payment in the bill's history)
- 🟡 3. Full backup = ZIP of data + attachments; restore from ZIP (old JSON backups still accepted)
- 🟡 4. Automatic backups to a phone folder you pick (daily / weekly / monthly, keeps last 5)

## High value
- 🟡 5. Home-screen widget — month total, amount left, next 4 bills (long-press home screen → Widgets → Bill Tracker)
- ✅ 6. Snooze 1 day from the notification (only ever delays — never pulls a reminder earlier)
- ✅ 7. Monthly budget — shown in the summary card; one notification per month when bills exceed it
- 🟡 8. App lock — fingerprint / face / screen lock, re-locks after 60 s in background
- 🟡 9. Export payments to CSV (Excel / Sheets)

## Nice to have
- ✅ 10. Partial payments — pay part, rest stays due, progress bar on the card
- ✅ 11. Pause a bill (1/2/3/6 months or until a date) and "Skip this occurrence"; Resume restores skipped dates
- ✅ 12. Year-over-year comparison in Insights (chart + per category)
- 🟡 13. Pay via UPI (GPay / PhonePe / Paytm) — add the biller's UPI ID on the bill
- ✅ 14. App icon + light / dark / system theme

## UI/UX rebuild
- ✅ Bottom navigation (Bills · Calendar · Insights · Settings)
- ✅ Home: month summary card, overdue banner, tabs Upcoming · Overdue · Paid · All, grouped by Today / Tomorrow / This week / Later
- ✅ Bill detail screen (status, pay, UPI, pause, skip, history, notes, files)
- ✅ Pay sheet (amount, date, partial, note)
- ✅ Edit form in grouped sections with category chips
