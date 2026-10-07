package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты кодека листа «Индекс» — технического файла со списком доступных таблиц. */
class GroupIndexCodecTest {

    private val sample = listOf(
        IndexEntry("id-1", "Наши деньги", "https://docs.google.com/spreadsheets/d/id-1", "creator", "2026-01-15"),
        IndexEntry("id-2", "Соседи, дача", "https://docs.google.com/spreadsheets/d/id-2", "observer", "2026-03-02"),
        IndexEntry("id-3", "Коллеги", "https://docs.google.com/spreadsheets/d/id-3", "member", "2026-07-20"),
    )

    @Test
    fun `круговая запись индекса`() {
        val parsed = IndexCodec.parse(IndexCodec.rows("user@example.com", sample))
        assertEquals(IndexCodec.VERSION, parsed.version)
        assertEquals("user@example.com", parsed.account)
        assertEquals(sample, parsed.entries)
    }

    @Test
    fun `сохраняются специальные символы в названии`() {
        val tricky = listOf(
            IndexEntry("id-x", "Группа \"А\", №1 🎉", "https://example.com/a", "member", "2026-01-01"),
        )
        assertEquals(tricky, IndexCodec.parse(IndexCodec.rows("u@example.com", tricky)).entries)
    }

    @Test
    fun `разбор терпит мусор пустые строки и дубликаты`() {
        val rows = listOf(
            listOf("indexVersion", 1),
            listOf("account", "user@example.com"),
            emptyList(),
            listOf("мусорная строка", "значение"),
            listOf("spreadsheetId", "title", "role", "joinedAt", "url"),
            listOf("id-1", "Первая", "creator", "2026-01-01", "url-1"),
            emptyList(),
            listOf("id-2", "Вторая", "неизвестная роль", "2026-02-01", "url-2"),
            listOf("id-1", "Первая обновлённая", "observer", "2026-05-01", "url-1b"),
        )
        val parsed = IndexCodec.parse(rows)
        assertEquals(1, parsed.version)
        assertEquals("user@example.com", parsed.account)
        assertEquals(2, parsed.entries.size)
        assertEquals("Первая обновлённая", parsed.entries.first { it.spreadsheetId == "id-1" }.title)
        assertEquals("observer", parsed.entries.first { it.spreadsheetId == "id-1" }.role)
        assertEquals("member", parsed.entries.first { it.spreadsheetId == "id-2" }.role)
    }

    @Test
    fun `пустой лист не ломает разбор`() {
        val parsed = IndexCodec.parse(emptyList())
        assertEquals(0, parsed.version)
        assertEquals("", parsed.account)
        assertTrue(parsed.entries.isEmpty())
    }
}
