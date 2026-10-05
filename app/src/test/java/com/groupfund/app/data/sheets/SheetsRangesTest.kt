package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetsRangesTest {

    @Test
    fun colNamesFollowSpreadsheetAlphabet() {
        assertEquals("A", colName(1))
        assertEquals("B", colName(2))
        assertEquals("Z", colName(26))
        assertEquals("AA", colName(27))
        assertEquals("AZ", colName(52))
        assertEquals("BA", colName(53))
    }

    @Test
    fun writeRangeGrowsWithDataInsteadOfFixedLimit() {
        val rows = List(1201) { listOf<Any?>("Платёж $it", "2026-10-01", 500.0, "") }
        val range = vrange("Платежи", rows)
        assertEquals("Платежи!A1:D1201", range.range)
    }

    @Test
    fun writeRangeCoversWidestRow() {
        val range = vrange("Расходы", listOf(listOf("a", "b", "c", "d", "e"), listOf("1", "2")))
        assertEquals("Расходы!A1:E2", range.range)
    }

    @Test
    fun emptyValuesStillProduceValidRange() {
        assertEquals("Сводка!A1:A1", vrange("Сводка", emptyList()).range)
    }
}