package com.local.billtracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

class StoreJsonTest {
    private fun roundTrip(b: Bill) = Store.billFrom(JSONObject(Store.billJson(b).toString()))

    @Test fun everyFieldSurvivesSaveAndLoad() {
        val b = Bill(
            name = "Electricity \"BESCOM\"", amount = 1234.56, variableAmount = true, category = Category.UTILITIES,
            startDate = LocalDate.parse("2026-01-31"), occurrence = 4, recurring = true, repeatEvery = 2,
            repeatUnit = RepeatUnit.WEEK, endType = EndType.ON_DATE, endDate = LocalDate.parse("2027-12-31"), endCount = 9,
            completed = false, pausedUntil = LocalDate.parse("2026-11-01"), reminderEnabled = false,
            remindDaysBefore = 3, remindHour = 18, remindMinute = 45, snoozedUntil = LocalDateTime.parse("2026-10-06T09:00"),
            upiId = "biller@okhdfc", upiName = "BESCOM", notes = "Acct 123\nline 2 ₹", attachments = listOf("/data/x/a.jpg"),
            payments = listOf(
                Payment(date = LocalDate.parse("2026-02-01"), dueDate = LocalDate.parse("2026-01-31"), amount = 900.5, note = "UPI"),
                Payment(date = LocalDate.parse("2026-02-14"), dueDate = LocalDate.parse("2026-02-14"), amount = 0.0, note = "Paused", skipped = true),
            ),
        )
        assertEquals(b, roundTrip(b))
    }

    @Test fun nullablesSurvive() {
        val b = Bill(name = "x", startDate = LocalDate.parse("2026-05-05"), endDate = null, endCount = null, pausedUntil = null, snoozedUntil = null)
        assertEquals(b, roundTrip(b))
    }

    @Test fun settingsSurvive() {
        val s = Settings(currency = "$", upcomingWindowDays = 14, defaultRemindDaysBefore = 7, defaultRemindHour = 18,
            overdueDailyNag = false, monthlyBudget = 25000.0, appLock = true, theme = ThemeMode.LIGHT,
            autoBackupUri = "content://x/tree/y", autoBackupDays = 30, lastAutoBackup = "2026-10-05T03:00", budgetWarnedMonth = "2026-10")
        assertEquals(s, Store.settingsFrom(JSONObject(Store.settingsJson(s).toString())))
    }

    @Test fun v1FileMigratesToCurrentOccurrence() {
        // v1 stored the current due date and no occurrence index.
        val v1 = JSONObject("""{"id":"a","name":"Net","amount":999,"category":"INTERNET","dueDate":"2026-12-05",
            "startDate":"2026-10-05","recurring":true,"repeatEvery":1,"repeatUnit":"MONTH","endType":"NEVER",
            "payments":[{"date":"2026-10-05","dueDate":"2026-10-05","amount":999},{"date":"2026-11-04","dueDate":"2026-11-05","amount":999}]}""")
        val b = Store.billFrom(v1)
        assertEquals(2, b.occurrence)
        assertEquals(LocalDate.parse("2026-12-05"), b.dueDate)
        assertEquals(2, b.payments.size)
        assertTrue(b.payments.all { it.id.isNotEmpty() })
    }

    @Test fun unknownEnumsFallBackInsteadOfCrashing() {
        val o = Store.billJson(Bill(name = "x", startDate = LocalDate.parse("2026-01-01")))
            .put("category", "SOMETHING_NEW").put("repeatUnit", "DECADE").put("endType", "???")
        val b = Store.billFrom(o)
        assertEquals(Category.OTHER, b.category)
        assertEquals(RepeatUnit.MONTH, b.repeatUnit)
        assertEquals(EndType.NEVER, b.endType)
    }

    @Test fun realPhoneDataStillLoads() {
        val f = File("../data-backup/bills-before-resign.json").takeIf { it.exists() } ?: return
        val o = JSONObject(f.readText())
        val bills = o.getJSONArray("bills")
        for (i in 0 until bills.length()) {
            val b = Store.billFrom(bills.getJSONObject(i))
            assertEquals(b, roundTrip(b))
        }
        Store.settingsFrom(o.getJSONObject("settings"))
    }
}
