package com.local.billtracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All data lives in a JSON file in app-private storage. Nothing leaves the device. */
class Store private constructor(private val ctx: Context) {
    private val file = File(ctx.filesDir, "bills.json")
    /** AtomicFile: write goes to a side file and is fsynced before replacing, so a crash mid-save can't truncate data. */
    private val atomic = android.util.AtomicFile(file)
    /** Last known-good copy, refreshed on every successful save. Used if the main file is ever unreadable. */
    private val lastGood = File(ctx.filesDir, "bills.lastgood.json")
    private val attachDir get() = File(ctx.filesDir, "attachments").apply { mkdirs() }
    private val _bills = MutableStateFlow<List<Bill>>(emptyList())
    private val _settings = MutableStateFlow(Settings())
    val bills: StateFlow<List<Bill>> = _bills
    val settings: StateFlow<Settings> = _settings

    init {
        // Don't save here: save() refreshes the widget, which calls Store.get() before this instance is published.
        // Callers (MainActivity, receivers, worker) run resolvePauses() right after.
        load()
    }

    private fun load() {
        if (!file.exists() && !lastGood.exists() && !File(ctx.filesDir, "bills.json.bak").exists()) return
        val main = runCatching { fromJson(JSONObject(String(atomic.readFully()))) }
        if (main.isSuccess) return
        // Keep the unreadable file for recovery instead of silently overwriting it on the next save.
        if (file.exists()) file.copyTo(File(ctx.filesDir, "bills.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
        runCatching { fromJson(JSONObject(lastGood.readText())) }
    }

    private fun fromJson(o: JSONObject) {
        // Parse fully before publishing, so a half-parsed file never replaces good in-memory data.
        val bills = o.optJSONArray("bills")?.let { a -> (0 until a.length()).map { billFrom(a.getJSONObject(it)) } } ?: emptyList()
        val settings = o.optJSONObject("settings")?.let(::settingsFrom) ?: Settings()
        _bills.value = bills
        _settings.value = settings
    }

    private fun save() = synchronized(this) {
        val bytes = toJson().toString().toByteArray()
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        runCatching {
            val tmp = File(ctx.filesDir, "bills.lastgood.tmp")
            tmp.writeBytes(bytes); tmp.renameTo(lastGood)
        }
        runCatching { Widget.updateAll(ctx) }
    }

    /** Every read-modify-write goes through here so a notification action and the UI can't overwrite each other. */
    private inline fun <T> mutate(block: () -> T): T = synchronized(this, block)

    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("bills", JSONArray(_bills.value.map(::billJson)))
        .put("settings", settingsJson(_settings.value))

    /** Called on app start and daily: ends pauses whose date has passed. */
    fun resolvePauses() = mutate {
        val changed = _bills.value.map { it.resolvePause() }
        if (changed != _bills.value) { _bills.value = changed; save() }
    }

    fun get(id: String) = _bills.value.firstOrNull { it.id == id }

    fun upsert(b: Bill) = mutate {
        val fixed = b.resolvePause()
        _bills.value = _bills.value.filterNot { it.id == fixed.id } + fixed
        save()
        fixed
    }.also { Reminders.schedule(ctx, it); Reminders.checkBudget(ctx) }

    fun delete(id: String) {
        mutate {
            get(id)?.attachments?.forEach { File(it).delete() }
            _bills.value = _bills.value.filterNot { it.id == id }
            save()
        }
        Reminders.cancel(ctx, id)
    }

    /** Apply [f] to the latest stored copy of a bill (not a possibly stale copy from the UI). */
    private fun update(id: String, f: (Bill) -> Bill?) = mutate { get(id)?.let(f)?.let(::upsert) }

    fun pay(id: String, amount: Double? = null, on: LocalDate = LocalDate.now(), note: String = "", settle: Boolean = true) =
        update(id) { it.pay(amount ?: it.remaining.takeIf { r -> r > 0 } ?: it.amount, on, note, settle) }
            .also { if (settle) clearNotification(id) }

    fun skip(id: String) = update(id) { it.skip() }.also { clearNotification(id) }

    private fun clearNotification(id: String) =
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).cancel(id.hashCode()) }
    fun undoPayment(id: String) = update(id) { it.undoLastPayment() }
    fun resume(id: String) = update(id) { it.resume() }
    fun pause(id: String, until: LocalDate) = update(id) { it.copy(pausedUntil = until) }

    fun snooze(id: String, until: LocalDateTime) = update(id) { it.copy(snoozedUntil = until) }

    fun editPayment(billId: String, p: Payment) = update(billId) { b -> b.copy(payments = b.payments.map { if (it.id == p.id) p else it }) }

    /** Deleting a payment that closed an occurrence reopens that occurrence. */
    fun deletePayment(billId: String, paymentId: String) = update(billId) { b ->
        val p = b.payments.firstOrNull { it.id == paymentId } ?: return@update null
        val rest = b.payments.filterNot { it.id == paymentId }
        val stillCovered = rest.any { it.dueDate == p.dueDate }
        var nb = b.copy(payments = rest)
        if (!stillCovered && (b.completed || p.dueDate.isBefore(b.dueDate))) {
            val idx = if (b.recurring) b.occurrenceIndexOf(p.dueDate) else 0
            // Only roll back if no later occurrence has been paid.
            if (rest.none { it.dueDate.isAfter(p.dueDate) }) nb = nb.copy(occurrence = idx, completed = false)
        }
        nb
    }

    /** Save internal bookkeeping fields without rescheduling anything. */
    fun patchSettings(f: (Settings) -> Settings) = mutate { _settings.value = f(_settings.value); save() }

    fun updateSettings(s: Settings) {
        // The UI passes a copy that may predate a background backup; keep that bookkeeping field current.
        mutate { _settings.value = s.copy(lastAutoBackup = _settings.value.lastAutoBackup); save() }
        Reminders.rescheduleAll(ctx); AutoBackup.schedule(ctx)
    }

    // ---------- Backup ----------

    /** ZIP with data.json and every attachment. */
    fun writeBackup(out: OutputStream) {
        ZipOutputStream(out.buffered()).use { z ->
            val json = toJson()
            // Store attachment paths relative so they survive a move to another phone.
            val bills = json.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                val a = bills.getJSONObject(i).getJSONArray("attachments")
                for (j in 0 until a.length()) a.put(j, File(a.getString(j)).name)
            }
            z.putNextEntry(ZipEntry("data.json")); z.write(json.toString(2).toByteArray()); z.closeEntry()
            _bills.value.flatMap { it.attachments }.map(::File).filter { it.exists() }.forEach { f ->
                z.putNextEntry(ZipEntry("attachments/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    /** Accepts a ZIP from [writeBackup] or a plain JSON backup from v1. */
    fun restore(input: InputStream) {
        val bytes = input.use { it.readBytes() }
        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val jsonText = if (!isZip) String(bytes) else {
            var data: String? = null
            ZipInputStream(bytes.inputStream()).use { z ->
                generateSequence { z.nextEntry }.forEach { e ->
                    when {
                        e.name == "data.json" -> data = z.readBytes().toString(Charsets.UTF_8)
                        e.name.startsWith("attachments/") && !e.isDirectory -> {
                            val name = File(e.name).name
                            File(attachDir, name).outputStream().use { z.copyTo(it) }
                        }
                    }
                }
            }
            data ?: error("Not a Bill Tracker backup")
        }
        val o = JSONObject(jsonText) // throws on a bad file before anything is replaced
        mutate {
            // Safety copy of what's being replaced.
            File(ctx.filesDir, "bills.before-restore.json").writeText(toJson().toString())
            val keepAuto = _settings.value.autoBackupUri
            fromJson(o)
            // Re-point attachments at this phone's storage.
            _bills.value = _bills.value.map { b -> b.copy(attachments = b.attachments.map { File(attachDir, File(it).name).absolutePath }) }
            if (_settings.value.autoBackupUri.isEmpty()) _settings.value = _settings.value.copy(autoBackupUri = keepAuto)
            save()
        }
        Reminders.rescheduleAll(ctx)
    }

    fun writeCsv(out: OutputStream) {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        out.bufferedWriter().use { w ->
            w.write("Bill,Category,Due date,Paid on,Amount,Status,Note\n")
            _bills.value.flatMap { b -> b.payments.map { b to it } }.sortedBy { it.second.date }.forEach { (b, p) ->
                w.write(listOf(q(b.name), q(b.category.label), p.dueDate.toString(), p.date.toString(),
                    String.format(java.util.Locale.US, "%.2f", p.amount), if (p.skipped) "Skipped" else "Paid", q(p.note)).joinToString(",") + "\n")
            }
            _bills.value.filter { !it.completed }.forEach { b ->
                w.write(listOf(q(b.name), q(b.category.label), b.dueDate.toString(), "",
                    String.format(java.util.Locale.US, "%.2f", b.remaining), "Due", q("")).joinToString(",") + "\n")
            }
        }
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }

        private fun d(s: String?) = s?.takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse)
        private fun dt(s: String?) = s?.takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDateTime::parse)

        internal fun billJson(b: Bill) = JSONObject()
            .put("id", b.id).put("name", b.name).put("amount", b.amount).put("variableAmount", b.variableAmount)
            .put("category", b.category.name).put("startDate", b.startDate.toString()).put("occurrence", b.occurrence)
            .put("recurring", b.recurring).put("repeatEvery", b.repeatEvery).put("repeatUnit", b.repeatUnit.name)
            .put("endType", b.endType.name).put("endDate", b.endDate?.toString() ?: "").put("endCount", b.endCount ?: 0)
            .put("completed", b.completed).put("pausedUntil", b.pausedUntil?.toString() ?: "")
            .put("reminderEnabled", b.reminderEnabled).put("remindDaysBefore", b.remindDaysBefore)
            .put("remindHour", b.remindHour).put("remindMinute", b.remindMinute)
            .put("snoozedUntil", b.snoozedUntil?.toString() ?: "")
            .put("upiId", b.upiId).put("upiName", b.upiName)
            .put("notes", b.notes).put("attachments", JSONArray(b.attachments))
            .put("payments", JSONArray(b.payments.map {
                JSONObject().put("id", it.id).put("date", it.date.toString()).put("dueDate", it.dueDate.toString())
                    .put("amount", it.amount).put("note", it.note).put("skipped", it.skipped)
            }))

        internal fun billFrom(o: JSONObject): Bill {
            val payments = o.optJSONArray("payments")?.let { a ->
                (0 until a.length()).map { val p = a.getJSONObject(it)
                    Payment(id = p.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                        date = LocalDate.parse(p.getString("date")), dueDate = LocalDate.parse(p.getString("dueDate")),
                        amount = p.getDouble("amount"), note = p.optString("note"), skipped = p.optBoolean("skipped")) }
            } ?: emptyList()
            val legacyDue = d(o.optString("dueDate"))
            val start = d(o.optString("startDate")) ?: legacyDue ?: LocalDate.now()
            val b = Bill(
                id = o.getString("id"), name = o.optString("name"), amount = o.optDouble("amount", 0.0),
                variableAmount = o.optBoolean("variableAmount"),
                category = runCatching { Category.valueOf(o.optString("category")) }.getOrDefault(Category.OTHER),
                startDate = start, occurrence = o.optInt("occurrence", -1),
                recurring = o.optBoolean("recurring", true), repeatEvery = o.optInt("repeatEvery", 1).coerceAtLeast(1),
                repeatUnit = runCatching { RepeatUnit.valueOf(o.optString("repeatUnit")) }.getOrDefault(RepeatUnit.MONTH),
                endType = runCatching { EndType.valueOf(o.optString("endType")) }.getOrDefault(EndType.NEVER),
                endDate = d(o.optString("endDate")), endCount = o.optInt("endCount", 0).takeIf { it > 0 },
                completed = o.optBoolean("completed"), pausedUntil = d(o.optString("pausedUntil")),
                reminderEnabled = o.optBoolean("reminderEnabled", true),
                remindDaysBefore = o.optInt("remindDaysBefore", 1), remindHour = o.optInt("remindHour", 9),
                remindMinute = o.optInt("remindMinute", 0), snoozedUntil = dt(o.optString("snoozedUntil")),
                upiId = o.optString("upiId"), upiName = o.optString("upiName"), notes = o.optString("notes"),
                attachments = o.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                payments = payments,
            )
            // v1 files stored the current due date and no occurrence index.
            return if (b.occurrence >= 0) b
            else b.copy(occurrence = if (legacyDue != null && b.recurring) b.occurrenceIndexOf(legacyDue) else 0)
        }

        internal fun settingsJson(s: Settings) = JSONObject()
            .put("currency", s.currency).put("upcomingWindowDays", s.upcomingWindowDays)
            .put("defaultRemindDaysBefore", s.defaultRemindDaysBefore).put("defaultRemindHour", s.defaultRemindHour)
            .put("overdueDailyNag", s.overdueDailyNag).put("monthlyBudget", s.monthlyBudget)
            .put("appLock", s.appLock).put("theme", s.theme.name)
            .put("autoBackupUri", s.autoBackupUri).put("autoBackupDays", s.autoBackupDays)
            .put("lastAutoBackup", s.lastAutoBackup).put("budgetWarnedMonth", s.budgetWarnedMonth)

        internal fun settingsFrom(o: JSONObject) = Settings(
            currency = o.optString("currency", "₹"), upcomingWindowDays = o.optInt("upcomingWindowDays", 7),
            defaultRemindDaysBefore = o.optInt("defaultRemindDaysBefore", 1), defaultRemindHour = o.optInt("defaultRemindHour", 9),
            overdueDailyNag = o.optBoolean("overdueDailyNag", true), monthlyBudget = o.optDouble("monthlyBudget", 0.0),
            appLock = o.optBoolean("appLock"),
            theme = runCatching { ThemeMode.valueOf(o.optString("theme")) }.getOrDefault(ThemeMode.SYSTEM),
            autoBackupUri = o.optString("autoBackupUri"), autoBackupDays = o.optInt("autoBackupDays", 7),
            lastAutoBackup = o.optString("lastAutoBackup"), budgetWarnedMonth = o.optString("budgetWarnedMonth"),
        )
    }
}
