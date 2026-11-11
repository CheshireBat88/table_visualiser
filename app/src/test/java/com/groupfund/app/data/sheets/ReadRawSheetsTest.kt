package com.groupfund.app.data.sheets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение листов с флагом «Сборы прочитаны» (N2): неудачное чтение «Сборов» не должно
 * молча подменять их пустым списком — оно помечается флагом и отключает чистку платежей.
 */
class ReadRawSheetsTest {

    private val ranges = listOf(
        "settings" to "Настройки!A1:C500",
        "members" to "Участники!A1:H2000",
        "payments" to "Платежи!A1:D50000",
        "expenses" to "Расходы!A1:E50000",
        "collections" to "Сборы!A1:F50000",
    )

    private fun ok(vararg rows: List<Any?>) = ValuesResponse(values = rows.toList())

    @Test
    fun `batchGet успешен - флаг true и одиночные чтения не нужны`() {
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = {
                    BatchGetValuesResponse(
                        valueRanges = ranges.map { BatchGetValueRange(values = listOf(listOf("v"))) },
                    )
                },
                get = { error("batchGet успешен — одиночные чтения не должны вызываться") },
            )
            val read = readRawSheets(api, "Bearer t", "id1", ranges)
            assertTrue(read.collectionsReadOk)
            assertEquals(5, read.values.size)
            assertEquals(listOf("v"), read.values["collections"]?.first())
        }
    }

    @Test
    fun `batchGet упал и Сборы не читаются - флаг false, остальные листы прочитаны`() {
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = { error("сеть отвалилась") },
                get = { range ->
                    if (range.startsWith("Сборы")) error("HTTP 500 на Сборах")
                    ok(listOf("строка1"), listOf("строка2"))
                },
            )
            val read = readRawSheets(api, "Bearer t", "id1", ranges)
            assertFalse(read.collectionsReadOk)
            assertTrue(read.values["collections"].orEmpty().isEmpty())
            assertEquals(2, read.values["members"]?.size)
            assertEquals(2, read.values["payments"]?.size)
        }
    }

    @Test
    fun `batchGet упал но всё дочиталось по одному - флаг true`() {
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = { error("429") },
                get = { range -> ok(listOf("v"), listOf(range)) },
            )
            val read = readRawSheets(api, "Bearer t", "id1", ranges)
            assertTrue(read.collectionsReadOk)
            assertEquals(listOf("v"), read.values["collections"]?.first())
        }
    }

    @Test
    fun `batchGet вернул не все диапазоны - переходит на одиночные чтения`() {
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = {
                    BatchGetValuesResponse(
                        valueRanges = listOf(BatchGetValueRange(values = listOf(listOf("v")))),
                    )
                },
                get = { range -> ok(listOf("fallback")) },
            )
            val read = readRawSheets(api, "Bearer t", "id1", ranges)
            assertTrue(read.collectionsReadOk)
            assertEquals(listOf("fallback"), read.values["collections"]?.first())
        }
    }

    @Test
    fun `отмена корутины в batchGet - пробрасывается, а не гасится`() {
        var thrown: Throwable? = null
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = { throw CancellationException("cancelled") },
                get = { error("не должно дойти") },
            )
            try {
                readRawSheets(api, "Bearer t", "id1", ranges)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("ожидали CancellationException, получили $thrown", thrown is CancellationException)
    }

    @Test
    fun `отмена корутины при чтении Сборов - пробрасывается, а не гасится в флаг`() {
        var thrown: Throwable? = null
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = { error("сеть") },
                get = { range ->
                    if (range.startsWith("Сборы")) throw CancellationException("cancelled")
                    ok(listOf("v"))
                },
            )
            try {
                readRawSheets(api, "Bearer t", "id1", ranges)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("ожидали CancellationException, получили $thrown", thrown is CancellationException)
    }

    @Test
    fun `ошибка чтения Платежей - пробрасывается, запись по неполным данным невозможна`() {
        var thrown: Throwable? = null
        runBlocking {
            val api = FakeSheetsApi(
                batchGet = { error("сеть") },
                get = { range ->
                    if (range.startsWith("Платежи")) error("HTTP 500 на Платежах")
                    ok(listOf("v"))
                },
            )
            try {
                readRawSheets(api, "Bearer t", "id1", ranges)
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("ожидали ошибку чтения, получили $thrown", thrown is IllegalStateException)
    }
}

/** Заглушка Sheets API: настраиваются только batchGetValues и getValues по диапазону. */
private class FakeSheetsApi(
    private val batchGet: (suspend () -> BatchGetValuesResponse)? = null,
    private val get: (suspend (String) -> ValuesResponse)? = null,
) : SheetsApiService {

    override suspend fun createSpreadsheet(
        authorization: String,
        body: CreateSpreadsheetRequest,
    ): SpreadsheetResponse = error("не используется в тесте")

    override suspend fun batchUpdateValues(
        authorization: String,
        spreadsheetId: String,
        body: BatchUpdateValuesRequest,
    ): BatchUpdateValuesResponse = error("не используется в тесте")

    override suspend fun batchClearValues(
        authorization: String,
        spreadsheetId: String,
        body: BatchClearValuesRequest,
    ): BatchClearValuesResponse = error("не используется в тесте")

    override suspend fun getValues(
        authorization: String,
        spreadsheetId: String,
        range: String,
        majorDimension: String,
    ): ValuesResponse = get?.invoke(range) ?: error("нет заглушки для диапазона $range")

    override suspend fun batchGetValues(
        authorization: String,
        spreadsheetId: String,
        body: BatchGetValuesRequest,
    ): BatchGetValuesResponse = batchGet?.invoke() ?: error("нет заглушки для batchGetValues")

    override suspend fun getSpreadsheetMeta(
        authorization: String,
        spreadsheetId: String,
        fields: String,
    ): SpreadsheetMeta = error("не используется в тесте")

    override suspend fun batchUpdateSpreadsheet(
        authorization: String,
        spreadsheetId: String,
        body: SpreadsheetBatchUpdateRequest,
    ): retrofit2.Response<Any> = error("не используется в тесте")
}
