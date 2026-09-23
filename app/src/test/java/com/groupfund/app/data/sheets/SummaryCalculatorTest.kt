package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * Тесты сводки бюджета: месяцы, балансы, сборы (общие и отдельные), расходы, итоги.
 */
class SummaryCalculatorTest {

    private val months = (1..3).map { MonthPlan(YearMonth.of(2026, it), required = true, amount = 100) }

    private fun member(name: String, join: String = "2026-01-01", off: List<String> = emptyList()) =
        MemberData(name = name, email = "", joinDate = join, active = true, removedAt = null, refund = null, offMonths = off)

    private fun group(
        window: List<MonthPlan>? = null,
        members: List<MemberData> = listOf(member("A"), member("B"), member("C")),
        payments: List<PaymentData> = emptyList(),
        expenses: List<ExpenseData> = emptyList(),
        collections: List<CollectionData> = emptyList(),
    ) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 100,
        currency = "RUB",
        createdAt = "2026-01-01",
        months = window ?: months,
        members = members,
        payments = payments,
        expenses = expenses,
        collections = collections,
    )

    private fun pay(name: String, amount: Double, comment: String = "") =
        PaymentData(name, "2026-01-15", amount, comment)

    private fun members(n: Int) = (1..n).map { member("P$it") }

    @Test
    fun basicMonthsExpenseAndBalance() {
        val g = group(
            payments = listOf(pay("A", 300.0)),
            expenses = listOf(ExpenseData("2026-01-01", "Аренда", 300.0, listOf("A", "B", "C"))),
        )
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(3, s.requiredIndices.size)
        assertEquals(listOf(100.0, 100.0, 100.0), a.perMonth)
        assertEquals(300.0, a.totalPaid, 0.001)
        assertEquals(100.0, a.expenseShare, 0.001)
        assertEquals(200.0, a.balance, 0.001)
        // Итоги: внесено 300, сборы 0, расходы 300, баланс 0.
        assertEquals(300.0, s.totals[3], 0.001)
        assertEquals(0.0, s.totals[4], 0.001)
        assertEquals(300.0, s.totals[5], 0.001)
        assertEquals(0.0, s.totals[6], 0.001)
    }

    @Test
    fun refundSubtractedFromPaid() {
        val g = group(payments = listOf(pay("A", 300.0), pay("A", 50.0, "Возврат: A")))
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(250.0, a.totalPaid, 0.001)
        assertEquals(250.0, a.balance, 0.001)
    }

    @Test
    fun sharedCollectionsRoundUpForEveryone() {
        val ms = members(13)
        val names = ms.map { it.name }
        val g = group(
            members = ms,
            collections = listOf(
                CollectionData("A", "2026-01-01", 380.0, names, emptyMap(), true),
                CollectionData("B", "2026-01-01", 380.0, names, emptyMap(), true),
            ),
        )
        val s = SummaryCalculator.compute(g)
        // Каждый сбор 380/13 → 29.24 с человека; два сбора → 58.48, итого 760.24.
        assertEquals(760.24, s.totals[4], 0.001)
        assertEquals(760.24, s.rows.sumOf { it.collectionShare }, 0.001)
        assertEquals(13, s.rows.count { it.collectionShare == 58.48 })
        // Расходы = сборы (расходов нет), баланс суммарно = −760.24.
        assertEquals(760.24, s.totals[5], 0.001)
        assertEquals(-760.24, round2(s.totals[6]), 0.001)
    }

    @Test
    fun sharedCollectionPaymentCountsAsIncome() {
        val g = group(
            members = listOf(member("A"), member("B")),
            collections = listOf(CollectionData("Общий", "2026-01-01", 100.0, listOf("A", "B"), emptyMap(), true)),
            payments = listOf(pay("A", 50.0, "Сбор: Общий")),
        )
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(50.0, a.totalPaid, 0.001)
        assertEquals(50.0, a.collectionShare, 0.001)
        assertEquals(0.0, a.balance, 0.001)
    }

    @Test
    fun standaloneCollectionIsIsolatedFromBudget() {
        val g = group(
            members = listOf(member("A"), member("B")),
            collections = listOf(
                CollectionData("Отдел", "2026-01-01", 100.0, listOf("A", "B"), emptyMap(), false),
                CollectionData("Общий", "2026-01-01", 100.0, listOf("A", "B"), emptyMap(), true),
            ),
            payments = listOf(
                pay("A", 50.0, "Сбор: Отдел"),        // внутри доли — не в бюджете
                pay("A", 30.0, "Возврат из сбора: Отдел"), // излишек — в бюджет
            ),
        )
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        val b = s.rows.first { it.member.name == "B" }
        assertEquals(30.0, a.totalPaid, 0.001)
        assertEquals(50.0, a.collectionShare, 0.001)
        assertEquals(50.0, a.expenseShare, 0.001)
        assertEquals(-20.0, a.balance, 0.001)
        assertEquals(0.0, b.totalPaid, 0.001)
        // Итоги: внесено 30, сборы 100, расходы 100, баланс −70.
        assertEquals(30.0, s.totals[3], 0.001)
        assertEquals(100.0, s.totals[4], 0.001)
        assertEquals(100.0, s.totals[5], 0.001)
        assertEquals(-70.0, s.totals[6], 0.001)
    }

    @Test
    fun orphanCollectionPaymentStaysIncome() {
        val g = group(payments = listOf(pay("A", 100.0, "Сбор: Виртуальный")))
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(100.0, a.totalPaid, 0.001)
        assertEquals(0.0, a.collectionShare, 0.001)
    }

    @Test
    fun memberJoiningInFebruaryPaysFromFebruary() {
        val d = member("D", join = "2026-02-10")
        val g = group(members = listOf(d), payments = listOf(pay("D", 200.0)))
        val s = SummaryCalculator.compute(g)
        val r = s.rows.first()
        assertEquals(listOf(0.0, 100.0, 100.0), r.perMonth)
        assertEquals(listOf(1, 2), r.requiredMonthIndices)
        assertEquals(YearMonth.of(2026, 3), r.paidThrough)
        assertEquals(2, r.fullyPaidRequiredCount)
    }

    @Test
    fun offMonthSkippedInCharges() {
        val e = member("E", off = listOf("2026-02"))
        val g = group(members = listOf(e), payments = listOf(pay("E", 200.0)))
        val s = SummaryCalculator.compute(g)
        val r = s.rows.first()
        assertEquals(listOf(100.0, 0.0, 100.0), r.perMonth)
        assertEquals(listOf(0, 2), r.requiredMonthIndices)
        assertEquals(YearMonth.of(2026, 3), r.paidThrough)
        assertEquals(2, r.fullyPaidRequiredCount)
    }

    @Test
    fun partialMonthStopsPaidThrough() {
        val g = group(payments = listOf(pay("A", 120.0)))
        val s = SummaryCalculator.compute(g)
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(listOf(100.0, 20.0, 0.0), a.perMonth)
        assertEquals(YearMonth.of(2026, 1), a.paidThrough)
        assertEquals(1, a.fullyPaidRequiredCount)
    }

    @Test
    fun summaryRowsLayout() {
        val g = group(
            window = months.take(2),
            payments = listOf(pay("A", 200.0)),
        )
        val s = SummaryCalculator.compute(g)
        val rows = SummaryCalculator.summaryRows(g, s)
        assertEquals(listOf<Any?>("Тест"), rows[0])
        assertEquals(
            listOf<Any?>("Участник", "2026-01", "2026-02", "Внесено", "Сборы", "Доля расходов", "Баланс"),
            rows[2],
        )
        assertEquals(
            listOf<Any?>("A", "100", "100", "200", "0", "0", "200"),
            rows[3],
        )
        val totalRow = rows.first { it.getOrNull(0) == "Итого" }
        assertEquals(
            listOf<Any?>("Итого", "100", "100", "200", "0", "0", "200"),
            totalRow,
        )
        assertTrue(rows.any { row -> row.getOrNull(0) == "РАСХОДЫ: по дате, сумме и кругу участников на момент записи" })
    }

    @Test
    fun removedMemberAppearsInExcludedSection() {
        val exc = member("A").copy(active = false, removedAt = "2026-02-01", refund = 30.0)
        val g = group(
            members = listOf(member("B"), exc),
            payments = listOf(pay("A", 100.0), pay("B", 100.0)),
            expenses = listOf(ExpenseData("2026-01-01", "Еда", 100.0, listOf("A", "B"))),
        )
        val s = SummaryCalculator.compute(g)
        val rows = SummaryCalculator.summaryRows(g, s)
        val excRow = rows.lastOrNull { it.getOrNull(0) == "A" }
        assertEquals(listOf<Any?>("A", "100", "50", "50"), excRow)
    }
}