package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Значения пишутся в Google Таблицы в режиме RAW: имя, описание расхода, комментарий
 * или название сбора не должны исполняться как формулы при открытии таблицы.
 */
class WriteInputOptionTest {

    @Test
    fun valuesAreWrittenAsRawInput() {
        assertEquals("RAW", VALUE_INPUT_RAW)
        assertEquals(VALUE_INPUT_RAW, BatchUpdateValuesRequest(data = emptyList()).valueInputOption)
    }

    @Test
    fun codecKeepsFormulaLikeTextVerbatim() {
        val rows = SheetCodec.paymentsRows(
            listOf(
                PaymentData(
                    member = "=HYPERLINK(\"http://example.com\",\"клик\")",
                    date = "2026-01-01",
                    amount = 100.0,
                    comment = "=IMPORTXML(\"http://example.com\",\"//a\")",
                ),
            ),
        )
        assertEquals("=HYPERLINK(\"http://example.com\",\"клик\")", rows[1][0])
        assertEquals("=IMPORTXML(\"http://example.com\",\"//a\")", rows[1][3])
    }
}
