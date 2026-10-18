package com.groupfund.app.data.sheets

import android.util.Log
import retrofit2.HttpException

/** Диапазон хвоста листа «Индекс» — всё после данных (безопасный запас на случаи сокращения). */
internal const val INDEX_CLEAR_ROWS = 1000

/** Диапазон очистки хвоста: начинается со строки сразу за последней строкой данных. */
internal fun indexClearRange(lastDataRow: Int): String = "$INDEX_SHEET!A${lastDataRow + 1}:Z$INDEX_CLEAR_ROWS"

/**
 * Поиск файла-индекса по точному имени: под drive.file свои файлы ищутся по
 * имени стабильно, а запрос по appProperties (оператор has) может не совпасть
 * из-за событийной индексации. Имя уникальное — вручную такой файл не назовут;
 * метка в appProperties остаётся, чтобы «Из диска» не принял индекс за группу.
 */
internal val INDEX_QUERY: String =
    "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false " +
        "and name='$INDEX_FILE_TITLE'"

/**
 * Технический файл-индекс на Google Диске: список таблиц, доступных аккаунту,
 * с ролью в каждой (см. [GroupIndexModel.kt] для формата).
 *
 * Файл создаётся приватным — без публичного доступа по ссылке: в нём лежат
 * ссылки на чужие группы, делиться им нельзя. Отличается от групп меткой
 * [INDEX_MARKER_KEY], поэтому поиск «Из диска» его за группу не принимает.
 */
class GroupIndexRepository(private val repository: SheetsRepository) {

    private var cachedFileId: String? = null

    /**
     * Читает индекс; если файла ещё нет — создаёт и возвращает пустой.
     * Если файл был удалён — создаёт новый, чтобы приложение не зависло.
     */
    suspend fun readIndex(): Result<ParsedIndex> = repository.withToken { sheets, drive, token ->
        withIndexFile(sheets, drive, token) { id ->
            val resp = sheets.getValues("Bearer $token", id, INDEX_SHEET)
            IndexCodec.parse(resp.values.orEmpty())
        }
    }

    /**
     * Полностью перезаписывает лист индекса: данные пишутся первыми, хвост
     * чистится после — при повторе запроса файл не остаётся пустым.
     */
    suspend fun writeIndex(account: String, entries: List<IndexEntry>): Result<Unit> =
        repository.withToken { sheets, drive, token ->
            val authHeader = "Bearer $token"
            withIndexFile(sheets, drive, token) { id ->
                val rows = IndexCodec.rows(account, entries)
                sheets.batchUpdateValues(
                    authHeader,
                    id,
                    BatchUpdateValuesRequest(data = listOf(vrange(INDEX_SHEET, rows))),
                )
                sheets.batchClearValues(
                    authHeader,
                    id,
                    BatchClearValuesRequest(ranges = listOf(indexClearRange(rows.size))),
                )
            }
        }

    /**
     * Ведёт к файлу-индексу: кэш → поиск по метке → создание. Если файл исчез,
     * и операция упала в 404 — сбрасывает кэш, создаёт файл заново и повторяет.
     */
    private suspend fun <T> withIndexFile(
        sheets: SheetsApiService,
        drive: DriveApiService,
        token: String,
        block: suspend (String) -> T,
    ): T {
        val id = cachedFileId ?: findIndexFileId(drive, token) ?: createIndexFile(sheets, drive, token)
        return try {
            block(id)
        } catch (e: HttpException) {
            if (e.code() != 404) throw e
            Log.w("SheetsApi", "index file $id disappeared, recreating")
            cachedFileId = null
            block(createIndexFile(sheets, drive, token))
        }
    }

    private suspend fun findIndexFileId(drive: DriveApiService, token: String): String? {
        val page = drive.listFiles(
            "Bearer $token",
            query = INDEX_QUERY,
            fields = "nextPageToken,files(id)",
            pageSize = 10,
        )
        val id = page.files?.firstNotNullOfOrNull { it.id } ?: return null
        cachedFileId = id
        return id
    }

    private suspend fun createIndexFile(sheets: SheetsApiService, drive: DriveApiService, token: String): String {
        val authHeader = "Bearer $token"
        val response = sheets.createSpreadsheet(
            authHeader,
            CreateSpreadsheetRequest(
                properties = SpreadsheetPropertiesRequest(title = INDEX_FILE_TITLE),
                sheets = listOf(
                    SheetRequestContent(properties = SheetPropertiesRequest(title = INDEX_SHEET)),
                ),
            ),
        )
        // Метка «это индекс» — ищется ним же; сбой логируется, но не рушит создание.
        runCatching {
            drive.patchFile(authHeader, response.spreadsheetId, DriveFilePatch(mapOf(INDEX_MARKER_KEY to INDEX_MARKER_VALUE)))
        }.onFailure { Log.w("SheetsApi", "patchFile index marker failed for ${response.spreadsheetId}", it) }
        cachedFileId = response.spreadsheetId
        return response.spreadsheetId
    }
}
