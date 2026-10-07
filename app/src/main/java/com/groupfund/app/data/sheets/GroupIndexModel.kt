package com.groupfund.app.data.sheets

/**
 * Технический файл-индекс на Google Диске: список таблиц, к которым у аккаунта
 * есть доступ, с ролью в каждой из них.
 *
 * Нужен отдельно от обычных групп, потому что под scope `drive.file` чужие файлы
 * на Диске вообще не видны — найти их поиском невозможно. Поэтому чужие таблицы
 * хранятся в индексе (ссылка + роль), а свои и так находятся поиском
 * (см. [SheetsRepository.discoverGroups]).
 *
 * Файл принадлежит создавшему его аккаунту и никому больше не расшаривается.
 */

/** Имя листа внутри файла-индекса. */
const val INDEX_SHEET = "Индекс"

/** Имя самого файла индекса на Диске (видно только его владельцу). */
const val INDEX_FILE_TITLE = "GroupFund — индекс таблиц"

/** Метка в appProperties: файл — индекс, а не группа. */
const val INDEX_MARKER_KEY = "groupfund.index"
const val INDEX_MARKER_VALUE = "1"

/** Запись индекса: одна таблица, доступная этому аккаунту. */
data class IndexEntry(
    val spreadsheetId: String,
    val title: String,
    val url: String,
    /** "creator" | "member" | "observer" — как в [com.groupfund.app.data.registry.GroupEntry.role]. */
    val role: String,
    /** Дата подключения, yyyy-MM-dd. */
    val joinedAt: String,
)

/** Кодирование и разбор листа «Индекс». */
object IndexCodec {

    const val VERSION = 1

    private val ROLES = setOf("creator", "member", "observer")

    /** Строки для полной перезаписи листа. */
    fun rows(account: String, entries: List<IndexEntry>): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>(
            listOf("indexVersion", VERSION),
            listOf("account", account),
            emptyList(),
            listOf("spreadsheetId", "title", "role", "joinedAt", "url"),
        )
        entries.forEach { e ->
            rows.add(listOf(e.spreadsheetId, e.title, e.role, e.joinedAt, e.url))
        }
        return rows
    }

    /**
     * Разбирает лист независимо от порядка строк: ключевые пары (версия, аккаунт)
     * читаются по первому столбцу, строки таблицы — по заголовку. Мусор, пустые
     * строки и дубликаты по id пропускаются, неизвестная роль становится "member".
     */
    fun parse(rows: List<List<Any?>>): ParsedIndex {
        var version = 0
        var account = ""
        val entries = LinkedHashMap<String, IndexEntry>()

        rows.forEach { row ->
            when (row.getOrNull(0)?.let(::cellText)) {
                "indexVersion" -> version = cellNum(row.getOrNull(1)).toInt()
                "account" -> account = cellText(row.getOrNull(1))
            }
        }

        val headerIndex = rows.indexOfFirst { row ->
            cellText(row.getOrNull(0)) == "spreadsheetId"
        }
        if (headerIndex >= 0) {
            rows.drop(headerIndex + 1).forEach { row ->
                val id = cellText(row.getOrNull(0))
                if (id.isEmpty()) return@forEach
                val role = cellText(row.getOrNull(2)).ifEmpty { "member" }
                entries[id] = IndexEntry(
                    spreadsheetId = id,
                    title = cellText(row.getOrNull(1)),
                    url = cellText(row.getOrNull(4)),
                    role = if (role in ROLES) role else "member",
                    joinedAt = cellText(row.getOrNull(3)),
                )
            }
        }
        return ParsedIndex(version = version, account = account, entries = entries.values.toList())
    }
}

/** Результат разбора листа «Индекс». */
data class ParsedIndex(
    val version: Int,
    val account: String,
    val entries: List<IndexEntry>,
)
