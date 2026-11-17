package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Проверка применения взноса по сбору: переплата не засчитывается в сбор. */
class ApplyContributionTest {

    private fun member(name: String) =
        MemberData(name = name, email = "", joinDate = "2026-01-01", active = true, removedAt = null, refund = null)

    private fun group(contrib: Map<String, Double> = emptyMap()) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 100,
        currency = "RUB",
        createdAt = "2026-01-01",
        months = (1..12).map { MonthPlan(YearMonth.of(2026, it), true, 100) },
        members = listOf(member("A"), member("B")),
        payments = emptyList(),
        expenses = emptyList(),
        collections = listOf(
            CollectionData("Gift", "2026-01-01", 223.0, listOf("A", "B"), contrib, true),
        ),
    )

    @Test
    fun overpaymentIsNotCountedIntoCollection() {
        val g = group()
        val (collections, payments) = applyContribution(
            g.collections, g.payments, "Gift", "A", 1120.0,
        )
        val c = collections.first()
        // Доля 223/2 = 111.5 → в сбор засчитывается ровно доля.
        assertEquals(111.5, c.countedContribution("A"), 0.0)
        // Излишек уходит платёжом-возвратом и не входит в сбор.
        val paid = payments.filter { it.member == "A" && it.comment == "Сбор: Gift" }.sumOf { it.amount }
        val returned = payments.filter { it.member == "A" && it.comment == "Возврат из сбора: Gift" }.sumOf { it.amount }
        assertEquals(111.5, paid, 0.0)
        assertEquals(1008.5, returned, 0.0)

        val s = SummaryCalculator.compute(g.copy(collections = collections, payments = payments))
        val a = s.rows.first { it.member.name == "A" }
        assertEquals(111.5, a.collectionShare, 0.0)
        assertEquals(1008.5, a.balance, 0.0)
        assertTrue(a.perMonth.sum() >= 1008.5 - 0.001)
    }

    @Test
    fun zeroShareCollectionNeverWritesZeroRow() {
        // Вырожденный сбор: цель 0 → доля 0. Строка «Сбор: Gift» на 0 только
        // мусорила бы в листе, а деньги должны остаться обычным взносом.
        val zero = CollectionData("Gift", "2026-01-01", 0.0, listOf("A", "B"), emptyMap(), true)
        val (collections, payments) = applyContribution(
            listOf(zero), emptyList(), "Gift", "A", 300.0,
        )
        assertTrue(payments.none { it.comment.startsWith(SummaryCalculator.COLLECTION_TAG_PREFIX) })
        assertEquals(300.0, payments.single().amount, 0.0)
        assertEquals("", payments.single().comment)
        // Вклад в сбор не изменился: деньги ушли в месячный баланс.
        assertEquals(0.0, collections.first().contrib["A"] ?: 0.0, 0.0)
    }
}
