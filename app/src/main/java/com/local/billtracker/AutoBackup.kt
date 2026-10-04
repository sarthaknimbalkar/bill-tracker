package com.local.billtracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Full ZIP backups that stay on the phone. Goes to the folder the user picked, or — if none —
 * to Downloads/BillTracker, which survives uninstalling the app.
 */
object AutoBackup {
    private const val WORK = "auto-backup"
    private const val KEEP_PICKED = 5
    private const val KEEP_DEFAULT = 14
    const val DEFAULT_FOLDER = "Download/BillTracker"

    /** Default location needs MediaStore Downloads (Android 10+). */
    val defaultAvailable get() = Build.VERSION.SDK_INT >= 29

    fun schedule(ctx: Context) {
        val s = Store.get(ctx).settings.value
        val wm = WorkManager.getInstance(ctx)
        if (s.autoBackupUri.isEmpty() && !defaultAvailable) { wm.cancelUniqueWork(WORK); return }
        // Check daily; the worker itself decides whether the interval has elapsed.
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build())
    }

    fun location(s: Settings) = if (s.autoBackupUri.isNotEmpty()) "your chosen folder" else DEFAULT_FOLDER

    /** Returns the file name written. */
    fun runNow(ctx: Context): String {
        val store = Store.get(ctx)
        val s = store.settings.value
        val name = "bill-tracker-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".zip"
        if (s.autoBackupUri.isNotEmpty()) writePicked(ctx, store, s, name) else writeDefault(ctx, store, name)
        store.patchSettings { it.copy(lastAutoBackup = LocalDateTime.now().withNano(0).toString()) }
        return name
    }

    private fun writePicked(ctx: Context, store: Store, s: Settings, name: String) {
        val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(s.autoBackupUri)) ?: error("Backup folder not accessible")
        if (!dir.canWrite()) error("Backup folder not writable — pick it again in Settings")
        val f = dir.createFile("application/zip", name) ?: error("Couldn't create backup file")
        ctx.contentResolver.openOutputStream(f.uri)!!.use { store.writeBackup(it) }
        dir.listFiles().filter { it.name?.startsWith("bill-tracker-") == true && it.name!!.endsWith(".zip") }
            .sortedByDescending { it.name }.drop(KEEP_PICKED).forEach { it.delete() }
    }

    private fun writeDefault(ctx: Context, store: Store, name: String) {
        if (!defaultAvailable) error("Pick a backup folder in Settings")
        val cr = ctx.contentResolver
        val coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BillTracker")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(coll, values) ?: error("Couldn't create backup in Downloads")
        try {
            cr.openOutputStream(uri)!!.use { store.writeBackup(it) }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null); throw e
        }
        // Prune our own older backups (the app can only see files it created).
        val ours = mutableListOf<Pair<Uri, String>>()
        cr.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'bill-tracker-%.zip'",
            arrayOf(Environment.DIRECTORY_DOWNLOADS + "/BillTracker%"), null)?.use { c ->
            while (c.moveToNext()) ours += android.content.ContentUris.withAppendedId(coll, c.getLong(0)) to c.getString(1)
        }
        ours.sortedByDescending { it.second }.drop(KEEP_DEFAULT).forEach { runCatching { cr.delete(it.first, null, null) } }
    }

    fun due(s: Settings): Boolean {
        if (s.autoBackupUri.isEmpty() && !defaultAvailable) return false
        // Default location backs up daily; a picked folder uses the chosen interval.
        val days = if (s.autoBackupUri.isEmpty()) 1L else s.autoBackupDays.toLong()
        val last = s.lastAutoBackup.takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse) ?: return true
        return !last.plusDays(days).minusHours(2).isAfter(LocalDateTime.now())
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        store.resolvePauses()
        val s = store.settings.value
        if (store.bills.value.isEmpty() || !AutoBackup.due(s)) return Result.success()
        return runCatching { AutoBackup.runNow(applicationContext) }.fold({ Result.success() }, { Result.retry() })
    }
}
