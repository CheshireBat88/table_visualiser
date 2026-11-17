package com.groupfund.app.data

/**
 * Достаёт spreadsheetId из отсканированного QR-кода или вставленного текста.
 *
 * Поддерживаются:
 * - `groupfund://invite/<id>`;
 * - `https://cheshirebat88.github.io/invite/<id>` (с хвостовым `/` и параметрами);
 * - обычная ссылка Google Таблиц `https://docs.google.com/spreadsheets/d/<id>/…`;
 * - «голый» id таблицы.
 *
 * Возвращает null, если текст не похож на приглашение: случайный QR не должен
 * открывать экран подключения к несуществующей группе.
 */
internal fun parseInviteTarget(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val invitePrefixes = listOf(
        "groupfund://invite/",
        "https://cheshirebat88.github.io/invite/",
        "http://cheshirebat88.github.io/invite/",
    )
    invitePrefixes.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let { prefix ->
        val id = text.substring(prefix.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trim('/')
        return id.takeIf(::isSpreadsheetId)
    }

    Regex("""docs\.google\.com/spreadsheets/d/([A-Za-z0-9_-]+)""")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it.takeIf(::isSpreadsheetId) }

    return text.takeIf(::isSpreadsheetId)
}

/** Google-идентификаторы таблиц состоят из букв, цифр, `-` и `_` и длиннее 20 символов. */
internal fun isSpreadsheetId(value: String): Boolean =
    value.length >= 20 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
