package com.groupfund.app.data.sheets

/**
 * Чистые правила передачи группы другому ведущему и проверки, что по ссылке
 * действительно таблица группы. Вынесены из репозитория и ViewModel, чтобы их
 * можно было покрыть тестами без сети.
 */

/** Что делать приёмщику передачи после того, как копия уже создана. */
internal enum class TransferOutcome {
    /** Копию ещё никто не забрал — записываем её id в прежнюю таблицу. */
    CLAIM,

    /** В прежней таблице уже стоит id этой же копии — повторный приём. */
    ALREADY_OURS,

    /** Передачу забрал другой получатель — копию нужно удалить и не добавлять в реестр. */
    TAKEN_BY_OTHER,
}

/**
 * Решение по маркеру передачи: [takenId] — id копии из прежней таблицы,
 * [myCopyId] — только что созданная копия этого получателя.
 */
internal fun decideTransferOutcome(takenId: String?, myCopyId: String): TransferOutcome = when {
    takenId.isNullOrEmpty() -> TransferOutcome.CLAIM
    takenId == myCopyId -> TransferOutcome.ALREADY_OURS
    else -> TransferOutcome.TAKEN_BY_OTHER
}

/** Блокирует ли маркер новую передачу (завершённую или уже начатую). */
internal fun transferStartBlocked(marker: TransferMarker): Boolean =
    !marker.pendingCode.isNullOrEmpty() || !marker.newSpreadsheetId.isNullOrEmpty()

/**
 * Обязательные ключи листа «Настройки»: по ним таблица опознаётся как группа
 * даже без метки в appProperties (старые таблицы и копии, сделанные не нами).
 */
private val SETTINGS_KEYS = listOf("groupname", "baseamount", "currency", "createdat")

/** Похож ли лист «Настройки» на настройки группы. */
internal fun isGroupSettings(rows: List<List<Any?>>): Boolean {
    val keys = rows.map { cellText(it.getOrNull(0)).lowercase() }.toSet()
    if (!SETTINGS_KEYS.all { it in keys }) return false
    // Заголовок таблицы месяцев отделяет план от служебных ключей.
    return rows.any { cellText(it.getOrNull(0)) == "Месяц" }
}

/**
 * Настоящая ли таблица группы: все базовые листы на месте и «Настройки» узнаваемы.
 * Одного совпадения имён листов мало — такую таблицу мог сделать кто угодно.
 */
internal fun looksLikeGroupTable(titles: List<String>, settingsRows: List<List<Any?>>): Boolean =
    Tabs.CORE.all { it in titles } && isGroupSettings(settingsRows)

/**
 * Расхождения копии с оригиналом (пустой список — копия полная).
 * Сравниваются составы и суммы: имена участников и их активность, платежи,
 * расходы, сборы с вкладами. Порядок строк роли не играет.
 */
internal fun copyGaps(old: GroupData, copy: GroupData): List<String> {
    val gaps = mutableListOf<String>()
    if (old.baseAmount != copy.baseAmount || old.currency != copy.currency) gaps += "настройки"
    if (old.months != copy.months) gaps += "план месяцев"
    if (memberSignature(old) != memberSignature(copy)) gaps += "участники"
    if (paymentSignature(old) != paymentSignature(copy)) gaps += "платежи"
    if (expenseSignature(old) != expenseSignature(copy)) gaps += "расходы"
    if (collectionSignature(old) != collectionSignature(copy)) gaps += "сборы"
    return gaps
}

private fun memberSignature(g: GroupData): List<String> =
    g.members.map { "${it.name}|${it.active}|${it.joinDate}" }.sorted()

private fun paymentSignature(g: GroupData): List<String> =
    g.payments.map { "${it.member}|${it.date}|${round2(it.amount)}|${it.comment}" }.sorted()

private fun expenseSignature(g: GroupData): List<String> =
    g.expenses
        .map { "${it.date}|${it.description}|${round2(it.amount)}|${it.participants.joinToString(";")}" }
        .sorted()

private fun collectionSignature(g: GroupData): List<String> =
    g.collections.map { c ->
        val contrib = c.contrib.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${round2(it.value)}" }
        val participants = c.participants.joinToString(";")
        "${c.name}|${c.date}|${round2(c.target)}|$participants|${c.sharedWithBudget}|$contrib"
    }.sorted()
