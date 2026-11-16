package com.groupfund.app.data.sheets

import java.time.LocalDate

/** Вид платежа в истории участника. */
enum class PaymentKind {
    /** Обычный месячный взнос. */
    MONTHLY,
    /** Взнос внутри сбора. */
    COLLECTION,
    /** Излишек сверх доли сбора, ушедший в месячную оплату. */
    COLLECTION_REFUND,
    /** Взнос удалённого сбора, ставший обычным доходом. */
    COLLECTION_CANCELLED,
    /** Взнос, перенесённый из сбора после выхода участника. */
    COLLECTION_TRANSFER,
    /** Возврат денег участнику. */
    REFUND,
}

/** Фильтр истории по виду платежа. */
enum class PaymentHistoryKindFilter {
    ALL,
    MONTHLY,
    COLLECTIONS,
    REFUNDS,
}

/** Фильтр истории по дате. */
enum class PaymentHistoryPeriod {
    ALL,
    LAST_30_DAYS,
    LAST_90_DAYS,
    CURRENT_YEAR,
}

/** Одна строка истории: исходный платёж, разобранная дата и вид. */
data class PaymentHistoryEntry(
    val payment: PaymentData,
    val date: LocalDate?,
    val kind: PaymentKind,
) {
    /** Сумма со знаком: возвраты уменьшают итог. */
    val signedAmount: Double
        get() = if (kind == PaymentKind.REFUND) -payment.amount else payment.amount
}

/** Итоги по выбранным строкам: поступления и возвраты (без сальдо в модели группы). */
data class PaymentHistoryTotals(
    val income: Double,
    val refunded: Double,
) {
    val net: Double get() = round2(income - refunded)
}

/** Вид платежа по комментарию. */
fun paymentKind(comment: String): PaymentKind = when {
    comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX) -> PaymentKind.REFUND
    comment.startsWith(SummaryCalculator.COLLECTION_REFUND_TAG_PREFIX) -> PaymentKind.COLLECTION_REFUND
    comment.startsWith(SummaryCalculator.COLLECTION_TAG_PREFIX) -> PaymentKind.COLLECTION
    comment.startsWith(SummaryCalculator.CANCELLED_COLLECTION_TAG_PREFIX) -> PaymentKind.COLLECTION_CANCELLED
    comment.startsWith(SummaryCalculator.COLLECTION_TRANSFER_TAG_PREFIX) -> PaymentKind.COLLECTION_TRANSFER
    else -> PaymentKind.MONTHLY
}

private fun PaymentHistoryKindFilter.matches(kind: PaymentKind): Boolean = when (this) {
    PaymentHistoryKindFilter.ALL -> true
    PaymentHistoryKindFilter.MONTHLY ->
        kind == PaymentKind.MONTHLY ||
            kind == PaymentKind.COLLECTION_CANCELLED ||
            kind == PaymentKind.COLLECTION_TRANSFER
    PaymentHistoryKindFilter.COLLECTIONS ->
        kind == PaymentKind.COLLECTION || kind == PaymentKind.COLLECTION_REFUND
    PaymentHistoryKindFilter.REFUNDS -> kind == PaymentKind.REFUND
}

private fun PaymentHistoryPeriod.startDate(today: LocalDate): LocalDate? = when (this) {
    PaymentHistoryPeriod.ALL -> null
    PaymentHistoryPeriod.LAST_30_DAYS -> today.minusDays(30)
    PaymentHistoryPeriod.LAST_90_DAYS -> today.minusDays(90)
    PaymentHistoryPeriod.CURRENT_YEAR -> LocalDate.of(today.year, 1, 1)
}

/**
 * История платежей участника: фильтры по виду и периоду, новые сверху.
 * Строки с неразбираемой датой видны только в режиме «всё время».
 */
fun paymentHistory(
    payments: List<PaymentData>,
    memberName: String,
    kindFilter: PaymentHistoryKindFilter = PaymentHistoryKindFilter.ALL,
    period: PaymentHistoryPeriod = PaymentHistoryPeriod.ALL,
    today: LocalDate = LocalDate.now(),
): List<PaymentHistoryEntry> {
    val from = period.startDate(today)
    return payments
        .filter { it.member == memberName }
        .map { PaymentHistoryEntry(it, parseSheetDate(it.date), paymentKind(it.comment)) }
        .filter { kindFilter.matches(it.kind) }
        .filter { from == null || (it.date != null && !it.date.isBefore(from)) }
        .sortedByDescending { it.date ?: LocalDate.MIN }
}

/** Поступления и возвраты по уже отфильтрованным строкам. */
fun paymentHistoryTotals(entries: List<PaymentHistoryEntry>): PaymentHistoryTotals {
    var income = 0.0
    var refunded = 0.0
    entries.forEach { entry ->
        if (entry.kind == PaymentKind.REFUND) refunded += entry.payment.amount else income += entry.payment.amount
    }
    return PaymentHistoryTotals(round2(income), round2(refunded))
}
