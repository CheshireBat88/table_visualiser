package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Тесты истории платежей участника: виды, фильтры и итоги. */
class PaymentHistoryTest {

    private fun p(member: String, date: String, amount: Double, comment: String = "") =
        PaymentData(member, date, amount, comment)

    private val today = LocalDate.of(2026, 6, 15)

    private val payments = listOf(
        p("Аня", "2026-06-01", 100.0),
        p("Аня", "2026-05-01", 100.0, "Сбор: Подарок"),
        p("Аня", "2026-04-01", 30.0, "Возврат из сбора: Подарок"),
        p("Аня", "2026-03-01", 40.0, "Возврат: переплата"),
        p("Аня", "2026-02-01", 50.0, "Отмененный сбор: Старый"),
        p("Аня", "2026-01-01", 60.0, "Перенос из сбора: Подарок"),
        p("Боря", "2026-06-01", 100.0),
    )

    @Test
    fun `вид платежа определяется по комментарию`() {
        assertEquals(PaymentKind.MONTHLY, paymentKind(""))
        assertEquals(PaymentKind.COLLECTION, paymentKind("Сбор: X"))
        assertEquals(PaymentKind.COLLECTION_REFUND, paymentKind("Возврат из сбора: X"))
        assertEquals(PaymentKind.COLLECTION_CANCELLED, paymentKind("Отмененный сбор: X"))
        assertEquals(PaymentKind.COLLECTION_TRANSFER, paymentKind("Перенос из сбора: X"))
        assertEquals(PaymentKind.REFUND, paymentKind("Возврат: X"))
    }

    @Test
    fun `берутся только платежи нужного участника и новые сверху`() {
        val entries = paymentHistory(payments, "Аня", today = today)
        assertEquals(6, entries.size)
        assertEquals(LocalDate.of(2026, 6, 1), entries.first().date)
        assertEquals(LocalDate.of(2026, 1, 1), entries.last().date)
    }

    @Test
    fun `фильтр по виду отделяет сборы и возвраты`() {
        val collections = paymentHistory(
            payments,
            "Аня",
            kindFilter = PaymentHistoryKindFilter.COLLECTIONS,
            today = today,
        )
        assertEquals(setOf(PaymentKind.COLLECTION, PaymentKind.COLLECTION_REFUND), collections.map { it.kind }.toSet())

        val refunds = paymentHistory(
            payments,
            "Аня",
            kindFilter = PaymentHistoryKindFilter.REFUNDS,
            today = today,
        )
        assertEquals(1, refunds.size)
        assertEquals(40.0, refunds.single().payment.amount, 0.001)
    }

    @Test
    fun `фильтр по периоду отсекает старые строки`() {
        val last30 = paymentHistory(
            payments,
            "Аня",
            period = PaymentHistoryPeriod.LAST_30_DAYS,
            today = today,
        )
        assertEquals(listOf(LocalDate.of(2026, 6, 1)), last30.map { it.date })

        val year = paymentHistory(
            payments,
            "Аня",
            period = PaymentHistoryPeriod.CURRENT_YEAR,
            today = today,
        )
        assertEquals(6, year.size)
    }

    @Test
    fun `строка с неразбираемой датой видна только во всех`() {
        val withBadDate = payments + p("Аня", "когда-то", 10.0)
        assertEquals(1, paymentHistory(withBadDate, "Аня", period = PaymentHistoryPeriod.LAST_30_DAYS, today = today).size)
        assertEquals(7, paymentHistory(withBadDate, "Аня", period = PaymentHistoryPeriod.ALL, today = today).size)
    }

    @Test
    fun `итоги отделяют возвраты от поступлений`() {
        val entries = paymentHistory(payments, "Аня", today = today)
        val totals = paymentHistoryTotals(entries)
        assertEquals(100.0 + 100.0 + 30.0 + 50.0 + 60.0, totals.income, 0.001)
        assertEquals(40.0, totals.refunded, 0.001)
        assertEquals(300.0, totals.net, 0.001)
    }

    @Test
    fun `возврат уменьшает знаковую сумму`() {
        val entries = paymentHistory(payments, "Аня", kindFilter = PaymentHistoryKindFilter.REFUNDS, today = today)
        assertTrue(entries.single().signedAmount < 0)
    }
}
