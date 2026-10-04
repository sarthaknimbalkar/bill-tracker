package com.local.billtracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object Reminders {
    const val CHANNEL = "bill_reminders"
    private const val CHANNEL_BUDGET = "budget"
    private const val ACTION_REMIND = "com.local.billtracker.REMIND"
    private const val ACTION_PAID = "com.local.billtracker.PAID"
    private const val ACTION_SNOOZE = "com.local.billtracker.SNOOZE"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bill reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders for upcoming and overdue bills" })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Next notification: N days before due, on due day, then daily while overdue. Snooze and pause override. */
    fun nextTrigger(b: Bill, s: Settings, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!b.reminderEnabled || b.completed) return null
        b.pausedUntil?.let { if (now.toLocalDate().isBefore(it)) return it.atTime(b.remindHour, b.remindMinute) }
        val normal = normalTrigger(b, s, now)
        // Snooze only ever delays a reminder.
        b.snoozedUntil?.let { sn -> if (sn.isAfter(now) && (normal == null || sn.isAfter(normal))) return sn }
        return normal
    }

    private fun normalTrigger(b: Bill, s: Settings, now: LocalDateTime): LocalDateTime? {
        val candidates = buildList {
            if (b.remindDaysBefore > 0) add(b.dueDate.minusDays(b.remindDaysBefore.toLong()))
            add(b.dueDate)
        }.map { it.atTime(b.remindHour, b.remindMinute) }
        candidates.firstOrNull { it.isAfter(now) }?.let { return it }
        if (!s.overdueDailyNag) return null
        var t = now.toLocalDate().atTime(b.remindHour, b.remindMinute)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t
    }

    private fun pi(ctx: Context, id: String, action: String, due: LocalDate? = null): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra("id", id).putExtra("due", due?.toString())
        return PendingIntent.getBroadcast(ctx, (id + action).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(ctx: Context, billId: String?, pay: Boolean) = PendingIntent.getActivity(ctx,
        (billId + pay).hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("billId", billId).putExtra("pay", pay)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, b: Bill) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, b.id, ACTION_REMIND)
        am.cancel(p)
        if (b.completed) NotificationManagerCompat.from(ctx).cancel(b.id.hashCode())
        val t = nextTrigger(b, Store.get(ctx).settings.value) ?: return
        val ms = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id, ACTION_REMIND))
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    fun rescheduleAll(ctx: Context) {
        Store.get(ctx).resolvePauses()
        Store.get(ctx).bills.value.forEach { schedule(ctx, it) }
    }

    fun handle(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val store = Store.get(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        when (intent.action) {
            ACTION_PAID -> {
                // Only pay the occurrence this notification was about; a stale notification must not pay next month.
                val due = intent.getStringExtra("due")
                val b = store.get(id)
                if (b != null && !b.completed && (due == null || due == b.dueDate.toString())) store.pay(id)
                nm.cancel(id.hashCode())
            }
            ACTION_SNOOZE -> {
                val b = store.get(id) ?: return
                store.snooze(id, LocalDate.now().plusDays(1).atTime(b.remindHour, b.remindMinute))
                nm.cancel(id.hashCode())
            }
            ACTION_REMIND -> {
                store.resolvePauses()
                val b = store.get(id) ?: return
                if (!b.isPaused()) notify(ctx, b, store.settings.value)
                // Snooze is one-shot.
                if (b.snoozedUntil != null && !b.snoozedUntil.isAfter(LocalDateTime.now())) store.upsert(b.copy(snoozedUntil = null))
                else schedule(ctx, b)
            }
        }
    }

    fun sendTest(ctx: Context) {
        val store = Store.get(ctx)
        val b = store.bills.value.filter { !it.completed }.minByOrNull { it.dueDate }
            ?: Bill(id = "test", name = "Sample bill", amount = 499.0)
        notify(ctx, b, store.settings.value)
    }

    private fun notify(ctx: Context, b: Bill, s: Settings) {
        if (b.completed) return
        val days = ChronoUnit.DAYS.between(LocalDate.now(), b.dueDate)
        val whenText = when {
            days > 1 -> "Due in $days days · ${fmtShort(b.dueDate)}"
            days == 1L -> "Due tomorrow"
            days == 0L -> "Due today"
            else -> "Overdue by ${-days} day${if (days == -1L) "" else "s"}"
        }
        val amountText = if (b.variableAmount) "~" + fmtMoney(s.currency, b.expectedAmount) else fmtMoney(s.currency, b.remaining)
        // Variable or UPI bills need input, so "Pay" opens the app's pay sheet instead of paying blindly.
        val needsInput = b.variableAmount || b.upiId.isNotBlank()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bill)
            .setContentTitle("${b.name} · $amountText")
            .setContentText(whenText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx, b.id, false)).setAutoCancel(true)
            .addAction(0, if (needsInput) "Pay…" else "Mark as paid",
                if (needsInput) openApp(ctx, b.id, true) else pi(ctx, b.id, ACTION_PAID, b.dueDate))
            .addAction(0, "Snooze 1 day", pi(ctx, b.id, ACTION_SNOOZE))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(b.id.hashCode(), n) }
    }

    /** Bills due this calendar month (paid + still due). */
    fun monthTotals(bills: List<Bill>, ym: YearMonth = YearMonth.now()): Pair<Double, Double> {
        val first = ym.atDay(1); val last = ym.atEndOfMonth()
        val paid = bills.sumOf { b -> b.payments.filter { !it.skipped && YearMonth.from(it.date) == ym }.sumOf { it.amount } }
        val due = bills.filter { !it.completed && !it.isPaused() }.sumOf { b ->
            b.nextDueDates(62).filter { !it.isAfter(last) }.sumOf { d ->
                when {
                    d.isBefore(first) && d != b.dueDate -> 0.0
                    d == b.dueDate -> if (b.variableAmount) (b.expectedAmount - b.paidTowardCurrent).coerceAtLeast(0.0) else b.remaining
                    else -> b.expectedAmount
                }
            }
        }
        return paid to due
    }

    fun checkBudget(ctx: Context) {
        val store = Store.get(ctx)
        val s = store.settings.value
        if (s.monthlyBudget <= 0) return
        val ym = YearMonth.now().toString()
        if (s.budgetWarnedMonth == ym) return
        val (paid, due) = monthTotals(store.bills.value)
        if (paid + due <= s.monthlyBudget) return
        store.patchSettings { it.copy(budgetWarnedMonth = ym) }
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUDGET)
            .setSmallIcon(R.drawable.ic_stat_bill)
            .setContentTitle("Over budget this month")
            .setContentText("Bills total ${fmtMoney(s.currency, paid + due)} vs budget ${fmtMoney(s.currency, s.monthlyBudget)}")
            .setContentIntent(openApp(ctx, null, false)).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(42, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Reminders.handle(ctx, intent)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx); AutoBackup.schedule(ctx); Widget.updateAll(ctx)
    }
}
