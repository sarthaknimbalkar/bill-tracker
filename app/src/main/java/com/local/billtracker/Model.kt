package com.local.billtracker

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class Category(val label: String, val color: Long) {
    UTILITIES("Utilities", 0xFFFFA726),
    RENT("Rent / Mortgage", 0xFF42A5F5),
    INTERNET("Internet & Phone", 0xFF26C6DA),
    SUBSCRIPTION("Subscriptions", 0xFFAB47BC),
    INSURANCE("Insurance", 0xFF66BB6A),
    LOAN("Loan / EMI", 0xFFEF5350),
    CREDIT_CARD("Credit Card", 0xFFEC407A),
    TRANSPORT("Transport", 0xFF8D6E63),
    HEALTH("Health", 0xFF29B6F6),
    EDUCATION("Education", 0xFF7E57C2),
    OTHER("Other", 0xFF90A4AE),
}

enum class RepeatUnit(val label: String) { DAY("Day(s)"), WEEK("Week(s)"), MONTH("Month(s)"), YEAR("Year(s)") }

enum class EndType { NEVER, ON_DATE, AFTER_COUNT }

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Payment(
    val id: String = UUID.randomUUID().toString(),
    val date: LocalDate,
    /** Due date of the occurrence this payment counts toward. */
    val dueDate: LocalDate,
    val amount: Double,
    val note: String = "",
    /** Skipped occurrence (no money paid), e.g. while paused or by "Skip". */
    val skipped: Boolean = false,
)

data class Bill(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Usual / expected amount. For variable bills this is an estimate. */
    val amount: Double = 0.0,
    val variableAmount: Boolean = false,
    val category: Category = Category.UTILITIES,
    /** Original first due date; occurrences are computed from it so the 31st doesn't drift. */
    val startDate: LocalDate = LocalDate.now(),
    /** Index of the current (unsettled) occurrence. */
    val occurrence: Int = 0,
    val recurring: Boolean = true,
    val repeatEvery: Int = 1,
    val repeatUnit: RepeatUnit = RepeatUnit.MONTH,
    val endType: EndType = EndType.NEVER,
    val endDate: LocalDate? = null,
    val endCount: Int? = null,
    /** No occurrences left (one-off paid, or series ended). */
    val completed: Boolean = false,
    val pausedUntil: LocalDate? = null,
    val reminderEnabled: Boolean = true,
    val remindDaysBefore: Int = 1,
    val remindHour: Int = 9,
    val remindMinute: Int = 0,
    val snoozedUntil: LocalDateTime? = null,
    val upiId: String = "",
    val upiName: String = "",
    val notes: String = "",
    val attachments: List<String> = emptyList(),
    val payments: List<Payment> = emptyList(),
) {
    fun occurrenceDate(index: Int): LocalDate = when (repeatUnit) {
        RepeatUnit.DAY -> startDate.plusDays(index.toLong() * repeatEvery)
        RepeatUnit.WEEK -> startDate.plusWeeks(index.toLong() * repeatEvery)
        RepeatUnit.MONTH -> startDate.plusMonths(index.toLong() * repeatEvery)
        RepeatUnit.YEAR -> startDate.plusYears(index.toLong() * repeatEvery)
    }

    val dueDate: LocalDate get() = if (recurring) occurrenceDate(occurrence) else startDate

    fun occurrenceExists(index: Int): Boolean {
        if (!recurring) return index == 0
        return when (endType) {
            EndType.NEVER -> true
            EndType.AFTER_COUNT -> index < (endCount ?: 1)
            EndType.ON_DATE -> endDate == null || !occurrenceDate(index).isAfter(endDate)
        }
    }

    fun isPaused(today: LocalDate = LocalDate.now()) = pausedUntil != null && today.isBefore(pausedUntil)

    /** Money already put toward the current occurrence (partial payments). */
    val paidTowardCurrent: Double
        get() = if (completed) 0.0 else payments.filter { it.dueDate == dueDate && !it.skipped }.sumOf { it.amount }

    val remaining: Double get() = (amount - paidTowardCurrent).coerceAtLeast(0.0)

    fun nextDueDates(count: Int): List<LocalDate> {
        if (completed) return emptyList()
        val out = mutableListOf<LocalDate>()
        var i = occurrence
        while (out.size < count && occurrenceExists(i) && i < occurrence + 1000) { out += if (recurring) occurrenceDate(i) else startDate; i++ }
        return out
    }

    private fun advance(): Bill {
        val next = occurrence + 1
        return if (recurring && occurrenceExists(next)) copy(occurrence = next, snoozedUntil = null)
        else copy(completed = true, snoozedUntil = null)
    }

    /** Record a payment. When [settle] is true the occurrence is closed even if it's less than [amount]. */
    fun pay(paid: Double, on: LocalDate = LocalDate.now(), note: String = "", settle: Boolean = true): Bill {
        val withPay = copy(payments = payments + Payment(date = on, dueDate = dueDate, amount = paid, note = note))
        return if (settle) withPay.advance() else withPay.copy(snoozedUntil = null)
    }

    fun skip(note: String = "Skipped"): Bill =
        copy(payments = payments + Payment(date = LocalDate.now(), dueDate = dueDate, amount = 0.0, note = note, skipped = true)).advance()

    /** Skip occurrences that fall inside a finished pause window. */
    fun resolvePause(today: LocalDate = LocalDate.now()): Bill {
        val until = pausedUntil ?: return this
        var b = this
        var guard = 0
        while (!b.completed && b.dueDate.isBefore(until) && guard++ < 500) b = b.skip("Paused")
        return if (!today.isBefore(until)) b.copy(pausedUntil = null) else b
    }

    /** End a pause early and restore the occurrences the pause skipped. */
    fun resume(): Bill {
        var b = copy(pausedUntil = null)
        while (b.payments.lastOrNull()?.let { it.skipped && it.note == "Paused" } == true) b = b.undoLastPayment()
        return b
    }

    fun undoLastPayment(): Bill {
        val last = payments.lastOrNull() ?: return this
        val rest = payments.dropLast(1)
        // If the last payment had closed an occurrence, reopen it.
        val reopened = if (completed || last.dueDate != dueDate) {
            val idx = if (recurring) occurrenceIndexOf(last.dueDate) else 0
            copy(occurrence = idx, completed = false)
        } else this
        return reopened.copy(payments = rest)
    }

    fun occurrenceIndexOf(date: LocalDate): Int {
        var i = 0
        while (i < 5000 && occurrenceDate(i).isBefore(date)) i++
        return i
    }

    /** Average monthly cost while active. */
    val monthlyCost: Double
        get() {
            if (!recurring || completed) return 0.0
            val perYear = when (repeatUnit) {
                RepeatUnit.DAY -> 365.0 / repeatEvery
                RepeatUnit.WEEK -> 52.1775 / repeatEvery
                RepeatUnit.MONTH -> 12.0 / repeatEvery
                RepeatUnit.YEAR -> 1.0 / repeatEvery
            }
            return expectedAmount * perYear / 12.0
        }

    /** For variable bills, the average of the last 3 real payments; otherwise the set amount. */
    val expectedAmount: Double
        get() {
            if (!variableAmount) return amount
            val recent = payments.filter { !it.skipped }.groupBy { it.dueDate }.values.map { l -> l.sumOf { it.amount } }.takeLast(3)
            return if (recent.isEmpty()) amount else recent.average()
        }

    fun recurrenceText(): String {
        if (!recurring) return "One-time"
        val base = if (repeatEvery == 1) when (repeatUnit) {
            RepeatUnit.DAY -> "Daily"; RepeatUnit.WEEK -> "Weekly"
            RepeatUnit.MONTH -> "Monthly"; RepeatUnit.YEAR -> "Yearly"
        } else "Every $repeatEvery ${repeatUnit.label.lowercase().replace("(s)", "s")}"
        return when (endType) {
            EndType.NEVER -> base
            EndType.ON_DATE -> "$base until ${endDate?.let(::fmtDate) ?: "-"}"
            EndType.AFTER_COUNT -> "$base · ${endCount ?: 1} times"
        }
    }
}

data class Settings(
    val currency: String = "₹",
    val upcomingWindowDays: Int = 7,
    val defaultRemindDaysBefore: Int = 1,
    val defaultRemindHour: Int = 9,
    val overdueDailyNag: Boolean = true,
    val monthlyBudget: Double = 0.0,
    val appLock: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoBackupUri: String = "",
    val autoBackupDays: Int = 7,
    val lastAutoBackup: String = "",
    val budgetWarnedMonth: String = "",
)

private val dateFmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
private val shortFmt = java.time.format.DateTimeFormatter.ofPattern("d MMM")
fun fmtDate(d: LocalDate): String = d.format(dateFmt)
fun fmtShort(d: LocalDate): String = if (d.year == LocalDate.now().year) d.format(shortFmt) else d.format(dateFmt)
fun fmtMoney(cur: String, v: Double): String =
    cur + if (v % 1.0 == 0.0) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%,.2f", v)
