package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тесты кэша sheetId: сброс при добавлении листа и выходе из аккаунта (N7). */
class SheetIdCacheTest {

    @Test
    fun `кэш хранит карту по таблице`() {
        val cache = SheetIdCache()
        cache.put("s1", mapOf("Платежи" to 1))
        assertEquals(mapOf("Платежи" to 1), cache.get("s1"))
        assertNull(cache.get("s2"))
    }

    @Test
    fun `сброс одной таблицы не трогает остальные`() {
        val cache = SheetIdCache()
        cache.put("s1", mapOf("Платежи" to 1))
        cache.put("s2", mapOf("Сборы" to 2))
        cache.invalidate("s1")
        assertNull(cache.get("s1"))
        assertEquals(mapOf("Сборы" to 2), cache.get("s2"))
    }

    @Test
    fun `полный сброс очищает все таблицы`() {
        val cache = SheetIdCache()
        cache.put("s1", mapOf("Платежи" to 1))
        cache.put("s2", mapOf("Сборы" to 2))
        cache.clear()
        assertNull(cache.get("s1"))
        assertNull(cache.get("s2"))
    }
}
