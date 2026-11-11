package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перенос свободного месячного остатка в сбор (N6): ранние нетегированные платежи
 * участника раньше терялись — цикл урезания обрывался по достижении суммы, а сборка
 * новых платежей выкидывала все нетегированные строки участника и возвращала только
 * урезанные: всё, что цикл не успел обойти, молча исчезало из таблицы.
 */
class ReclassifyMonthlyPaymentsTest {

    @Test
    fun `ранние платежи не теряются когда сумма набрана раньше конца списка`() {
        val payments = listOf(
            PaymentData("A", "2026-01-01", 100.0, ""),
            PaymentData("A", "2026-02-01", 100.0, ""),
            PaymentData("A", "2026-03-01", 100.0, ""),
            PaymentData("A", "2026-04-01", 100.0, ""),
            PaymentData("B", "2026-05-01", 500.0, ""),
        )
        val newPayments = reclassifyMonthlyToCollectionPayments(payments, "A", "Gift", 150.0)
        val aPayments = newPayments.filter { it.member == "A" }
        // Две самые поздние строки: 100 целиком в сбор + 50 урезано = 150 переноса.
        // Две ранние остаются целиком, плюс строка сбора. Итог у A: 4 строки, сумма 400.
        assertEquals(4, aPayments.size)
        assertEquals(400.0, aPayments.sumOf { it.amount }, 0.001)
        // Ранние платежи нетронуты (раньше — терялись)
        assertTrue(aPayments.any { it.date == "2026-01-01" && it.amount == 100.0 })
        assertTrue(aPayments.any { it.date == "2026-02-01" && it.amount == 100.0 })
        // Полностью израсходованная строка ушла, урезанная осталась с остатком
        assertTrue(aPayments.none { it.date == "2026-04-01" })
        assertTrue(aPayments.any { it.date == "2026-03-01" && it.amount == 50.0 })
        // Строка сбора добавлена ровно на перенос
        assertEquals(150.0, aPayments.first { it.comment == "Сбор: Gift" }.amount, 0.001)
        // Чужие платежи не тронуты
        assertEquals(listOf(payments[4]), newPayments.filter { it.member == "B" })
    }

    @Test
    fun `перенос ровно на два последних платежа - ранние целы`() {
        val payments = listOf(
            PaymentData("A", "2026-01-01", 100.0, ""),
            PaymentData("A", "2026-02-01", 100.0, ""),
            PaymentData("A", "2026-03-01", 100.0, ""),
        )
        val newPayments = reclassifyMonthlyToCollectionPayments(payments, "A", "Gift", 200.0)
        val aPayments = newPayments.filter { it.member == "A" }
        // Ровно два последних ушли в сбор целиком: осталась ранняя строка + строка сбора.
        assertEquals(2, aPayments.size)
        assertEquals(300.0, aPayments.sumOf { it.amount }, 0.001)
        assertTrue(aPayments.any { it.date == "2026-01-01" && it.amount == 100.0 })
        assertEquals(200.0, aPayments.first { it.comment == "Сбор: Gift" }.amount, 0.001)
    }

    @Test
    fun `тегированные строки участника не трогаются`() {
        val payments = listOf(
            PaymentData("A", "2026-01-01", 100.0, ""),
            PaymentData("A", "2026-02-01", 60.0, "Сбор: Other"),
            PaymentData("A", "2026-03-01", 30.0, "Возврат из сбора: Other"),
            PaymentData("A", "2026-04-01", 20.0, "Возврат: A"),
        )
        val newPayments = reclassifyMonthlyToCollectionPayments(payments, "A", "Gift", 80.0)
        // Урезан только нетегированный платёж (100 → 20), все тегированные строки как были.
        assertTrue(newPayments.any { it.comment == "Сбор: Other" && it.amount == 60.0 })
        assertTrue(newPayments.any { it.comment == "Возврат из сбора: Other" && it.amount == 30.0 })
        assertTrue(newPayments.any { it.comment == "Возврат: A" && it.amount == 20.0 })
        assertTrue(newPayments.any { it.comment == "" && it.amount == 20.0 })
        assertEquals(80.0, newPayments.first { it.comment == "Сбор: Gift" }.amount, 0.001)
    }

    @Test
    fun `перенос больше месячных взносов - ошибка`() {
        var thrown: Throwable? = null
        try {
            reclassifyMonthlyToCollectionPayments(
                listOf(PaymentData("A", "2026-01-01", 100.0, "")),
                "A",
                "Gift",
                150.0,
            )
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("ожидали ошибку, получили $thrown", thrown is IllegalStateException)
        assertEquals("Недостаточно внесённых месячных взносов участника", thrown?.message)
    }

    @Test
    fun `платежи до сортировки в произвольном порядке - урезаются самые поздние по дате`() {
        val payments = listOf(
            PaymentData("A", "2026-03-01", 100.0, ""),
            PaymentData("A", "2026-01-01", 100.0, ""),
            PaymentData("A", "2026-02-01", 100.0, ""),
        )
        val newPayments = reclassifyMonthlyToCollectionPayments(payments, "A", "Gift", 100.0)
        val aPayments = newPayments.filter { it.member == "A" }
        // Ушла именно самая поздняя (2026-03-01), ранние целы.
        assertTrue(aPayments.none { it.date == "2026-03-01" })
        assertTrue(aPayments.any { it.date == "2026-01-01" && it.amount == 100.0 })
        assertTrue(aPayments.any { it.date == "2026-02-01" && it.amount == 100.0 })
        assertEquals(300.0, aPayments.sumOf { it.amount }, 0.001)
    }
}
