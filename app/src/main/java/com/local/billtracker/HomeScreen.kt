package com.local.billtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class HomeTab(val label: String) { UPCOMING("Upcoming"), OVERDUE("Overdue"), PAID("Paid"), ALL("All") }

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onAdd: () -> Unit, onSearch: () -> Unit) {
    val store = rememberStore()
    val bills by store.bills.collectAsState()
    val s = settingsState()
    val st = LocalStatus.current
    val today = LocalDate.now()
    var tab by rememberSaveable { mutableStateOf(HomeTab.UPCOMING) }
    var paying by remember { mutableStateOf<Bill?>(null) }

    val active = bills.filter { !it.completed }
    val overdue = active.filter { it.status(today) == Status.OVERDUE }.sortedBy { it.dueDate }
    val upcoming = active.filter { it.status(today) == Status.DUE }.sortedBy { it.dueDate }
    val paused = active.filter { it.isPaused(today) }
    val ym = YearMonth.from(today)
    val paidMonth = bills.flatMap { b -> b.payments.filter { !it.skipped && YearMonth.from(it.date) == ym }.map { b to it } }
        .sortedByDescending { it.second.date }
    val (paidTotal, dueTotal) = Reminders.monthTotals(bills)

    val failing = reminderChecks().filter { !it.ok }

    fun quickPay(b: Bill) { if (b.variableAmount || b.upiId.isNotBlank() || b.paidTowardCurrent > 0) paying = b else store.pay(b.id) }

    paying?.let { b -> PaySheet(b) { paying = null } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add bill") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
        },
        contentWindowInsets = WindowInsets.statusBars,
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                ScreenTitle("Bills", today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))) {
                    FilledTonalIconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Search") }
                }
            }
            item { HeroCard(s, ym, paidTotal, dueTotal) }
            if (failing.isNotEmpty()) item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Surface(onClick = { failing.first().fix(ctx) }, shape = MaterialTheme.shapes.medium, color = st.overdueBg) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsOff, null, tint = st.overdue); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Reminders may not arrive", color = st.overdue, fontWeight = FontWeight.SemiBold)
                            Text(failing.joinToString(" · ") { it.problem }.replaceFirstChar { it.uppercase() } + " — tap to fix",
                                style = MaterialTheme.typography.bodySmall, color = st.overdue)
                        }
                    }
                }
            }
            if (overdue.isNotEmpty() && tab != HomeTab.OVERDUE) item {
                Surface(onClick = { tab = HomeTab.OVERDUE }, shape = MaterialTheme.shapes.medium, color = st.overdueBg) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = st.overdue); Spacer(Modifier.width(12.dp))
                        Text("${overdue.size} overdue · ${fmtMoney(s.currency, overdue.sumOf { it.remaining })}",
                            Modifier.weight(1f), color = st.overdue, fontWeight = FontWeight.SemiBold)
                        Text("Review", color = st.overdue, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTab.entries.forEach { t ->
                        val count = when (t) {
                            HomeTab.UPCOMING -> upcoming.size; HomeTab.OVERDUE -> overdue.size
                            HomeTab.PAID -> paidMonth.size; HomeTab.ALL -> bills.size
                        }
                        FilterChip(tab == t, { tab = t }, label = { Text(if (count > 0) "${t.label} $count" else t.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            border = null, shape = CircleShape)
                    }
                }
            }

            when (tab) {
                HomeTab.UPCOMING -> {
                    if (upcoming.isEmpty() && paused.isEmpty()) item {
                        EmptyState(Icons.Default.TaskAlt, if (bills.isEmpty()) "No bills yet" else "Nothing coming up",
                            if (bills.isEmpty()) "Tap “Add bill” to start tracking" else "You're all caught up")
                    }
                    val groups = upcoming.groupBy { b ->
                        val n = ChronoUnit.DAYS.between(today, b.dueDate)
                        when {
                            n == 0L -> "Today"; n == 1L -> "Tomorrow"; n < 7 -> "This week"
                            YearMonth.from(b.dueDate) == ym -> "Later this month"
                            else -> "Later"
                        }
                    }
                    groups.forEach { (label, list) ->
                        item(key = "h-$label") { GroupLabel(label, list.sumOf { if (it.variableAmount) it.expectedAmount else it.remaining }, s.currency) }
                        items(list, key = { it.id }) { b -> BillCard(b, s.currency, { onOpen(b.id) }, onPay = { quickPay(b) }) }
                    }
                    if (paused.isNotEmpty()) {
                        item { GroupLabel("Paused", null, s.currency) }
                        items(paused, key = { it.id }) { b -> BillCard(b, s.currency, { onOpen(b.id) }) }
                    }
                }
                HomeTab.OVERDUE -> {
                    if (overdue.isEmpty()) item { EmptyState(Icons.Default.Verified, "No overdue bills", "Nice work") }
                    items(overdue, key = { it.id }) { b -> BillCard(b, s.currency, { onOpen(b.id) }, onPay = { quickPay(b) }) }
                }
                HomeTab.PAID -> {
                    if (paidMonth.isEmpty()) item { EmptyState(Icons.Default.Payments, "Nothing paid yet", "Payments this month show up here") }
                    items(paidMonth, key = { it.second.id }) { (b, p) ->
                        BillCard(b, s.currency, { onOpen(b.id) }, overrideSubtitle = "Paid ${fmtShort(p.date)}", overrideAmount = p.amount)
                    }
                }
                HomeTab.ALL -> {
                    items(bills.sortedWith(compareBy({ it.completed }, { it.dueDate })), key = { it.id }) { b ->
                        BillCard(b, s.currency, { onOpen(b.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(label: String, total: Double?, cur: String) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        total?.let { Text(fmtMoney(cur, it), style = MaterialTheme.typography.labelLarge.merge(Mono), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HeroCard(s: Settings, ym: YearMonth, paid: Double, due: Double) {
    val total = paid + due
    val onHero = Color.White
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
        .background(Brush.linearGradient(listOf(Color(0xFF4F7CFF), Color(0xFF2A3FB0))))) {
        Column(Modifier.padding(20.dp)) {
            Text(ym.format(DateTimeFormatter.ofPattern("MMMM")).uppercase(), color = onHero.copy(alpha = .75f),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(fmtMoney(s.currency, due), color = onHero, style = MaterialTheme.typography.displaySmall.merge(Mono))
            Text("left to pay", color = onHero.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            val frac = if (total > 0) (paid / total).toFloat() else 0f
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(onHero.copy(alpha = .22f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(CircleShape).background(Color(0xFF7CF5B0)))
            }
            Spacer(Modifier.height(10.dp))
            Row {
                HeroStat("Paid", fmtMoney(s.currency, paid), Modifier.weight(1f))
                HeroStat("Month total", fmtMoney(s.currency, total), Modifier.weight(1f))
                if (s.monthlyBudget > 0) {
                    val left = s.monthlyBudget - total
                    HeroStat(if (left >= 0) "Under budget" else "Over budget", fmtMoney(s.currency, kotlin.math.abs(left)), Modifier.weight(1f),
                        if (left >= 0) onHero else Color(0xFFFFB4B4))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier, color: Color = Color.White) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.merge(Mono))
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val store = rememberStore()
    val bills by store.bills.collectAsState()
    val s = settingsState()
    var q by rememberSaveable { mutableStateOf("") }
    var cat by remember { mutableStateOf<Category?>(null) }
    val res = bills.filter { b ->
        (q.isBlank() || b.name.contains(q, true) || b.notes.contains(q, true) || b.category.label.contains(q, true) ||
            b.payments.any { it.note.contains(q, true) }) && (cat == null || b.category == cat)
    }.sortedWith(compareBy({ it.completed }, { it.dueDate }))
    Scaffold(topBar = { BackTopBar("Search", onBack) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Name, note or category") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = CircleShape)
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(cat == null, { cat = null }, label = { Text("All") }) }
                    items(Category.entries) { c ->
                        FilterChip(cat == c, { cat = if (cat == c) null else c }, label = { Text(c.label) },
                            leadingIcon = { Icon(c.icon(), null, Modifier.size(16.dp), tint = Color(c.color)) })
                    }
                }
            }
            if (res.isEmpty()) item { EmptyState(Icons.Default.SearchOff, "No matches", "Try a different word") }
            items(res, key = { it.id }) { b -> BillCard(b, s.currency, { onOpen(b.id) }) }
        }
    }
}
