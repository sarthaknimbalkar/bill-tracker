package com.local.billtracker

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationManagerCompat

/** Things the phone can switch off that silently stop reminders. */
object ReminderHealth {
    data class Check(val title: String, val problem: String, val ok: Boolean, val fixLabel: String, val fix: (Context) -> Unit)

    fun checks(ctx: Context): List<Check> {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return listOf(
            Check("Notifications", "notifications are off",
                NotificationManagerCompat.from(ctx).areNotificationsEnabled(), "Allow", ::openNotificationSettings),
            Check("Exact reminder times", "exact alarms are off",
                Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), "Allow", ::openExactAlarmSettings),
            Check("Battery unrestricted", "battery saver can block reminders",
                pm.isIgnoringBatteryOptimizations(ctx.packageName), "Fix", ::requestBatteryExemption),
        )
    }

    fun allOk(ctx: Context) = checks(ctx).all { it.ok }

    private fun start(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun appDetails(ctx: Context) =
        start(ctx, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    private fun openNotificationSettings(ctx: Context) {
        start(ctx, Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)) || appDetails(ctx)
    }

    private fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31)
            start(ctx, Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) || appDetails(ctx)
    }

    @SuppressLint("BatteryLife") // A sideloaded reminder app is exactly the case this exemption exists for.
    private fun requestBatteryExemption(ctx: Context) {
        start(ctx, Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            || start(ctx, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || appDetails(ctx)
    }
}
