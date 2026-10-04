package com.local.billtracker

import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun copyIn(ctx: Context, uri: Uri): String? = runCatching {
    val ext = ctx.contentResolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: ""
    val f = File(File(ctx.filesDir, "attachments").apply { mkdirs() }, UUID.randomUUID().toString() + ext)
    ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
    f.absolutePath
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditBillScreen(id: String?, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val store = rememberStore()
    val s = settingsState()
    val orig = remember(id) {
        id?.let { store.get(it) } ?: Bill(startDate = LocalDate.now(), remindDaysBefore = s.defaultRemindDaysBefore, remindHour = s.defaultRemindHour)
    }
    val isNew = id == null
    var b by remember(id) { mutableStateOf(orig) }
    var due by remember(id) { mutableStateOf(orig.dueDate) }
    var amount by remember(id) { mutableStateOf(amountText(orig.amount)) }
    var every by remember(id) { mutableStateOf(orig.repeatEvery.toString()) }
    var count by remember(id) { mutableStateOf(orig.endCount?.toString() ?: "") }
    var alreadyPaid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile; if (ok && f != null) b = b.copy(attachments = b.attachments + f.absolutePath) else f?.delete()
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { copyIn(ctx, it) }?.let { b = b.copy(attachments = b.attachments + it) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyIn(ctx, it) }?.let { b = b.copy(attachments = b.attachments + it) }
    }

    /** Applies typed fields and anchors the series so the current occurrence lands on [due]. */
    fun build(): Bill {
        val base = b.copy(repeatEvery = every.toIntOrNull()?.coerceAtLeast(1) ?: 1, endCount = count.toIntOrNull()?.coerceAtLeast(1))
        val anchored = base.copy(startDate = due)
        return base.copy(startDate = if (base.recurring) anchored.occurrenceDate(-base.occurrence) else due)
    }
    val preview = build()

    fun save() {
        val amt = parseAmount(amount)
        error = when {
            b.name.isBlank() -> "Give the bill a name"
            amt == null -> "Enter an amount"
            b.recurring && b.endType == EndType.ON_DATE && b.endDate == null -> "Pick an end date"
            b.recurring && b.endType == EndType.AFTER_COUNT && preview.endCount == null -> "Enter how many times"
            b.upiId.isNotBlank() && !b.upiId.contains('@') -> "UPI ID looks wrong (e.g. name@bank)"
            else -> null
        }
        if (error != null) return
        // A payment may have been recorded (e.g. from a notification) while this form was open.
        // Take payment state from the stored copy so saving the form never erases it.
        val latest = id?.let { store.get(it) }
        if (latest != null && (latest.occurrence != orig.occurrence || latest.payments != orig.payments)) {
            if (latest.occurrence != orig.occurrence && due == orig.dueDate) due = latest.dueDate
            b = b.copy(occurrence = latest.occurrence, completed = latest.completed, pausedUntil = latest.pausedUntil)
        }
        val fresh = build()
        var out = fresh.copy(payments = latest?.payments ?: b.payments,name = b.name.trim(), amount = amt!!, upiId = b.upiId.trim(), snoozedUntil = null)
        // Editing the schedule can revive an ended series.
        if (out.completed && out.occurrenceExists(out.occurrence) && out.recurring && out.endType != EndType.NEVER) out = out.copy(completed = false)
        if (alreadyPaid) out = out.pay(out.amount)
        store.upsert(out)
        orig.attachments.filter { it !in out.attachments }.forEach { File(it).delete() }
        onBack()
    }

    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${b.name}?") }, text = { Text("The bill, its payment history and files will be removed.") },
        confirmButton = { TextButton(onClick = { store.delete(orig.id); onDeleted() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })

    Scaffold(topBar = {
        BackTopBar(if (isNew) "New bill" else "Edit bill", onBack) {
            if (!isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "Delete") }
            Button(onClick = ::save, Modifier.padding(end = 8.dp)) { Text("Save") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            error?.let {
                Surface(color = LocalStatus.current.overdueBg, shape = MaterialTheme.shapes.medium) {
                    Text(it, Modifier.fillMaxWidth().padding(12.dp), color = LocalStatus.current.overdue)
                }
            }

            // ---- Basics
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(b.name, { b = b.copy(name = it); error = null }, Modifier.fillMaxWidth(),
                    label = { Text("Bill name") }, placeholder = { Text("e.g. Electricity") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
                OutlinedTextField(amount, { amount = it; error = null }, Modifier.fillMaxWidth(),
                    label = { Text(if (b.variableAmount) "Usual amount (estimate)" else "Amount") }, prefix = { Text(s.currency) },
                    singleLine = true, textStyle = MaterialTheme.typography.titleLarge.merge(Mono),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Amount changes each time", style = MaterialTheme.typography.bodyLarge)
                        Text("You'll enter the real amount when paying", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(b.variableAmount, { b = b.copy(variableAmount = it) })
                }
            }

            // ---- Category
            Column {
                Label("Category")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Category.entries) { c ->
                        FilterChip(b.category == c, { b = b.copy(category = c) }, label = { Text(c.label) },
                            leadingIcon = { Icon(c.icon(), null, Modifier.size(18.dp), tint = Color(c.color)) }, shape = CircleShape)
                    }
                }
            }

            // ---- Schedule
            SectionCard("Schedule") {
                RowItem(Icons.Default.Event, if (isNew || orig.payments.isEmpty()) "Due date" else "Current due date", fmtDate(due),
                    onClick = { pickDate(ctx, due) { due = it } })
                Divider16()
                RowItem(Icons.Default.Repeat, "Repeats", trailing = { Switch(b.recurring, { b = b.copy(recurring = it) }) })
                if (b.recurring) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Every")
                            OutlinedTextField(every, { every = it.filter(Char::isDigit).take(3) }, Modifier.width(72.dp), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RepeatUnit.entries.forEach { u ->
                                FilterChip(b.repeatUnit == u, { b = b.copy(repeatUnit = u) }, label = { Text(u.label.replace("(s)", if (every == "1") "" else "s")) })
                            }
                        }
                        Text("Ends", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(b.endType == EndType.NEVER, { b = b.copy(endType = EndType.NEVER) }, label = { Text("Never") })
                            FilterChip(b.endType == EndType.ON_DATE, {
                                pickDate(ctx, b.endDate ?: due.plusYears(1)) { b = b.copy(endDate = it, endType = EndType.ON_DATE) }
                            }, label = { Text(if (b.endType == EndType.ON_DATE && b.endDate != null) "On ${fmtShort(b.endDate!!)}" else "On a date") })
                            FilterChip(b.endType == EndType.AFTER_COUNT, { b = b.copy(endType = EndType.AFTER_COUNT) }, label = { Text("After N times") })
                        }
                        if (b.endType == EndType.AFTER_COUNT) OutlinedTextField(count, { count = it.filter(Char::isDigit).take(4) },
                            Modifier.width(140.dp), label = { Text("Times") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        val next = preview.copy(completed = false).nextDueDates(4)
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                            Text("${preview.recurrenceText()}  ·  next: ${next.joinToString(", ") { fmtShort(it) }}",
                                Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (isNew) {
                    Divider16()
                    RowItem(Icons.Default.CheckCircleOutline, "Already paid this one", trailing = { Switch(alreadyPaid, { alreadyPaid = it }) })
                }
            }

            // ---- Reminders
            SectionCard("Reminder") {
                RowItem(Icons.Default.Notifications, "Notify me", trailing = { Switch(b.reminderEnabled, { b = b.copy(reminderEnabled = it) }) })
                if (b.reminderEnabled) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 1, 2, 3, 7).forEach { d ->
                                FilterChip(b.remindDaysBefore == d, { b = b.copy(remindDaysBefore = d) },
                                    label = { Text(if (d == 0) "On the day" else "${d}d before") })
                            }
                        }
                        AssistChip(onClick = {
                            TimePickerDialog(ctx, { _, h, m -> b = b.copy(remindHour = h, remindMinute = m) }, b.remindHour, b.remindMinute, false).show()
                        }, label = { Text("At " + LocalTime.of(b.remindHour, b.remindMinute).format(DateTimeFormatter.ofPattern("h:mm a"))) },
                            leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)) })
                        Text("Also on the due date" + if (s.overdueDailyNag) ", then daily until paid." else ".",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- UPI
            SectionCard("Pay with UPI (optional)") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(b.upiId, { b = b.copy(upiId = it.trim()); error = null }, Modifier.fillMaxWidth(),
                        label = { Text("UPI ID") }, placeholder = { Text("biller@okicici") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    OutlinedTextField(b.upiName, { b = b.copy(upiName = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Payee name") }, singleLine = true)
                }
            }

            // ---- Notes & files
            SectionCard("Notes & files") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(b.notes, { b = b.copy(notes = it) }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        placeholder = { Text("Account number, customer ID…") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {
                            val f = File(File(ctx.filesDir, "attachments").apply { mkdirs() }, UUID.randomUUID().toString() + ".jpg"); cameraFile = f
                            camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                        }, label = { Text("Camera") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            label = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { files.launch(arrayOf("*/*")) },
                            label = { Text("File") }, leadingIcon = { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)) })
                    }
                    b.attachments.forEach { path ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { openFile(ctx, path) }, Modifier.weight(1f)) { Text(File(path).name.takeLast(26), maxLines = 1) }
                            IconButton(onClick = { b = b.copy(attachments = b.attachments - path) }) { Icon(Icons.Default.Close, "Remove") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), Modifier.padding(start = 4.dp, bottom = 8.dp),
    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
