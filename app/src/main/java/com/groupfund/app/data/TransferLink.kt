package com.groupfund.app.data

/** Разобранная ссылка передачи: id прежней таблицы и одноразовый код. */
data class TransferLinkParts(val spreadsheetId: String, val code: String)

/** Префиксы ссылки передачи (схема приложения и веб-страница). */
private val TRANSFER_PREFIXES = listOf(
    "groupfund://transfer/",
    "https://cheshirebat88.github.io/transfer/",
    "http://cheshirebat88.github.io/transfer/",
)

/**
 * Достаёт id таблицы и код передачи из отсканированной/вставленной ссылки.
 *
 * Поддерживаются:
 * - `groupfund://transfer/<id>?code=<код>`;
 * - `https://cheshirebat88.github.io/transfer/<id>?code=<код>`;
 * - `<id>|<код>` (если ссылку разбили на части вручную).
 *
 * Лишние query-параметры после кода игнорируются, регистр префикса не важен.
 * Возвращает null, если пара «id + код» не похожа на передачу: посторонний QR
 * не должен открывать диалог приёма группы.
 */
fun parseTransferLink(raw: String): TransferLinkParts? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    TRANSFER_PREFIXES
        .firstOrNull { text.startsWith(it, ignoreCase = true) }
        ?.let { prefix -> text = text.substring(prefix.length) }

    val queryIdx = text.indexOf('?')
    val pipeIdx = text.indexOf('|')
    val id: String
    val code: String
    when {
        pipeIdx >= 0 && (queryIdx < 0 || pipeIdx < queryIdx) -> {
            id = text.substring(0, pipeIdx).trim().trim('/')
            code = text.substring(pipeIdx + 1).substringBefore('&').substringBefore('#').trim()
        }

        queryIdx >= 0 -> {
            id = text.substring(0, queryIdx).trim().trim('/')
            val query = text.substring(queryIdx + 1)
            val match = TRANSFER_CODE_PARAM.find(query) ?: return null
            code = match.groupValues[1].trim()
        }

        else -> return null
    }
    if (!isSpreadsheetId(id)) return null
    if (!code.matches(Regex("[A-Za-z0-9]{$TRANSFER_CODE_LENGTH}"))) return null
    return TransferLinkParts(id, code)
}

/** `code=…` в query-строке: первый параметр или любой следующий. */
private val TRANSFER_CODE_PARAM = Regex("(?:^|&)code=([^&#]*)", RegexOption.IGNORE_CASE)

/** Длина кода передачи (8 символов алфавита без похожих букв). */
const val TRANSFER_CODE_LENGTH = 8
