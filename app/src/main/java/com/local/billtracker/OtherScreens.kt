package com.local.billtracker

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// ================= Calendar =================
@Composable
fun CalendarScreen(onOpen: (String) -> Unit) {
    val store = rememberStore()
    val bills by store.bills.collectAsState()
    val s = settingsState()
    val st = LocalStatus.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    var sel by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()

    data class Ev(val bill: Bill, val paid: Boolean, val amount: Double)
    val events = remember(bills, month) {
        val m = mutableMapOf<LocalDate, MutableList<Ev>>()
        val first = month.atDay(1); val last = month.atEndOfMonth()
        bills.forEach { b ->
            b.payments.filter { !it.skipped }.groupBy { it.dueDate }.forEach { (d, ps) ->
                if (!d.isBefore(first) && !d.isAfter(last) && (d != b.dueDate || b.completed)) m.getOrPut(d) { mutableListOf() } += Ev(b, true, ps.sumOf { it.amount })
            }
            if (!b.isPaused()) b.nextDueDates(500).takeWhile { !it.isAfter(last) }.filter { !it.isBefore(first) }.forEach {
                m.getOrPut(it) { mutableListOf() } += Ev(b, false, if (it == b.dueDate) b.remaining else b.expectedAmount)
            }
        }
        m
    }
    val total = events.values.flatten().sumOf { it.amount }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Calendar", "Total ${fmtMoney(s.currency, total)} in ${month.format(DateTimeFormatter.ofPattern("MMMM"))}") }
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f).clickable {
                            month = YearMonth.now(); sel = today }, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
                    }
                    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                        Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    Spacer(Modifier.height(4.dp))
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    for (row in 0 until (offset + month.lengthOfMonth() + 6) / 7) Row {
                        for (col in 0..6) {
                            val day = row * 7 + col - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp), Alignment.Center) {
                                if (day in 1..month.lengthOfMonth()) {
                                    val d = month.atDay(day)
                                    val ev = events[d].orEmpty()
                                    val selected = d == sel
                                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .then(if (d == today && !selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                        .clickable { sel = d }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("$day", color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (ev.isNotEmpty()) FontWeight.Bold else FontWeight.Normal)
                                        Spacer(Modifier.height(3.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                                            ev.take(3).forEach { e ->
                                                val c = when { e.paid -> st.paid; d.isBefore(today) -> st.overdue; else -> st.due }
                                                Box(Modifier.size(6.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.onPrimary else c))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text(sel.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val dayTotal = events[sel].orEmpty().sumOf { it.amount }
                if (dayTotal > 0) Text(fmtMoney(s.currency, dayTotal), style = MaterialTheme.typography.titleMedium.merge(Mono))
            }
        }
        val dayEv = events[sel].orEmpty()
        if (dayEv.isEmpty()) item { Text("Nothing due", Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        dayEv.forEach { e ->
            item {
                BillCard(e.bill, s.currency, { onOpen(e.bill.id) }, overrideAmount = e.amount,
                    overrideSubtitle = when { e.paid -> "Paid"; sel == e.bill.dueDate -> null; else -> "Scheduled" })
            }
        }
    }
}

// ================= Insights =================
@Composable
fun StatsScreen() {
    val store = rememberStore()
    val bills by store.bills.collectAsState()
    val s = settingsState()
    val st = LocalStatus.current
    val today = LocalDate.now()
    val active = bills.filter { !it.completed && !it.isPaused() }
    val monthly = active.sumOf { it.monthlyCost }
    val pays = bills.flatMap { b -> b.payments.filter { !it.skipped }.map { b to it } }
    val ym = YearMonth.from(today)
    val months = (11 downTo 0).map { ym.minusMonths(it.toLong()) }
    fun paidIn(m: YearMonth) = pays.filter { YearMonth.from(it.second.date) == m }.sumOf { it.second.amount }
    val thisYearToDate = pays.filter { it.second.date.year == today.year && !it.second.date.isAfter(today) }.sumOf { it.second.amount }
    val lastYearToDate = pays.filter { it.second.date.year == today.year - 1 && !it.second.date.isAfter(today.minusYears(1)) }.sumOf { it.second.amount }
    val onTime = pays.count { !it.second.date.isAfter(it.second.dueDate) }
    val byCat = active.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.monthlyCost } }.filterValues { it > 0 }
        .toList().sortedByDescending { it.second }
    val yoyCat = Category.entries.mapNotNull { c ->
        val now = pays.filter { it.first.category == c && it.second.date.year == today.year && !it.second.date.isAfter(today) }.sumOf { it.second.amount }
        val prev = pays.filter { it.first.category == c && it.second.date.year == today.year - 1 && !it.second.date.isAfter(today.minusYears(1)) }.sumOf { it.second.amount }
        if (now == 0.0 && prev == 0.0) null else Triple(c, now, prev)
    }.sortedByDescending { it.second }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Insights", "Where your money goes") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Fixed costs / month", fmtMoney(s.currency, monthly), "${fmtMoney(s.currency, monthly * 12)} a year", Modifier.weight(1f))
                StatTile("Paid this month", fmtMoney(s.currency, paidIn(ym)),
                    if (s.monthlyBudget > 0) "of ${fmtMoney(s.currency, s.monthlyBudget)} budget" else "${plural(active.size, "active bill")}", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val delta = if (lastYearToDate > 0) (thisYearToDate - lastYearToDate) / lastYearToDate * 100 else null
                StatTile("Paid in ${today.year}", fmtMoney(s.currency, thisYearToDate),
                    delta?.let { "${if (it >= 0) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(it))}% vs ${today.year - 1}" } ?: "No data for ${today.year - 1}",
                    Modifier.weight(1f), delta?.let { if (it > 0) st.overdue else st.paid })
                StatTile("Paid on time", if (pays.isEmpty()) "—" else "${onTime * 100 / pays.size}%", plural(pays.size, "payment"), Modifier.weight(1f))
            }
        }
        item {
            ChartCard("Last 12 months") {
                val values = months.map { paidIn(it) }
                val prevValues = months.map { paidIn(it.minusYears(1)) }
                val max = (values + prevValues).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                val bar = MaterialTheme.colorScheme.primary
                val ghost = MaterialTheme.colorScheme.surfaceContainerHighest
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val slot = size.width / 12f
                    val w = slot * 0.62f
                    values.forEachIndexed { i, v ->
                        val x = i * slot + (slot - w) / 2
                        val hp = (prevValues[i] / max * size.height).toFloat()
                        drawRoundRect(ghost, Offset(x, size.height - hp), Size(w, hp), CornerRadius(6f))
                        val h = (v / max * size.height).toFloat()
                        drawRoundRect(bar, Offset(x + w * 0.18f, size.height - h), Size(w * 0.64f, h), CornerRadius(6f))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    months.forEach { Text(it.format(DateTimeFormatter.ofPattern("MMMMM")), Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(bar)); Text("  This year   ", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.size(10.dp).clip(CircleShape).background(ghost)); Text("  Year before", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            ChartCard("Monthly fixed costs by category") {
                if (byCat.isEmpty()) Text("Add recurring bills to see this", color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Stacked bar
                Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
                    byCat.forEach { (c, v) -> Box(Modifier.weight((v / monthly).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(Color(c.color))) }
                }
                Spacer(Modifier.height(12.dp))
                byCat.forEach { (c, v) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))); Spacer(Modifier.width(10.dp))
                        Text(c.label, Modifier.weight(1f))
                        Text("${(v / monthly * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                        Text(fmtMoney(s.currency, v), style = MaterialTheme.typography.bodyLarge.merge(Mono))
                    }
                }
            }
        }
        if (yoyCat.isNotEmpty()) item {
            ChartCard("Year over year (Jan – today)") {
                yoyCat.forEach { (c, now, prev) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(c.icon(), null, Modifier.size(18.dp), tint = Color(c.color)); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.label)
                            Text("${today.year - 1}: ${fmtMoney(s.currency, prev)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmtMoney(s.currency, now), style = MaterialTheme.typography.bodyLarge.merge(Mono))
                        Spacer(Modifier.width(8.dp))
                        if (prev > 0) {
                            val pct = (now - prev) / prev * 100
                            Icon(if (pct >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null,
                                Modifier.size(18.dp), tint = if (pct > 0) st.overdue else st.paid)
                            Text("${"%.0f".format(kotlin.math.abs(pct))}%", style = MaterialTheme.typography.labelMedium,
                                color = if (pct > 0) st.overdue else st.paid)
                        } else Text("new", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            ChartCard("Biggest recurring costs") {
                active.filter { it.recurring }.sortedByDescending { it.monthlyCost }.take(8).forEach { b ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryAvatar(b.category, 32.dp); Spacer(Modifier.width(10.dp))
                        Text(b.name, Modifier.weight(1f))
                        Text("${fmtMoney(s.currency, b.monthlyCost)}/mo", style = MaterialTheme.typography.bodyLarge.merge(Mono))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(14.dp)); content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier, subColor: Color? = null) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Mono), maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

// ================= Settings =================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val store = rememberStore()
    val s = settingsState()
    val scope = rememberCoroutineScope()
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    fun io(work: () -> Unit, ok: String) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching(work) }
        r.onSuccess { toast(ok) }.onFailure { toast("Failed: ${it.message}") }
    }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { io({ ctx.contentResolver.openOutputStream(it)!!.use(store::writeBackup) }, "Backup saved") }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { io({ ctx.contentResolver.openOutputStream(it)!!.use(store::writeCsv) }, "CSV exported") }
    }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        store.updateSettings(s.copy(autoBackupUri = uri.toString()))
        io({ AutoBackup.runNow(ctx) }, "Auto-backup on · first backup written")
    }
    confirmRestore?.let { uri ->
        AlertDialog(onDismissRequest = { confirmRestore = null }, title = { Text("Restore backup?") },
            text = { Text("This replaces all bills on this phone with the ones in the backup.") },
            confirmButton = { TextButton(onClick = {
                confirmRestore = null
                io({ store.restore(ctx.contentResolver.openInputStream(uri)!!) }, "Backup restored")
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } })
    }
    var editBudget by remember { mutableStateOf(false) }
    if (editBudget) {
        var t by remember { mutableStateOf(amountText(s.monthlyBudget)) }
        AlertDialog(onDismissRequest = { editBudget = false }, title = { Text("Monthly budget for bills") }, text = {
            OutlinedTextField(t, { t = it }, prefix = { Text(s.currency) }, singleLine = true, placeholder = { Text("0 = off") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }, confirmButton = { TextButton(onClick = {
            store.updateSettings(s.copy(monthlyBudget = parseAmount(t) ?: 0.0, budgetWarnedMonth = "")); editBudget = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editBudget = false }) { Text("Cancel") } })
    }
    var editCurrency by remember { mutableStateOf(false) }
    if (editCurrency) AlertDialog(onDismissRequest = { editCurrency = false }, title = { Text("Currency") }, text = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("₹", "$", "€", "£", "¥", "AED ", "Rs ").forEach { c ->
                FilterChip(s.currency == c, { store.updateSettings(s.copy(currency = c)); editCurrency = false }, label = { Text(c.trim()) })
            }
        }
    }, confirmButton = { TextButton(onClick = { editCurrency = false }) { Text("Close") } })

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenTitle("Settings") }
        item {
            SectionCard("Money") {
                RowItem(Icons.Default.Savings, "Monthly budget",
                    if (s.monthlyBudget > 0) fmtMoney(s.currency, s.monthlyBudget) + " · warns when bills exceed it" else "Off", onClick = { editBudget = true })
                Divider16()
                RowItem(Icons.Default.CurrencyRupee, "Currency", s.currency.trim(), onClick = { editCurrency = true })
            }
        }
        item {
            val checks = reminderChecks()
            SectionCard("Reminder health") {
                checks.forEachIndexed { i, c ->
                    if (i > 0) Divider16()
                    RowItem(if (c.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, c.title,
                        if (c.ok) "OK" else "Reminders may be late or missing",
                        trailing = { if (c.ok) Icon(Icons.Default.Check, null, tint = LocalStatus.current.paid)
                                     else FilledTonalButton(onClick = { c.fix(ctx) }) { Text(c.fixLabel) } })
                }
                Divider16()
                Text("On ColorOS also open App info → Battery usage and allow background activity / auto-launch.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard("Reminders") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Default for new bills", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 2, 3, 7).forEach { d -> FilterChip(s.defaultRemindDaysBefore == d,
                            { store.updateSettings(s.copy(defaultRemindDaysBefore = d)) }, label = { Text(if (d == 0) "On the day" else "${d}d before") }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 9, 12, 18, 21).forEach { h -> FilterChip(s.defaultRemindHour == h,
                            { store.updateSettings(s.copy(defaultRemindHour = h)) },
                            label = { Text(java.time.LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("h a"))) }) }
                    }
                }
                Divider16()
                RowItem(Icons.Default.NotificationsActive, "Daily reminders while overdue",
                    trailing = { Switch(s.overdueDailyNag, { store.updateSettings(s.copy(overdueDailyNag = it)) }) })
                Divider16()
                RowItem(Icons.Default.Send, "Send a test notification", onClick = { Reminders.sendTest(ctx) })
            }
        }
        item {
            SectionCard("Appearance & privacy") {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(s.theme == m, { store.updateSettings(s.copy(theme = m)) }, SegmentedButtonDefaults.itemShape(i, 3)) {
                                Text(m.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
                Divider16()
                RowItem(Icons.Default.Fingerprint, "App lock", "Fingerprint, face or screen lock",
                    trailing = { Switch(s.appLock, { on -> if (on) LockState.unlocked.value = true; store.updateSettings(s.copy(appLock = on)) }) })
            }
        }
        item {
            SectionCard("Backup — stays on your phone") {
                val last = s.lastAutoBackup.takeIf { it.isNotEmpty() }?.let { LocalDateTime.parse(it) }
                val lastText = "last ${last?.let { fmtDate(it.toLocalDate()) } ?: "not yet"}"
                RowItem(Icons.Default.Backup, "Automatic backup",
                    if (s.autoBackupUri.isEmpty()) {
                        if (AutoBackup.defaultAvailable) "Daily to ${AutoBackup.DEFAULT_FOLDER} · $lastText · tap to use another folder"
                        else "Off · pick a folder to turn on"
                    } else "Every ${s.autoBackupDays} days to your folder · $lastText · keeps 5",
                    onClick = { pickFolder.launch(null) },
                    trailing = if (s.autoBackupUri.isEmpty()) null else ({
                        TextButton(onClick = { store.updateSettings(s.copy(autoBackupUri = "")) }) { Text("Use default") }
                    }))
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.autoBackupUri.isNotEmpty()) listOf(1, 7, 30).forEach { d ->
                        FilterChip(s.autoBackupDays == d, { store.updateSettings(s.copy(autoBackupDays = d)) },
                            label = { Text(when (d) { 1 -> "Daily"; 7 -> "Weekly"; else -> "Monthly" }) })
                    }
                    if (s.autoBackupUri.isNotEmpty() || AutoBackup.defaultAvailable)
                        AssistChip(onClick = { io({ AutoBackup.runNow(ctx) }, "Backup written to ${AutoBackup.location(s)}") }, label = { Text("Back up now") })
                }
                Divider16()
                RowItem(Icons.Default.Archive, "Export backup", "ZIP with bills, payments and attachments",
                    onClick = { exportZip.launch("bill-tracker-${LocalDate.now()}.zip") })
                Divider16()
                RowItem(Icons.Default.Restore, "Restore from backup", "ZIP or older JSON backup", onClick = { restore.launch(arrayOf("application/zip", "application/json", "*/*")) })
                Divider16()
                RowItem(Icons.Default.TableChart, "Export payments to CSV", "Open in Excel or Google Sheets",
                    onClick = { exportCsv.launch("bill-payments-${LocalDate.now()}.csv") })
            }
        }
        item {
            Text("Bill Tracker 2.0 · all data stored only on this device", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
