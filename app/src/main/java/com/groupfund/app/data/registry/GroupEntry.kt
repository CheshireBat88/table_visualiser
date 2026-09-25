package com.groupfund.app.data.registry

/**
 * Запись о группе в локальном реестре пользователя.
 * role: "creator" | "member".
 */
data class GroupEntry(
    val id: String,
    val title: String,
    val spreadsheetId: String,
    val spreadsheetUrl: String,
    val role: String,
    val createdAtEpochMillis: Long,
    /** Как группа называется «у пользователя» (локально), если он её переименовал. */
    val localTitle: String? = null,
    /** Нет доступа к таблице (удалена или создатель ограничил доступ) — помечается в списке. */
    val unavailable: Boolean = false,
)