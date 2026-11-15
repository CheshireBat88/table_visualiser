package com.groupfund.app.data.sheets

/**
 * Кэш числовых sheetId по таблице (лист → id).
 *
 * id листов не меняются, поэтому кэш экономит запрос метаданных перед каждым
 * оформлением. Но кэш обязан сбрасываться, когда набор листов меняется:
 * добавление «Сборов» старой таблице (ensureCollectionsTab) и смена аккаунта
 * (signOut) иначе оставляли бы устаревшую карту без нового листа — оформление
 * и очистка хвостов молча пропускали бы его.
 */
internal class SheetIdCache {

    private val cache = mutableMapOf<String, Map<String, Int>>()

    fun get(spreadsheetId: String): Map<String, Int>? = cache[spreadsheetId]

    fun put(spreadsheetId: String, ids: Map<String, Int>) {
        cache[spreadsheetId] = ids
    }

    /** Сбрасывает кэш одной таблицы (набор её листов изменился). */
    fun invalidate(spreadsheetId: String) {
        cache.remove(spreadsheetId)
    }

    /** Сбрасывает весь кэш (выход из аккаунта). */
    fun clear() {
        cache.clear()
    }
}
