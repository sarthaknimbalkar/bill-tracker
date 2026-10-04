package com.local.billtracker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BillTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun monthly(start: String, amount: Double = 1000.0) = Bill(name = "Rent", amount = amount, startDate = d(start))

    @Test fun monthEndDoesNotDrift() {
        val b = monthly("2026-01-31")
        assertEquals(d("2026-02-28"), b.occurrenceDate(1))
        assertEquals(d("2026-03-31"), b.occurrenceDate(2)) // computed from start, not from Feb 28
        assertEquals(d("2027-01-31"), b.occurrenceDate(12))
    }

    @Test fun payAdvancesToNextMonth() {
        val b = monthly("2026-10-05").pay(1000.0, d("2026-10-04"))
        assertEquals(d("2026-11-05"), b.dueDate)
        assertEquals(1, b.payments.size)
        assertEquals(d("2026-10-05"), b.payments[0].dueDate)
        assertFalse(b.completed)
    }

    @Test fun partialKeepsOccurrenceOpen() {
        var b = monthly("2026-10-05").pay(400.0, settle = false)
        assertEquals(d("2026-10-05"), b.dueDate)
        assertEquals(600.0, b.remaining, 0.001)
        b = b.pay(600.0)
        assertEquals(d("2026-11-05"), b.dueDate)
        assertEquals(1000.0, b.remaining, 0.001)
    }

    @Test fun undoReopensClosedOccurrence() {
        val b = monthly("2026-10-05").pay(1000.0).undoLastPayment()
        assertEquals(d("2026-10-05"), b.dueDate)
        assertTrue(b.payments.isEmpty())
    }

    @Test fun undoPartialStaysOnSameOccurrence() {
        val b = monthly("2026-10-05").pay(300.0, settle = false).undoLastPayment()
        assertEquals(d("2026-10-05"), b.dueDate)
        assertEquals(1000.0, b.remaining, 0.001)
    }

    @Test fun oneOffCompletesAndUndoes() {
        val b = Bill(name = "Repair", amount = 500.0, startDate = d("2026-10-10"), recurring = false)
        val paid = b.pay(500.0)
        assertTrue(paid.completed)
        assertTrue(paid.nextDueDates(3).isEmpty())
        val undone = paid.undoLastPayment()
        assertFalse(undone.completed)
        assertEquals(d("2026-10-10"), undone.dueDate)
    }

    @Test fun afterCountEndsSeries() {
        var b = monthly("2026-01-01").copy(endType = EndType.AFTER_COUNT, endCount = 2)
        b = b.pay(1000.0); assertFalse(b.completed)
        b = b.pay(1000.0); assertTrue(b.completed)
    }

    @Test fun onDateEndsSeries() {
        var b = monthly("2026-01-15").copy(endType = EndType.ON_DATE, endDate = d("2026-02-20"))
        assertEquals(listOf(d("2026-01-15"), d("2026-02-15")), b.nextDueDates(10))
        b = b.pay(1000.0).pay(1000.0)
        assertTrue(b.completed)
    }

    @Test fun pauseSkipsAndResumeRestores() {
        val b = monthly("2026-10-05").copy(pausedUntil = d("2026-12-05"))
        val paused = b.resolvePause(today = d("2026-10-06"))
        assertEquals(d("2026-12-05"), paused.dueDate)
        assertEquals(2, paused.payments.count { it.skipped })
        assertNotNull(paused.pausedUntil) // still in pause window
        val resumed = paused.resume()
        assertEquals(d("2026-10-05"), resumed.dueDate)
        assertTrue(resumed.payments.isEmpty())
        assertNull(resumed.pausedUntil)
    }

    @Test fun pauseClearsWhenWindowPasses() {
        val b = monthly("2026-10-05").copy(pausedUntil = d("2026-11-01")).resolvePause(today = d("2026-11-02"))
        assertNull(b.pausedUntil)
        assertEquals(d("2026-11-05"), b.dueDate)
    }

    @Test fun skipDoesNotCountAsMoney() {
        val b = monthly("2026-10-05").skip()
        assertEquals(d("2026-11-05"), b.dueDate)
        assertEquals(0.0, b.payments.filter { !it.skipped }.sumOf { it.amount }, 0.0)
    }

    @Test fun variableUsesAverageOfLastThree() {
        var b = monthly("2026-01-10", 1000.0).copy(variableAmount = true)
        b = b.pay(800.0).pay(1200.0).pay(1300.0).pay(1700.0)
        assertEquals((1200.0 + 1300.0 + 1700.0) / 3, b.expectedAmount, 0.001)
    }

    @Test fun monthlyCostNormalises() {
        assertEquals(1000.0, monthly("2026-01-01").monthlyCost, 0.001)
        assertEquals(1000.0, monthly("2026-01-01", 12000.0).copy(repeatUnit = RepeatUnit.YEAR).monthlyCost, 0.001)
        assertEquals(500.0, monthly("2026-01-01").copy(repeatEvery = 2).monthlyCost, 0.001)
        assertEquals(0.0, monthly("2026-01-01").copy(recurring = false).monthlyCost, 0.001)
    }

    @Test fun occurrenceIndexOfMatchesDates() {
        val b = monthly("2026-01-31")
        for (i in 0..24) assertEquals(i, b.occurrenceIndexOf(b.occurrenceDate(i)))
    }

    @Test fun anchoringBackwardsRoundTrips() {
        // EditBillScreen re-anchors startDate so occurrence N lands on the chosen due date.
        val b = monthly("2026-10-05").copy(occurrence = 3)
        val start = b.copy(startDate = d("2027-01-20")).occurrenceDate(-3)
        assertEquals(d("2027-01-20"), b.copy(startDate = start).dueDate)
    }
}
