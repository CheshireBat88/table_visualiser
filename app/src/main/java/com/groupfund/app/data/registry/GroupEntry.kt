package com.groupfund.app.data.registry

/**
 * Запись о группе в локальном реестре пользователя.
 * role: "creator" | "observer" | "member".
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
    /**
     * id прежней таблицы, из которой группа была передана другому ведущему.
     * Пока не пусто — в меню карточки видна кнопка «Убрать прежнюю версию»
     * (удаляет старую таблицу с диска создателя).
     */
    val retiredSpreadsheetId: String? = null,
    /** Автоматическая стилизация «Сводки» в Google-таблице уже применена один раз. */
    val summaryStyled: Boolean = false,
)