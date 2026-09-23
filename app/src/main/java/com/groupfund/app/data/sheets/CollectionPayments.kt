package com.groupfund.app.data.sheets

/**
 * Чистые правила пересборки платежей по сбору из вкладов участников.
 *
 * Вклад участника (contrib) может превышать его долю — тогда в сам сбор засчитывается
 * ровно доля, а излишек выходит отдельным платежом «Возврат из сбора: X» и попадает
 * в баланс участника (распределяется по месяцам). Излишек не увеличивает «собранную
 * сумму» сбора (см. CollectionData.countedContribution).
 */

/** Является ли комментарий платежа служебной строкой сбора («Сбор: X» / «Возврат из сбора: X»). */
internal fun String.isCollectionPayment(): Boolean =
    startsWith(SummaryCalculator.COLLECTION_TAG_PREFIX) ||
        startsWith(SummaryCalculator.COLLECTION_REFUND_TAG_PREFIX)

/**
 * Убирает платежи конкретного участника по сбору.
 *
 * @param keepRefunds при `true` оставляет строки «Возврат из сбора» — излишек, уже зачисленный
 *   в месячный баланс участника. Нужно при снятии галочки/исключении из сбора: сам взнос
 *   откатывается, но реально внесённые сверх доли деньги не должны исчезать.
 *   При пересборке взноса (applyContribution) возврат нужно удалить целиком — тогда `false`.
 */
internal fun removeMemberCollectionPayments(
    payments: List<PaymentData>,
    memberName: String,
    collectionName: String,
    keepRefunds: Boolean = false,
): List<PaymentData> {
    val collectionTag = "Сбор: $collectionName"
    val refundTag = "Возврат из сбора: $collectionName"
    return payments.filterNot {
        it.member == memberName && (it.comment == collectionTag || (!keepRefunds && it.comment == refundTag))
    }
}

/**
 * Применяет один взнос по сбору к текущему состоянию (общая логика для одиночного
 * и пакетного внесения): обновляет вклад участника и пересобирает его платежи по сбору.
 * Пока вклад в пределах доли — прикрепляется транш строкой «Сбор: …»; при избытке —
 * максимум на долю, а остаток уходит в месячную оплату платежом «Возврат из сбора: …».
 */
internal fun applyContribution(
    collections: List<CollectionData>,
    payments: List<PaymentData>,
    collectionName: String,
    memberName: String,
    amount: Double,
): Pair<List<CollectionData>, List<PaymentData>> {
    val collection = collections.firstOrNull { it.name == collectionName }
        ?: error("Сбор не найден")
    if (memberName !in collection.participants) error("Участник не в этом сборе")
    val share = collection.sharePerPerson
    val newContrib = round2(collection.contributed(memberName) + amount)
    val updatedColl = collection.copy(
        contrib = collection.contrib + (memberName to newContrib),
    )
    val newCollections = collections.map { if (it.name == collectionName) updatedColl else it }
    val tag = "Сбор: $collectionName"
    val leftover = "Возврат из сбора: $collectionName"
    val today = SheetCodec.today()
    val newPayments: List<PaymentData>
    if (newContrib <= share + 0.005) {
        // Транш в пределах доли: просто добавляем строку, прошлые транши целы.
        newPayments = payments + PaymentData(memberName, today, round2(amount), tag)
    } else {
        // Переизбыток: максимум — платёж на долю, остаток — в месячную оплату.
        newPayments = removeMemberCollectionPayments(payments, memberName, collectionName) +
            PaymentData(memberName, today, share, tag) +
            PaymentData(memberName, today, round2(newContrib - share), leftover)
    }
    return newCollections to newPayments
}
