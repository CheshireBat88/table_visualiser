package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Тесты сводки за период (неделя/месяц/произвольный) и помесячных трат
 * в «Моей информации».
 */
class PeriodStatisticsTest {

    private fun p(member: String, date: String, amount: Double, comment: String = "") =
        PaymentData(member, date, amount, comment)

    private fun e(date: String, amount: Double, participants: List<String> = emptyList()) =
        ExpenseData(date, "Р", amount, participants)

    private fun c(name: String, date: String, target: Double, participants: List<String>, shared: Boolean) =
        CollectionData(name, date, target, participants, sharedWithBudget = shared)

    private val members = listOf("Аня", "Боря", "Витя")

    private fun group(
        payments: List<PaymentData> = emptyList(),
        expenses: List<ExpenseData> = emptyList(),
        collections: List<CollectionData> = emptyList(),
        months: List<MonthPlan> = emptyList(),
    ): GroupData = GroupData(
        spreadsheetId = "id",
        title = "Г",
        baseAmount = 100,
        currency = "RUB",
        createdAt = "2026-01-01",
        months = months,
        members = members.map { MemberData(it, "", "2026-01-01", true, null, null) },
        payments = payments,
        expenses = expenses,
        collections = collections,
    )

    private val jan = LocalDate.of(2026, 1, 1)
    private val janEnd = LocalDate.of(2026, 1, 31)

    @Test
    fun expensesFilteredByPeriod() {
        val g = group(expenses = listOf(
            e("2026-01-10", 300.0, members),
            e("2026-02-10", 900.0, members),
        ))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        assertEquals(300.0, s.expensesTotal, 0.0)
        assertEquals(1, s.expenses.size)
        assertEquals("2026-01-10", s.expenses.single().date)
    }

    @Test
    fun incomeExcludesRefundsAndOffBudgetContributions() {
        val standalone = c("Подарок", "2026-01-05", 500.0, members, shared = false)
        val g = group(payments = listOf(
            p("Аня", "2026-01-05", 1000.0),
            p("Боря", "2026-01-06", 200.0, "Сбор: Подарок"),
            p("Витя", "2026-01-07", 50.0, "Возврат: …"),
            p("Аня", "2026-02-01", 500.0),
        ), collections = listOf(standalone))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        // Учтены только первый платеж (1000) и взнос в общий бюджет нет —
        // взнос в «Подарок» и возврат исключены.
        assertEquals(1000.0, s.incomeTotal, 0.0)
        assertEquals(1, s.income.size)
    }

    @Test
    fun offBudgetOnlyStandaloneCollectionsInPeriod() {
        val g = group(collections = listOf(
            c("Подарок", "2026-01-05", 500.0, members, shared = false),
            c("Бензин", "2026-02-05", 700.0, members, shared = false),
            c("Общий", "2026-01-06", 300.0, members, shared = true),
        ))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        assertEquals(500.0, s.offBudgetTotal, 0.0)
        assertEquals(listOf("Подарок"), s.offBudgetCollections.map { it.name })
    }

    @Test
    fun memberSharesUseFairCeilingRounding() {
        val g = group(expenses = listOf(e("2026-01-10", 100.0, members)))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        // 100 / 3 = 33.34 (вверх до копейки) на каждого.
        s.members.forEach { assertEquals(33.34, it.expenseShare, 0.0) }
        s.members.forEach { assertEquals(0.0, it.offBudgetShare, 0.0) }
        assertEquals(100.02, s.members.sumOf { it.expenseShare }, 0.001)
    }

    @Test
    fun participantCircleRestrictsShares() {
        val g = group(expenses = listOf(e("2026-01-10", 200.0, listOf("Аня", "Боря"))))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        assertEquals(100.0, s.members.first { it.name == "Аня" }.expenseShare, 0.0)
        assertEquals(100.0, s.members.first { it.name == "Боря" }.expenseShare, 0.0)
        assertEquals(0.0, s.members.first { it.name == "Витя" }.expenseShare, 0.0)
    }

    @Test
    fun offBudgetSharesAppearForParticipants() {
        val g = group(collections = listOf(
            c("Подарок", "2026-01-05", 300.0, listOf("Аня", "Боря"), shared = false),
        ))
        val s = PeriodStatistics.compute(g, jan, janEnd)
        val anya = s.members.first { it.name == "Аня" }
        val vitya = s.members.first { it.name == "Витя" }
        assertEquals(150.0, anya.offBudgetShare, 0.0)
        assertEquals(0.0, vitya.offBudgetShare, 0.0)
    }

    @Test
    fun deltaIsIncomeMinusSpending() {
        val g = group(
            payments = listOf(p("Аня", "2026-01-05", 1000.0)),
            expenses = listOf(e("2026-01-10", 300.0, members)),
        )
        val s = PeriodStatistics.compute(g, jan, janEnd)
        // 1000 − 300 = 700.
        assertEquals(700.0, s.delta, 0.001)
    }

    @Test
    fun memberMonthlyExpensesAttributedByDateMonth() {
        val months = listOf(
            MonthPlan(YearMonth.of(2026, 1), true, 100),
            MonthPlan(YearMonth.of(2026, 2), true, 100),
        )
        val g = group(expenses = listOf(
            e("2026-01-10", 100.0, members),
            e("2026-02-10", 900.0, members),
        ), months = months)
        val shares = PeriodStatistics.memberExpenseSharesByMonth(g, members[1], months)
        assertEquals(33.34, shares[YearMonth.of(2026, 1)] ?: 0.0, 0.0)
        assertEquals(300.0, shares[YearMonth.of(2026, 2)] ?: 0.0, 0.0)
    }

    @Test
    fun memberMonthlyExpensesIncludeSharedCollections() {
        val months = listOf(MonthPlan(YearMonth.of(2026, 1), true, 100))
        val g = group(collections = listOf(
            c("Общий", "2026-01-06", 300.0, members, shared = true),
            c("Отдельный", "2026-01-07", 500.0, members, shared = false),
        ), months = months)
        val shares = PeriodStatistics.memberExpenseSharesByMonth(g, members[0], months)
        assertEquals(100.0, shares[YearMonth.of(2026, 1)] ?: 0.0, 0.0)
    }

    @Test
    fun offBudgetRowsListOnlyMyStandaloneCollections() {
        val g = group(collections = listOf(
            c("Подарок", "2026-01-05", 300.0, listOf("Аня", "Боря"), shared = false),
            c("Бензин", "2026-02-05", 200.0, listOf("Боря"), shared = false),
        ))
        val rows = PeriodStatistics.memberOffBudgetRows(g, "Аня")
        assertEquals(1, rows.size)
        assertEquals("Подарок", rows.single().name)
        assertEquals(YearMonth.of(2026, 1), rows.single().month)
        assertEquals(150.0, rows.single().share, 0.0)
    }

    @Test
    fun groupingMonthWithNoActivityShowsZero() {
        val months = listOf(MonthPlan(YearMonth.of(2026, 1), true, 100))
        val g = group(months = months)
        val shares = PeriodStatistics.memberExpenseSharesByMonth(g, members[0], months)
        assertTrue(shares.isEmpty())
    }
}