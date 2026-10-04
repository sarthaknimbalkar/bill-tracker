package com.local.billtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun BillDetailScreen(id: String, openPay: Boolean, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    val store = rememberStore()
    val bills by store.bills.collectAsState()
    val s = settingsState()
    val st = LocalStatus.current
    val b = bills.firstOrNull { it.id == id }
    if (b == null) { LaunchedEffect(Unit) { onBack() }; return }

    var paying by remember { mutableStateOf(openPay && !b.completed) }
    var editing by remember { mutableStateOf<Payment?>(null) }
    var pauseMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    if (paying) PaySheet(b) { paying = false }
    editing?.let { p -> PaymentDialog(b, p, s.currency, onDismiss = { editing = null }) }

    val status = b.status()
    val (fg, bg) = when (status) {
        Status.OVERDUE -> st.overdue to st.overdueBg; Status.PAID -> st.paid to st.paidBg
        Status.PAUSED -> st.paused to MaterialTheme.colorScheme.surfaceContainerHighest; Status.DUE -> st.due to st.dueBg
    }

    Scaffold(topBar = {
        BackTopBar("", onBack) {
            IconButton(onClick = { onEdit(b.id) }) { Icon(Icons.Default.Edit, "Edit") }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    if (!b.completed && b.recurring) DropdownMenuItem(text = { Text("Skip this occurrence") },
                        leadingIcon = { Icon(Icons.Default.SkipNext, null) }, onClick = { store.skip(b.id); moreMenu = false })
                    if (b.payments.isNotEmpty()) DropdownMenuItem(text = { Text("Undo last payment") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) }, onClick = { store.undoPayment(b.id); moreMenu = false })
                }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CategoryAvatar(b.category, 64.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(b.name, style = MaterialTheme.typography.headlineSmall)
                    Text(b.category.label + " · " + b.recurrenceText(), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    val shown = if (b.variableAmount && b.paidTowardCurrent == 0.0) b.expectedAmount else if (b.completed) b.amount else b.remaining
                    Text((if (b.variableAmount && !b.completed) "~" else "") + fmtMoney(s.currency, shown),
                        style = MaterialTheme.typography.displaySmall.merge(Mono))
                    if (b.paidTowardCurrent > 0) Text("${fmtMoney(s.currency, b.paidTowardCurrent)} of ${fmtMoney(s.currency, b.amount)} paid",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    StatusPill(when (status) {
                        Status.PAID -> "All paid"; Status.PAUSED -> "Paused until ${fmtDate(b.pausedUntil!!)}"
                        else -> dueLong(b.dueDate)
                    }, fg, bg)
                }
            }
            if (!b.completed) item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { paying = true }, Modifier.weight(1f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = st.paid, contentColor = Color(0xFF06210F))) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Pay")
                    }
                    if (b.upiId.isNotBlank()) FilledTonalButton(onClick = {
                        if (!launchUpi(ctx, b, if (b.variableAmount) b.expectedAmount else b.remaining))
                            android.widget.Toast.makeText(ctx, "No UPI app found", android.widget.Toast.LENGTH_SHORT).show()
                        else paying = true
                    }, Modifier.weight(1f).height(52.dp)) { Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(6.dp)); Text("UPI") }
                    Box {
                        FilledTonalButton(onClick = { if (b.pausedUntil != null) store.resume(b.id) else pauseMenu = true },
                            Modifier.height(52.dp)) {
                            Icon(if (b.pausedUntil != null) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(4.dp)); Text(if (b.pausedUntil != null) "Resume" else "Pause")
                        }
                        DropdownMenu(pauseMenu, { pauseMenu = false }) {
                            listOf(1, 2, 3, 6).forEach { m ->
                                DropdownMenuItem(text = { Text("$m month${if (m > 1) "s" else ""}") }, onClick = {
                                    store.pause(b.id, LocalDate.now().plusMonths(m.toLong())); pauseMenu = false })
                            }
                            DropdownMenuItem(text = { Text("Until date…") }, onClick = {
                                pauseMenu = false
                                pickDate(ctx, LocalDate.now().plusMonths(1)) { store.pause(b.id, it) }
                            })
                        }
                    }
                }
            }
            item {
                SectionCard("Details") {
                    if (!b.completed) {
                        val next = b.nextDueDates(4).drop(1)
                        if (next.isNotEmpty()) RowItem(Icons.Default.Event, "Next due", next.joinToString(" · ") { fmtShort(it) })
                    }
                    RowItem(Icons.Default.Notifications, "Reminder",
                        if (!b.reminderEnabled) "Off" else (if (b.remindDaysBefore == 0) "On the day" else plural(b.remindDaysBefore, "day") + " before") +
                            " at " + LocalTime.of(b.remindHour, b.remindMinute).format(DateTimeFormatter.ofPattern("h:mm a")) +
                            (b.snoozedUntil?.let { " · snoozed to ${fmtShort(it.toLocalDate())}" } ?: ""))
                    if (b.variableAmount) RowItem(Icons.Default.Tune, "Variable amount", "Usual ~${fmtMoney(s.currency, b.expectedAmount)} (last 3 payments)")
                    if (b.upiId.isNotBlank()) RowItem(Icons.Default.QrCode, "UPI", b.upiName.ifBlank { b.upiId } + if (b.upiName.isNotBlank()) " · ${b.upiId}" else "")
                    val total = b.payments.filter { !it.skipped }.sumOf { it.amount }
                    RowItem(Icons.Default.Summarize, "Total paid", "${fmtMoney(s.currency, total)} across ${plural(b.payments.count { !it.skipped }, "payment")}")
                }
            }
            if (b.notes.isNotBlank() || b.attachments.isNotEmpty()) item {
                SectionCard("Notes & files") {
                    if (b.notes.isNotBlank()) Text(b.notes, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    b.attachments.forEach { path ->
                        RowItem(Icons.AutoMirrored.Filled.InsertDriveFile, File(path).name.takeLast(28), onClick = { openFile(ctx, path) })
                    }
                }
            }
            item {
                Text("PAYMENT HISTORY", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (b.payments.isEmpty()) item {
                Text("No payments yet", Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(b.payments.asReversed(), key = { it.id }) { p ->
                Surface(onClick = { editing = p }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.skipped) Icons.Default.SkipNext else Icons.Default.CheckCircle, null,
                            tint = if (p.skipped) st.paused else st.paid)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (p.skipped) "Skipped" else "Paid ${fmtDate(p.date)}", style = MaterialTheme.typography.bodyLarge)
                            Text("For ${fmtDate(p.dueDate)}" + if (p.note.isNotBlank()) " · ${p.note}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmtMoney(s.currency, p.amount), style = MaterialTheme.typography.titleMedium.merge(Mono),
                            textDecoration = if (p.skipped) TextDecoration.LineThrough else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentDialog(b: Bill, p: Payment, cur: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val store = rememberStore()
    var amt by remember { mutableStateOf(amountText(p.amount)) }
    var date by remember { mutableStateOf(p.date) }
    var note by remember { mutableStateOf(p.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete payment?") },
        text = { Text("The ${fmtMoney(cur, p.amount)} payment for ${fmtDate(p.dueDate)} will be removed. If it closed that bill, the bill becomes due again.") },
        confirmButton = { TextButton(onClick = { store.deletePayment(b.id, p.id); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit payment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("For due date ${fmtDate(p.dueDate)}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, prefix = { Text(cur) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            AssistChip(onClick = { pickDate(ctx, date) { date = it } }, label = { Text("Paid on ${fmtDate(date)}") },
                leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) })
            OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
        }
    }, confirmButton = {
        TextButton(onClick = {
            parseAmount(amt)?.let { store.editPayment(b.id, p.copy(amount = it, date = date, note = note)) }; onDismiss()
        }, enabled = parseAmount(amt) != null) { Text("Save") }
    }, dismissButton = {
        Row {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    })
}
