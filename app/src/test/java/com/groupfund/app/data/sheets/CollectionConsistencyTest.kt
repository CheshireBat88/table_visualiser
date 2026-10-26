package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты диагностики согласованности «Платежи ↔ Сборы». */
class CollectionConsistencyTest {

    private fun group(
        collections: List<CollectionData>,
        payments: List<PaymentData>,
        members: List<MemberData> = listOf(
            MemberData("A", "", "2026-01-01", true, null, null),
            MemberData("B", "", "2026-01-01", true, null, null),
            MemberData("C", "", "2026-01-01", true, null, null),
        ),
    ) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 0,
        currency = "RUB",
        createdAt = "2026-01-01",
        months = listOf(MonthPlan(java.time.YearMonth.of(2026, 1), true, 100)),
        payments = payments,
        expenses = emptyList(),
        collections = collections,
        members = members,
    )

    private fun coll(contrib: Map<String, Double>) = CollectionData(
        name = "Gift",
        date = "2026-01-01",
        target = 380.0,
        participants = listOf("A", "B", "C"),
        contrib = contrib,
        sharedWithBudget = true,
    )

    private fun member(name: String) =
        MemberData(name = name, email = "", joinDate = "2026-01-01", active = true, removedAt = null, refund = null, offMonths = emptyList())

    @Test
    fun `частичные взносы совпадают с платежами - без предупреждений`() {
        val g = group(
            collections = listOf(coll(mapOf("A" to 100.0, "B" to 100.0, "C" to 100.0))),
            payments = listOf(
                PaymentData("A", "2026-01-01", 100.0, "Сбор: Gift"),
                PaymentData("B", "2026-01-01", 100.0, "Сбор: Gift"),
                PaymentData("C", "2026-01-01", 100.0, "Сбор: Gift"),
            ),
        )
        assertTrue(CollectionConsistency.mismatches(g).isEmpty())
    }

    @Test
    fun `канонический вид при взносе выше доли - без предупреждений`() {
        // contrib 200 = доля 126.67 + излишек 73.33 → платежи консолидированы.
        val g = group(
            collections = listOf(coll(mapOf("A" to 200.0))),
            payments = listOf(
                PaymentData("A", "2026-01-01", 126.67, "Сбор: Gift"),
                PaymentData("A", "2026-01-01", 73.33, "Возврат из сбора: Gift"),
            ),
        )
        assertTrue(CollectionConsistency.mismatches(g).isEmpty())
    }

    @Test
    fun `несколько строк Сбор в пределах взноса тоже допустимы`() {
        val g = group(
            collections = listOf(coll(mapOf("A" to 150.0))),
            payments = listOf(
                PaymentData("A", "2026-01-01", 100.0, "Сбор: Gift"),
                PaymentData("A", "2026-01-02", 50.0, "Сбор: Gift"),
            ),
        )
        // Допустимый неконсолидированный вид.
        assertTrue(CollectionConsistency.mismatches(g).isEmpty())
    }

    @Test
    fun `платежи расходятся со взносом - предупреждение`() {
        val g = group(
            collections = listOf(coll(mapOf("A" to 200.0))),
            payments = listOf(
                PaymentData("A", "2026-01-01", 126.67, "Сбор: Gift"),
                // излишек 73.33 в платежах отсутствует
            ),
        )
        val warnings = CollectionConsistency.mismatches(g)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("Gift"))
        assertTrue(warnings[0].contains("A"))
    }

    @Test
    fun `платёж по несуществующему сбору - предупреждение`() {
        val g = group(
            collections = emptyList(),
            payments = listOf(PaymentData("A", "2026-01-01", 10.0, "Сбор: НетТакого")),
        )
        val warnings = CollectionConsistency.mismatches(g)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("НетТакого"))
    }

    @Test
    fun `платёж от участника не из сбора - предупреждение`() {
        val g = group(
            collections = listOf(coll(mapOf("A" to 100.0))),
            payments = listOf(PaymentData("Незнакомец", "2026-01-01", 100.0, "Сбор: Gift")),
        )
        val warnings = CollectionConsistency.mismatches(g)
        assertTrue(warnings.any { it.contains("Незнакомец") })
    }

    @Test
    fun `удаление сбора переводит взносы в отменённые`() {
        val payments = listOf(
            PaymentData("A", "2026-01-01", 100.0, "Сбор: Gift"),
            PaymentData("B", "2026-01-02", 50.0, "Сбор: Gift"),
            PaymentData("A", "2026-01-03", 20.0, "Возврат из сбора: Gift"),
            PaymentData("C", "2026-01-04", 300.0, ""),
        )
        val renamed = renameCollectionPaymentsToCancelled(payments, "Gift")
        assertEquals(4, renamed.size)
        // «Сбор: Gift» → «Отмененный сбор: Gift», суммы и даты сохранены
        assertEquals(PaymentData("A", "2026-01-01", 100.0, "Отмененный сбор: Gift"), renamed[0])
        assertEquals(PaymentData("B", "2026-01-02", 50.0, "Отмененный сбор: Gift"), renamed[1])
        // Излишек и обычный платёж не тронуты
        assertEquals(payments[2], renamed[2])
        assertEquals(payments[3], renamed[3])
    }

    @Test
    fun `у не вносившего участника ничего не добавляется`() {
        val payments = listOf(
            PaymentData("C", "2026-01-04", 300.0, ""),
            PaymentData("C", "2026-01-05", 50.0, "Сбор: Other"),
        )
        val renamed = renameCollectionPaymentsToCancelled(payments, "Gift")
        assertEquals(payments, renamed)
    }

    @Test
    fun `отменённый сбор возвращает деньги в месячные взносы`() {
        val g = group(
            members = listOf(member("A"), member("B"), member("C")),
            collections = listOf(coll(emptyMap())),
            payments = listOf(
                PaymentData("A", "2026-01-01", 100.0, "Сбор: Gift"),
                PaymentData("B", "2026-01-01", 50.0, "Сбор: Gift"),
            ),
        )
        val payments = renameCollectionPaymentsToCancelled(g.payments, "Gift")
        val s = SummaryCalculator.compute(g.copy(collections = emptyList(), payments = payments))
        val a = s.rows.first { it.member.name == "A" }
        val b = s.rows.first { it.member.name == "B" }
        // Всё внесённое вернулось в месячные взносы: A оплатил 100, B — 50; C не вносил.
        assertEquals(100.0, a.perMonth.sum(), 0.001)
        assertEquals(50.0, b.perMonth.sum(), 0.001)
        assertEquals(100.0, a.balance, 0.001)
        assertEquals(50.0, b.balance, 0.001)
        assertEquals(0.0, s.rows.first { it.member.name == "C" }.balance, 0.001)
    }
}