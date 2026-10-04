package com.local.billtracker

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Home-screen widget: monthly total plus the next few bills due. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        private val rows = listOf(
            Triple(R.id.row1, R.id.row1_name, R.id.row1_amt), Triple(R.id.row2, R.id.row2_name, R.id.row2_amt),
            Triple(R.id.row3, R.id.row3_name, R.id.row3_amt), Triple(R.id.row4, R.id.row4_name, R.id.row4_amt),
        )

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val store = Store.get(ctx)
            val s = store.settings.value
            val today = LocalDate.now()
            val active = store.bills.value.filter { !it.completed && !it.isPaused() }.sortedBy { it.dueDate }
            val (paid, due) = Reminders.monthTotals(store.bills.value)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_total, fmtMoney(s.currency, paid + due))
            v.setTextViewText(R.id.w_sub, "this month · ${fmtMoney(s.currency, due)} left")
            val overdue = active.count { it.dueDate.isBefore(today) }
            v.setTextViewText(R.id.w_badge, if (overdue > 0) "$overdue overdue" else "")
            v.setViewVisibility(R.id.w_badge, if (overdue > 0) View.VISIBLE else View.GONE)
            rows.forEachIndexed { i, (row, name, amt) ->
                val b = active.getOrNull(i)
                v.setViewVisibility(row, if (b != null) View.VISIBLE else View.GONE)
                if (b != null) {
                    val d = ChronoUnit.DAYS.between(today, b.dueDate)
                    val w = when { d < 0 -> "overdue"; d == 0L -> "today"; d == 1L -> "tomorrow"; d < 7 -> b.dueDate.dayOfWeek.name.take(3).lowercase()
                        .replaceFirstChar { it.uppercase() }; else -> fmtShort(b.dueDate) }
                    v.setTextViewText(name, "${b.name}  ·  $w")
                    v.setTextColor(name, if (d < 0) 0xFFFF8A80.toInt() else 0xFFE6E8EC.toInt())
                    v.setTextViewText(amt, fmtMoney(s.currency, if (b.variableAmount) b.expectedAmount else b.remaining))
                }
            }
            v.setViewVisibility(R.id.w_empty, if (active.isEmpty()) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(ids, v)
        }
    }
}
