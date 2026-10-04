package com.local.billtracker

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun rememberStore(): Store { val ctx = LocalContext.current; return remember { Store.get(ctx) } }

@Composable
fun settingsState(): Settings { val s by rememberStore().settings.collectAsState(); return s }

/** Increments every time the screen resumes, e.g. after returning from a system settings page. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun reminderChecks(): List<ReminderHealth.Check> {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    return remember(tick) { ReminderHealth.checks(ctx) }
}

fun Category.icon(): ImageVector = when (this) {
    Category.UTILITIES -> Icons.Default.Bolt
    Category.RENT -> Icons.Default.House
    Category.INTERNET -> Icons.Default.Wifi
    Category.SUBSCRIPTION -> Icons.Default.Subscriptions
    Category.INSURANCE -> Icons.Default.Shield
    Category.LOAN -> Icons.Default.AccountBalance
    Category.CREDIT_CARD -> Icons.Default.CreditCard
    Category.TRANSPORT -> Icons.Default.DirectionsCar
    Category.HEALTH -> Icons.Default.LocalHospital
    Category.EDUCATION -> Icons.Default.School
    Category.OTHER -> Icons.Default.Category
}

@Composable
fun CategoryAvatar(c: Category, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Color(c.color).copy(alpha = .16f)), Alignment.Center) {
        Icon(c.icon(), null, Modifier.size(size * 0.5f), tint = Color(c.color))
    }
}

enum class Status { DUE, OVERDUE, PAID, PAUSED }

fun Bill.status(today: LocalDate = LocalDate.now()) = when {
    completed -> Status.PAID
    isPaused(today) -> Status.PAUSED
    dueDate.isBefore(today) -> Status.OVERDUE
    else -> Status.DUE
}

fun dueLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val n = ChronoUnit.DAYS.between(today, d)
    return when {
        n == 0L -> "Due today"
        n == 1L -> "Due tomorrow"
        n in 2..6 -> "Due ${d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
        n > 0 -> "Due ${fmtShort(d)}"
        n == -1L -> "1 day overdue"
        else -> "${-n} days overdue"
    }
}

/** "Due tomorrow · 6 Oct 2026" when relative wording adds something, else just "Due 5 Nov 2026". */
fun dueLong(d: LocalDate): String {
    val n = ChronoUnit.DAYS.between(LocalDate.now(), d)
    return if (n in 0..6 || n < 0) "${dueLabel(d)} · ${fmtDate(d)}" else "Due ${fmtDate(d)}"
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

@Composable
fun StatusPill(text: String, fg: Color, bg: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun BillCard(b: Bill, cur: String, onClick: () -> Unit, onPay: (() -> Unit)? = null, overrideSubtitle: String? = null, overrideAmount: Double? = null) {
    val st = LocalStatus.current
    val status = b.status()
    val (fg, bg) = when (status) {
        Status.OVERDUE -> st.overdue to st.overdueBg
        Status.PAID -> st.paid to st.paidBg
        Status.PAUSED -> st.paused to MaterialTheme.colorScheme.surfaceContainerHighest
        Status.DUE -> st.due to st.dueBg
    }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(b.category)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(overrideSubtitle ?: when (status) {
                            Status.PAUSED -> "Paused until ${fmtShort(b.pausedUntil!!)}"
                            Status.PAID -> "Done"
                            else -> dueLabel(b.dueDate)
                        }, fg, bg)
                        if (b.recurring && overrideSubtitle == null)
                            Text(b.recurrenceText(), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val amt = overrideAmount ?: if (b.variableAmount && b.paidTowardCurrent == 0.0) b.expectedAmount else b.remaining
                    Text((if (b.variableAmount && overrideAmount == null) "~" else "") + fmtMoney(cur, amt),
                        style = MaterialTheme.typography.titleMedium.merge(Mono))
                    if (b.paidTowardCurrent > 0 && overrideAmount == null)
                        Text("of ${fmtMoney(cur, b.amount)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onPay != null) {
                    Spacer(Modifier.width(10.dp))
                    FilledTonalIconButton(onClick = onPay, colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = st.paidBg, contentColor = st.paid)) { Icon(Icons.Default.Check, "Pay") }
                }
            }
            if (b.paidTowardCurrent > 0 && !b.completed) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { (b.paidTowardCurrent / b.amount).toFloat().coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = st.paid,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {}, gapSize = 0.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        trailing()
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        title?.let {
            Text(it.uppercase(), Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun RowItem(icon: ImageVector?, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null,
            trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Divider16() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) =
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()

fun openFile(ctx: Context, path: String) {
    val f = File(path)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
}

/** Opens the UPI app chooser with the payee and amount filled in. Returns false if no UPI app is installed. */
fun launchUpi(ctx: Context, b: Bill, amount: Double): Boolean {
    val uri = Uri.Builder().scheme("upi").authority("pay")
        .appendQueryParameter("pa", b.upiId.trim())
        .appendQueryParameter("pn", b.upiName.ifBlank { b.name })
        .appendQueryParameter("am", String.format(java.util.Locale.US, "%.2f", amount))
        .appendQueryParameter("cu", "INR")
        .appendQueryParameter("tn", b.name.take(40))
        .build()
    val i = Intent(Intent.ACTION_VIEW, uri)
    if (i.resolveActivity(ctx.packageManager) == null) return false
    ctx.startActivity(Intent.createChooser(i, "Pay with"))
    return true
}

fun parseAmount(s: String): Double? = s.replace(",", "").trim().toDoubleOrNull()?.takeIf { it >= 0 }
fun amountText(v: Double): String = if (v == 0.0) "" else v.toBigDecimal().stripTrailingZeros().toPlainString()

/** Bottom sheet to record a payment: amount, date, note, partial. Also offers UPI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaySheet(b: Bill, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val store = rememberStore()
    val s = settingsState()
    val st = LocalStatus.current
    val suggested = if (b.variableAmount && b.paidTowardCurrent == 0.0) b.expectedAmount else b.remaining.takeIf { it > 0 } ?: b.amount
    var amt by remember { mutableStateOf(amountText(Math.round(suggested * 100) / 100.0)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    val value = parseAmount(amt)
    val isPartial = !b.variableAmount && value != null && value < b.remaining - 0.005
    var keepOpen by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryAvatar(b.category, 40.dp); Spacer(Modifier.width(12.dp))
                Column {
                    Text("Pay ${b.name}", style = MaterialTheme.typography.titleLarge)
                    Text(dueLong(b.dueDate), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(amt, { amt = it }, Modifier.fillMaxWidth(), label = { Text("Amount paid") },
                prefix = { Text(s.currency) }, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall.merge(Mono),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    when {
                        b.variableAmount -> Text("Variable bill · usual ~${fmtMoney(s.currency, b.expectedAmount)}")
                        b.paidTowardCurrent > 0 -> Text("${fmtMoney(s.currency, b.paidTowardCurrent)} already paid · ${fmtMoney(s.currency, b.remaining)} left")
                        else -> Text("Bill amount ${fmtMoney(s.currency, b.amount)}")
                    }
                })
            if (isPartial) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(12.dp)) {
                    Text("Less than the amount due", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(keepOpen, { keepOpen = true })
                        Text("Partial payment — keep ${fmtMoney(s.currency, b.remaining - (value ?: 0.0))} due", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!keepOpen, { keepOpen = false })
                        Text("Settled in full (e.g. discount)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistChip(onClick = { pickDate(ctx, date) { date = it } }, label = { Text("Paid ${if (date == LocalDate.now()) "today" else fmtDate(date)}") },
                    leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) })
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, singleLine = true)

            if (b.upiId.isNotBlank()) OutlinedButton(onClick = {
                if (!launchUpi(ctx, b, value ?: suggested))
                    android.widget.Toast.makeText(ctx, "No UPI app found", android.widget.Toast.LENGTH_SHORT).show()
            }, Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.QrCode, null); Spacer(Modifier.width(8.dp)); Text("Open UPI app to pay ${b.upiId}")
            }
            Button(onClick = {
                store.pay(b.id, value!!, date, note, settle = !(isPartial && keepOpen)); onDismiss()
            }, enabled = value != null, modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = st.paid, contentColor = Color(0xFF06210F))) {
                Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp))
                Text(if (isPartial && keepOpen) "Record partial payment" else "Mark as paid", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
