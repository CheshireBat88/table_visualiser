package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты справедливой раскладки расходов и долей сборов: доля каждого округляется
 * вверх до копейки, поэтому при неделимой сумме платят все одинаково, а итог
 * «подрастает» на несколько копеек.
 */
class FairSharesTest {

    private fun members(n: Int): List<String> = (1..n).map { "P$it" }

    @Test
    fun evenSplitWhenTotalDivisible() {
        val ms = members(13)
        val shares = fairExpenseShares(listOf(expense(1430.0, ms)), ms)
        assertEquals(13, shares.size)
        shares.values.forEach { assertEquals(110.0, it, 0.0) }
        assertEquals(1430.0, shares.values.sum(), 0.001)
    }

    @Test
    fun oddCentRoundsUpForEveryone() {
        val ms = members(13)
        val shares = fairExpenseShares(listOf(expense(1430.01, ms)), ms)
        // 1430.01 / 13 = 110.00077… → каждый платит 110.01, итого на 12 копеек больше.
        assertEquals(1430.13, shares.values.sum(), 0.001)
        shares.values.forEach { assertEquals(110.01, it, 0.0) }
    }

    @Test
    fun multipleExpensesWithDifferentCircles() {
        val all = members(5)
        val expenses = listOf(
            expense(100.0, listOf("P1", "P2", "P3")),
            expense(200.0, listOf("P2", "P3", "P4", "P5")),
            expense(1.0, all),
        )
        val shares = fairExpenseShares(expenses, all)
        // 100/3 → 33.34, 200/4 → 50.00, 1/5 → 0.20; итого 301.02.
        assertEquals(301.02, shares.values.sum(), 0.001)
        assertTrue(shares["P1"]!! in 33.53..33.54)
        shares.values.forEach { v ->
            val cents = Math.round(v * 100.0).toInt()
            assertEquals(v, cents / 100.0, 0.001)
        }
    }

    @Test
    fun expenseWithoutParticipantsSplitsOnAllMembers() {
        val all = members(4)
        val expenses = listOf(ExpenseData("2026-01-01", "Всем", 100.0, emptyList()))
        val shares = fairExpenseShares(expenses, all)
        assertEquals(4, shares.size)
        assertEquals(100.0, shares.values.sum(), 0.001)
        shares.values.forEach { assertEquals(25.0, it, 0.0) }
    }

    @Test
    fun emptyExpensesGiveNoShares() {
        val all = members(3)
        assertTrue(fairExpenseShares(emptyList(), all).isEmpty())
        assertTrue(fairExpenseShares(listOf(expense(0.0, all)), all).isEmpty())
    }

    @Test
    fun collection380On13RoundsUp() {
        val ms = members(13)
        val shares = fairCollectionShares(listOf(collection("Gift", 380.0, ms, shared = true)), ms)
        // 380 / 13 = 29.2307… → каждый платит 29.24, итого 380.12.
        assertEquals(380.12, shares.values.sum(), 0.001)
        assertEquals(13, shares.values.count { it == 29.24 })
    }

    @Test
    fun twoCollections380On13RoundUp() {
        val ms = members(13)
        val shares = fairCollectionShares(
            listOf(
                collection("A", 380.0, ms, shared = true),
                collection("B", 380.0, ms, shared = true),
            ),
            ms,
        )
        // По 29.24 с каждого сбора → 58.48 с человека, итого 760.24.
        assertEquals(760.24, shares.values.sum(), 0.001)
        shares.values.forEach { v -> assertEquals(58.48, v, 0.0) }
    }

    @Test
    fun standaloneCollectionsIgnoredInSplit() {
        val ms = members(5)
        val shared = collection("Общий", 100.0, ms, shared = true)
        val standalone = collection("Отдельный", 500.0, ms, shared = false)
        val shares = fairCollectionShares(listOf(shared, standalone), ms)
        // Остались только доли общего сбора.
        assertEquals(100.0, shares.values.sum(), 0.001)
    }

    private fun expense(amount: Double, participants: List<String>) =
        ExpenseData("2026-01-01", "Р", amount, participants)

    private fun collection(name: String, target: Double, participants: List<String>, shared: Boolean) =
        CollectionData(
            name = name,
            date = "2026-01-01",
            target = target,
            participants = participants,
            sharedWithBudget = shared,
        )
}