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
 * Проверка комментария ручного платежа (P0.7): служебные строки сборов нельзя
 * вносить руками — «Сбор: X» и «Возврат из сбора: X» создаются только пересборкой
 * вклада и обслуживают соответствие «вклад ↔ платежи».
 *
 * Ручная строка «Сбор: X» с несуществующим сбором удалялась бы чисткой осиротевших
 * строк прямо в той же записи (appendPayment → writeData → sanitizeCollectionPayments):
 * пользователь видел «успех», а платежа и денег в таблице не оказывалось. С
 * существующим сбором строка выживала, но ломала сводку: вклад во вкладке «Сборы»
 * не рос, сбор оставался «недособранным» при внесённых деньгах.
 *
 * «Возврат: …» — НЕ служебная строка сбора: возвраты участнику легальны и вносятся
 * приложением через refundMember → appendPayment, поэтому не блокируются.
 *
 * @return текст ошибки, или null если комментарий допустим.
 */
internal fun manualPaymentCommentError(comment: String): String? =
    if (comment.trim().isCollectionPayment()) {
        "Взносы и возвраты по сборам создаются во вкладке «Сборы»"
    } else {
        null
    }

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
 * Переводит платежи удаляемого сбора в обычные взносы: «Сбор: X» и «Возврат из сбора: X»
 * переименовываются в «Отмененный сбор: X» (суммы и даты сохраняются). Итог: в месячные
 * взносы возвращается ровно всё, что участник внёс в сбор — и доля, и сверхдолевой излишек.
 *
 * Возвраты тоже переименовываются (N4): строки «Возврат из сбора: X» с прежним тегом
 * выкидывались чисткой осиротевших строк (sanitizeCollectionPayments в writeData) прямо
 * в той же записи удаления — сбора X уже нет, и излишек молча исчезал из таблицы. Оба
 * тега считаются в балансе одинаково (обычный доход), поэтому переименование не меняет
 * суммы — только спасает строки.
 *
 * Сопоставление как в чистке: префикс + имя с обрезкой пробелов, поэтому строки с
 * случайными пробелами вокруг имени («Сбор:  X ») тоже переименовываются, а не
 * удаляются чисткой как «осиротевшие».
 */
internal fun renameCollectionPaymentsToCancelled(
    payments: List<PaymentData>,
    collectionName: String,
): List<PaymentData> {
    val cancelled = "${SummaryCalculator.CANCELLED_COLLECTION_TAG_PREFIX}$collectionName"
    fun belongsToDeleted(payment: PaymentData): Boolean {
        val name = when {
            payment.comment.startsWith("Сбор: ") -> payment.comment.removePrefix("Сбор: ").trim()
            payment.comment.startsWith("Возврат из сбора: ") ->
                payment.comment.removePrefix("Возврат из сбора: ").trim()
            else -> return false
        }
        return name == collectionName
    }
    return payments.map { if (belongsToDeleted(it)) it.copy(comment = cancelled) else it }
}

/**
 * Выкидывает платежи, помеченные сбором, которого больше нет в списке («Сбор: X» /
 * «Возврат из сбора: X» без сбора X).
 *
 * @param collectionsReadOk удалось ли в этом сеансе прочитать лист «Сборы». Если чтение
 *   упало по ошибке (сеть, квота), список сборов НЕИЗВЕСТЕН — чистка отключается целиком:
 *   иначе один неудачный readGroup приводил к массовому удалению всех платежей по сборам
 *   при следующей записи (writeData гонит платежи через этот фильтр при каждом изменении).
 *   «Сбора нет» (лист отсутствует у старых таблиц) и «не смогли прочитать» здесь не
 *   различаются — при любых сомнениях ничего не удаляем.
 */
internal fun sanitizeCollectionPayments(
    payments: List<PaymentData>,
    collections: List<CollectionData>,
    collectionsReadOk: Boolean,
): List<PaymentData> {
    if (!collectionsReadOk) {
        return payments
    }
    val validNames = collections.map { it.name }.toSet()
    return payments.filter { p ->
        when {
            p.comment.startsWith("Сбор: ") ->
                p.comment.removePrefix("Сбор: ").trim() in validNames
            p.comment.startsWith("Возврат из сбора: ") ->
                p.comment.removePrefix("Возврат из сбора: ").trim() in validNames
            else -> true
        }
    }
}

/**
 * Пересборка платежей при переносе свободного месячного остатка участника в сбор
 * (см. SheetsRepository.reclassifyMonthlyToCollection).
 *
 * Переносим [move] из «будущих» месячных взносов участника: идём с конца списка,
 * урезаем самые поздние месячные платежи участника (без тегов сборов и возвратов).
 * Когда нужная сумма набрана, более ранние платежи остаются целиком. Полностью
 * израсходованные строки уходят, вместо них добавляется строка «Сбор: X» на [move].
 *
 * N6: раньше ранние нетегированные платежи терялись — цикл урезания обрывался по
 * достижении суммы, а сборка новых платежей выкидывала ВСЕ нетегированные строки
 * участника и возвращала только урезанные: всё, что цикл не успел обойти, молча
 * исчезало из таблицы вместе с балансом участника.
 */
internal fun reclassifyMonthlyToCollectionPayments(
    payments: List<PaymentData>,
    memberName: String,
    collectionName: String,
    move: Double,
): List<PaymentData> {
    val tag = "Сбор: $collectionName"
    var remaining = move
    val monthlyPayments = payments
        .filter {
            it.member == memberName &&
                !it.comment.startsWith("Сбор:") &&
                !it.comment.startsWith("Возврат из сбора") &&
                !it.comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX)
        }
        .sortedBy { it.date }
    val adjustedTail = mutableListOf<PaymentData>()
    for (p in monthlyPayments.asReversed()) {
        if (remaining <= 0.005) {
            // Сумма набрана: более ранние платежи остаются целиком (N6).
            adjustedTail += p
            continue
        }
        val take = round2(minOf(remaining, p.amount))
        remaining = round2(remaining - take)
        val left = round2(p.amount - take)
        if (left > 0.005) adjustedTail += p.copy(amount = left)
    }
    if (remaining > 0.005) error("Недостаточно внесённых месячных взносов участника")
    val keptMonthly = payments
        .filter {
            it.member != memberName ||
                it.comment.startsWith("Сбор:") ||
                it.comment.startsWith("Возврат из сбора") ||
                it.comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX)
        }
    return keptMonthly + adjustedTail + PaymentData(memberName, SheetCodec.today(), move, tag)
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
