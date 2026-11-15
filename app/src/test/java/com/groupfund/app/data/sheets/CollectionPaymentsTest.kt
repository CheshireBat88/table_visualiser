package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты пересборки платежей по сбору и галочки «сдал» (N5, N10). */
class CollectionPaymentsTest {

    private fun collection(contrib: Map<String, Double> = emptyMap()) = CollectionData(
        name = "Gift",
        date = "2026-01-01",
        target = 200.0,
        participants = listOf("A", "B"),
        contrib = contrib,
        sharedWithBudget = true,
    )

    private fun payment(member: String, amount: Double, comment: String, date: String = "2026-01-01") =
        PaymentData(member, date, amount, comment)

    // ---------- N5: удаление платежа не оставляет возврат без сбора ----------

    @Test
    fun `collectionTagTarget понимает оба тега и терпит пробелы`() {
        assertEquals("Gift", collectionTagTarget("Сбор: Gift"))
        assertEquals("Gift", collectionTagTarget("Сбор:  Gift "))
        assertEquals("Gift", collectionTagTarget("Возврат из сбора: Gift"))
        assertNull(collectionTagTarget("Обычный платёж"))
        assertNull(collectionTagTarget("Возврат: 100"))
    }

    @Test
    fun `пересборка вклада выше доли даёт канонические строки`() {
        val payments = listOf(
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 50.0, "Возврат из сбора: Gift"),
            payment("B", 80.0, "Сбор: Gift"),
        )
        val out = rebuildMemberCollectionPayments(payments, "A", "Gift", 150.0, 100.0, "2026-02-01")
        assertEquals(3, out.size)
        assertEquals(100.0, out.first { it.member == "A" && it.comment == "Сбор: Gift" }.amount, 0.001)
        assertEquals(
            50.0,
            out.first { it.member == "A" && it.comment == "Возврат из сбора: Gift" }.amount,
            0.001,
        )
        // Чужие строки не тронуты.
        assertEquals(80.0, out.first { it.member == "B" }.amount, 0.001)
    }

    @Test
    fun `пересборка нулевого вклада убирает только этого участника`() {
        val payments = listOf(
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 20.0, "Возврат из сбора: Gift"),
            payment("B", 80.0, "Сбор: Gift"),
            payment("C", 30.0, "Возврат из сбора: Gift"),
        )
        val out = rebuildMemberCollectionPayments(payments, "A", "Gift", 0.0, 100.0, "2026-02-01")
        assertEquals(2, out.size)
        assertTrue(out.none { it.member == "A" })
        // Возврат исключённого C сохраняется: он уже зачислен в его месячный баланс.
        assertTrue(out.any { it.member == "C" && it.comment == "Возврат из сбора: Gift" })
    }

    @Test
    fun `после удаления строки Сбор возврат не остаётся без взноса`() {
        // Осталась только строка возврата (например, пользователь удалил «Сбор: Gift»).
        // Вклад пересчитывается из остатка: 50 → эта же сумма становится взносом,
        // возврата без сбора больше нет.
        val remaining = listOf(payment("A", 50.0, "Возврат из сбора: Gift"))
        val out = rebuildMemberCollectionPayments(remaining, "A", "Gift", 50.0, 100.0, "2026-02-01")
        assertEquals(1, out.size)
        assertEquals("Сбор: Gift", out.single().comment)
        assertEquals(50.0, out.single().amount, 0.001)
    }

    @Test
    fun `пересборка сопоставляет теги с пробелами вокруг имени`() {
        val payments = listOf(
            payment("A", 100.0, "Сбор:  Gift "),
            payment("A", 20.0, "Возврат из сбора:  Gift "),
        )
        val out = rebuildMemberCollectionPayments(payments, "A", "Gift", 120.0, 100.0, "2026-02-01")
        assertEquals(setOf("Сбор: Gift", "Возврат из сбора: Gift"), out.map { it.comment }.toSet())
    }

    // ---------- N10: галочка «сдал» ----------

    @Test
    fun `отметка сдал доводит вклад до доли`() {
        val c = collection(mapOf("A" to 40.0))
        val payments = listOf(payment("A", 40.0, "Сбор: Gift"))
        val (updated, newPayments) = setMemberPaid(c, "A", true, payments, "2026-02-01")
        assertEquals(100.0, updated.contributed("A"), 0.001)
        assertEquals(1, newPayments.size)
        assertEquals("Сбор: Gift", newPayments.single().comment)
        assertEquals(100.0, newPayments.single().amount, 0.001)
    }

    @Test
    fun `отметка сдал не уменьшает переплату`() {
        val c = collection(mapOf("A" to 150.0))
        val payments = listOf(
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 50.0, "Возврат из сбора: Gift"),
        )
        val (updated, newPayments) = setMemberPaid(c, "A", true, payments, "2026-02-01")
        // max(150, 100) — переплата и связанный с ней возврат сохраняются.
        assertEquals(150.0, updated.contributed("A"), 0.001)
        assertEquals(100.0, newPayments.first { it.comment == "Сбор: Gift" }.amount, 0.001)
        assertEquals(
            50.0,
            newPayments.first { it.comment == "Возврат из сбора: Gift" }.amount,
            0.001,
        )
    }

    @Test
    fun `снятие отметки у переплатившего оставляет излишек обычным платежом`() {
        val c = collection(mapOf("A" to 150.0, "B" to 100.0))
        val payments = listOf(
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 50.0, "Возврат из сбора: Gift"),
            payment("B", 100.0, "Сбор: Gift"),
        )
        val (updated, newPayments) = setMemberPaid(c, "A", false, payments, "2026-02-01")
        assertTrue("A" !in updated.contrib)
        assertEquals(100.0, updated.contributed("B"), 0.001)
        // «Сбор: A» удалён, возврат стал обычным платежом: деньги не исчезли.
        assertEquals(2, newPayments.size)
        assertTrue(newPayments.none { it.member == "A" && it.comment.startsWith("Сбор: ") })
        val refund = newPayments.single { it.member == "A" }
        assertEquals("", refund.comment)
        assertEquals(50.0, refund.amount, 0.001)
        assertTrue(newPayments.any { it.member == "B" && it.comment == "Сбор: Gift" })
    }

    @Test
    fun `снятие отметки у недоплатившего убирает строки сбора`() {
        val c = collection(mapOf("A" to 40.0))
        val payments = listOf(payment("A", 40.0, "Сбор: Gift"))
        val (updated, newPayments) = setMemberPaid(c, "A", false, payments, "2026-02-01")
        assertTrue("A" !in updated.contrib)
        assertTrue(newPayments.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `нельзя отметить участника не из сбора`() {
        setMemberPaid(collection(), "C", true, emptyList(), "2026-02-01")
    }
}
