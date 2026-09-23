package com.groupfund.app.data.sheets

/** Имена листов в таблице группы. */
object Tabs {
    const val SUMMARY = "Сводка"
    const val SETTINGS = "Настройки"
    const val MEMBERS = "Участники"
    const val PAYMENTS = "Платежи"
    const val EXPENSES = "Расходы"
    const val COLLECTIONS = "Сборы"

    /** Все листы, создаются сразу при создании таблицы. */
    val ALL: List<String> = listOf(SUMMARY, SETTINGS, MEMBERS, PAYMENTS, EXPENSES, COLLECTIONS)

    /**
     * Минимальный набор листов, по которому таблица опознаётся как группа при импорте.
     * «Сборы» не входит: старые таблицы его не имеют.
     */
    val CORE: List<String> = listOf(SUMMARY, SETTINGS, MEMBERS, PAYMENTS, EXPENSES)
}