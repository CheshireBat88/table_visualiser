package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/**
 * Подготовка диапазонов записи (N3): при неудачном чтении «Сборов» строки
 * «Сбор: X» / «Возврат из сбора: X» должны дойти до листа «Платежи» нетронутыми.
 */
class PrepareWriteRangesTest {

    private fun group(payments: List<PaymentData>, collections: List<CollectionData>) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 100,
        currency = "RUB",
        createdAt = "2026-01-01",
        months = listOf(MonthPlan(YearMonth.of(2026, 1), true, 100)),
        members = listOf(MemberData("A", "", "2026-01-01", true, null, null)),
        payments = payments,
        expenses = emptyList(),
        collections = collections,
    )

    private val giftPayments = listOf(
        PaymentData("A", "2026-01-01", 126.67, "Сбор: Подарок"),
        PaymentData("A", "2026-01-02", 73.33, "Возврат из сбора: Подарок"),
        PaymentData("A", "2026-01-03", 300.0, ""),
    )

    @Test
    fun `Сборы не прочитаны - строки сбора остаются в диапазоне Платежи`() {
        val g = group(giftPayments, collections = emptyList())
        val out = prepareWriteRanges(
            g,
            collectionsReadOk = false,
            data0 = listOf(ValueRange("Платежи!A1:D1000", emptyList())),
        )
        val rows = out.single().values
        // Заголовок + все 3 платежа: ничего не потеряно при ошибке чтения «Сборов».
        assertEquals(4, rows.size)
        assertEquals("Сбор: Подарок", rows[1][3])
        assertEquals("Возврат из сбора: Подарок", rows[2][3])
    }

    @Test
    fun `Сборы прочитаны - осиротевшие строки удаляются из диапазона Платежи`() {
        val g = group(giftPayments, collections = emptyList())
        val out = prepareWriteRanges(
            g,
            collectionsReadOk = true,
            data0 = listOf(ValueRange("Платежи!A1:D1000", emptyList())),
        )
        val rows = out.single().values
        // Заголовок + только обычный платёж: чистка при успешном чтении работает.
        assertEquals(2, rows.size)
        assertEquals(300.0, rows[1][2])
    }

    @Test
    fun `диапазон Сборы дедуплицируется по имени`() {
        val coll = CollectionData("Подарок", "2026-01-01", 380.0, listOf("A"), mapOf("A" to 100.0), true)
        val g = group(emptyList(), listOf(coll, coll))
        val out = prepareWriteRanges(
            g,
            collectionsReadOk = true,
            data0 = listOf(ValueRange("Сборы!A1:F100", emptyList())),
        )
        // Заголовок + одна запись вместо двух дублей.
        assertEquals(2, out.single().values.size)
    }

    @Test
    fun `прочие диапазоны проходят без изменений`() {
        val g = group(giftPayments, emptyList())
        val untouched = ValueRange("Участники!A1:H100", listOf(listOf("A")))
        val out = prepareWriteRanges(g, collectionsReadOk = false, listOf(untouched))
        assertEquals(listOf(untouched), out)
    }
}
