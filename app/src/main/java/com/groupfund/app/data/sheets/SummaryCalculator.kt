package com.groupfund.app.data.sheets

import java.time.LocalDate
import java.time.YearMonth

/** Итог по одному участнику. */
data class MemberSummary(
    val member: MemberData,
    /** Оплата в рублях по каждому месяцу окна (индекс совпадает с months). */
    val perMonth: List<Double>,
    val totalPaid: Double,
    /** Взнос в общий счёт (раскладка): расходы + доли по сборам. */
    val expenseShare: Double,
    /** Доли по сборам, включённые в expenseShare (показ в карточке). */
    val collectionShare: Double,
    val balance: Double,
    /** Последний месяц окна, за который месяц оплачен полностью (или null). */
    val paidThrough: YearMonth?,
    /** Сколько требуемых месяцев подряд с начала окна оплачено полностью. */
    val fullyPaidRequiredCount: Int,
    /** Индексы обязательных месяцев окна, за которые участник должен платить (для плитки). */
    val requiredMonthIndices: List<Int>,
)

/** Сводка группы для отображения и записи на лист. */
data class GroupSummary(
    val months: List<MonthPlan>,
    val rows: List<MemberSummary>,
    /** Суммы по столбцам: по месяцам + внесено + доля расходов + баланс. */
    val totals: List<Double>,
) {
    val activeCount: Int get() = rows.size
    val requiredMonths: List<MonthPlan> get() = months.filter { it.required }

    /** Индексы в months/rows[].perMonth/totals, соответствующие обязательным месяцам. */
    val requiredIndices: List<Int> get() = months.indices.filter { months[it].required }
}

object SummaryCalculator {

    /** Префикс комментария платежей, привязанных к сборам. */
    const val COLLECTION_TAG_PREFIX = "Сбор: "

    /** Префикс комментария платежей-возвратов средств участнику. */
    const val REFUND_TAG_PREFIX = "Возврат: "

    /** Префикс комментария платежей-возвратов излишка сверх доли по сбору. */
    const val COLLECTION_REFUND_TAG_PREFIX = "Возврат из сбора: "

    /** Считывает полную сводку из данных группы. */
    fun compute(group: GroupData): GroupSummary {
        val months = group.months
        // Платежи по сборам («Сбор: …», «Возврат из сбора: …») включаются в «внесено» независимо
        // от флага «в общий бюджет»: внесённые деньги должны быть видны в сводке всегда.
        // Возвраты («Возврат: …») вычитаются.
        val basePaidByMember: Map<String, Double> = group.payments
            .filterNot {
                it.comment.startsWith(COLLECTION_TAG_PREFIX) || it.comment.startsWith(REFUND_TAG_PREFIX)
            }
            .groupBy { it.member }
            .mapValues { (_, ps) -> ps.sumOf { it.amount } }
        // Вклад в ОТДЕЛЬНЫЙ сбор (sharedWithBudget=false) не является деньгами бюджета: он
        // лежит в самом сборе и учитывается только там. В общий бюджет такой сбор никак
        // не попадает — ни в доход, ни в «Сборы», ни в расходы. Исключение: избыток сверх
        // доли выходит из сбора платежом «Возврат из сбора: X» и учитывается как обычный
        // доход (идут на оплату будущих месяцев). «Сбор: X» без найденной коллекции и
        // общие сборы остаются бюджетным доходом.
        val standaloneCollectionTags = group.collections
            .filter { !it.sharedWithBudget }
            .map { "$COLLECTION_TAG_PREFIX${it.name}" }
            .toSet()
        val positivePaidByMember: Map<String, Double> = group.payments
            .filterNot {
                it.comment in standaloneCollectionTags || it.comment.startsWith(REFUND_TAG_PREFIX)
            }
            .groupBy { it.member }
            .mapValues { (_, ps) -> ps.sumOf { it.amount } }
        val refundsByMember: Map<String, Double> = group.payments
            .filter { it.comment.startsWith(REFUND_TAG_PREFIX) }
            .groupBy { it.member }
            .mapValues { (_, ps) -> ps.sumOf { it.amount } }
        val allPaidByMember: Map<String, Double> = positivePaidByMember.mapValues { (name, total) ->
            round2(total - (refundsByMember[name] ?: 0.0))
        }
        // Справедливая раскладка расходов: доли отличаются максимум на копейку и в сумме
        // дают ровно сумму расходов (см. fairExpenseShares).
        val expenseShares = fairExpenseShares(group.expenses, group.members.map { it.name })
        // Справедливая раскладка долей по сборы (только общие): в сумме — ровно сумма
        // целей, в пределах копейки от target/участников (см. fairCollectionShares).
        val collectionShares = fairCollectionShares(group.collections, group.members.map { it.name })

        val rows = group.activeMembers.map { m ->
            val basePaid = round2(basePaidByMember[m.name] ?: 0.0)
            val totalPaid = round2(allPaidByMember[m.name] ?: 0.0)
            // Взносы начисляются с месяца вступления: предыдущие месяцы окна пропускаем.
            val startIdx = computeStartIndex(months, m.joinDate)
            val requiredMonthIndices = requiredIndicesFor(months, m)
            var remain = basePaid
            val perMonth = months.mapIndexed { i, mp ->
                if (i < startIdx || !mp.required || mp.label() in m.offMonths) {
                    0.0
                } else {
                    val give = minOf(remain, mp.amount.toDouble())
                    remain = round2(remain - give)
                    give
                }
            }
            // Доля по сборам начисляется участнику при создании сбора и всегда отражается
            // в расходах, чтобы баланс оставался «деньги − начисленное». Отдельные сборы
            // (не в общий бюджет) в расходы НЕ входят.
            val collectionShare = round2(collectionShares[m.name] ?: 0.0)
            val expenseShare = round2(
                (expenseShares[m.name] ?: 0.0) + collectionShare,
            )
            val balance = round2(totalPaid - expenseShare)

            var paidThrough: YearMonth? = null
            var fullCount = 0
            for (i in startIdx until months.size) {
                val mp = months[i]
                if (!mp.required || mp.label() in m.offMonths) continue
                if (perMonth[i] >= mp.amount - 0.005) {
                    paidThrough = mp.month
                    fullCount++
                } else {
                    break
                }
            }

            MemberSummary(m, perMonth, totalPaid, expenseShare, collectionShare, balance, paidThrough, fullCount, requiredMonthIndices)
        }

        val n = months.size
        val totals = ArrayList<Double>(n + 4)
        for (i in 0 until n) totals.add(round2(rows.sumOf { it.perMonth[i] }))
        totals.add(round2(rows.sumOf { it.totalPaid }))
        totals.add(round2(rows.sumOf { it.collectionShare }))
        totals.add(round2(rows.sumOf { it.expenseShare }))
        totals.add(round2(rows.sumOf { it.balance }))

        return GroupSummary(months, rows, totals)
    }

    /**
     * Индекс первого месяца окна, с которого участник должен платить (его месяц вступления).
     * Если дата вступления пуста/не разбирается — 0 (существующее поведение);
     * если вступление позже всех месяцев окна — месяцев.size (обязанностей в окне нет).
     */
    private fun computeStartIndex(months: List<MonthPlan>, joinDate: String): Int {
        val join = runCatching { YearMonth.from(LocalDate.parse(joinDate)) }.getOrNull()
            ?: return 0
        val idx = months.indexOfFirst { it.required && !it.month.isBefore(join) }
        return if (idx < 0) months.size else idx
    }

    /** Индексы обязательных месяцев окна, за которые участник должен платить. */
    private fun requiredIndicesFor(months: List<MonthPlan>, m: MemberData): List<Int> {
        val startIdx = computeStartIndex(months, m.joinDate)
        return months.indices.filter { i ->
            months[i].required && i >= startIdx && months[i].label() !in m.offMonths
        }
    }

    /** Строки для записи на лист «Сводка». Отображаются только обязательные месяцы. */
    fun summaryRows(group: GroupData, s: GroupSummary): List<List<Any?>> {
        val months = s.months
        val columns = s.requiredIndices
        val rows = mutableListOf<List<Any?>>()
        rows.add(listOf(group.title))
        rows.add(emptyList())

        val header = mutableListOf<Any?>("Участник")
        header.addAll(columns.map { months[it].label() })
        header.addAll(listOf("Внесено", "Сборы", "Доля расходов", "Баланс"))
        rows.add(header)

        s.rows.forEach { r ->
            val row = mutableListOf<Any?>(r.member.name)
            row.addAll(columns.map { c -> if (r.perMonth[c] > 0) money(r.perMonth[c]) else "" })
            row.addAll(listOf(money(r.totalPaid), money(r.collectionShare), money(r.expenseShare), money(r.balance)))
            rows.add(row)
        }

        rows.add(emptyList())
        val totalRow = mutableListOf<Any?>("Итого")
        totalRow.addAll(columns.map { money(s.totals[it]) })
        totalRow.addAll(
            listOf(
                money(s.totals[months.size]),
                money(s.totals[months.size + 1]),
                money(s.totals[months.size + 2]),
                money(s.totals[months.size + 3]),
            ),
        )
        rows.add(totalRow)
        rows.add(emptyList())

        rows.add(listOf("РАСХОДЫ: по дате, сумме и кругу участников на момент записи"))
        if (group.expenses.isEmpty()) {
            rows.add(listOf("Пока нет расходов"))
        } else {
            rows.add(listOf("Дата", "Описание", "Сумма", "Участники", "Доля на каждого"))
            group.expenses.forEach { e ->
                rows.add(
                    listOf(
                        e.date,
                        e.description,
                        money(e.amount),
                        if (e.participants.isEmpty()) "все" else e.participants.joinToString("; "),
                        money(e.averageShare),
                    ),
                )
            }
        }

        val excluded = excludedRows(group)
        if (excluded.isNotEmpty()) {
            rows.add(emptyList())
            rows.add(listOf("ИСКЛЮЧЁННЫЕ: внесено − доля расходов = возврат"))
            rows.add(listOf("Имя", "Внесено", "Доля расходов", "Возврат"))
            excluded.forEach { rows.add(it) }
        }
        return rows
    }

    /** Строки по исключённым участникам: имя, внесено, доля расходов, возврат. */
    fun excludedRows(group: GroupData): List<List<Any?>> {
        val removed = group.members.filter { !it.active }
        if (removed.isEmpty()) return emptyList()
        val expenseShares = fairExpenseShares(group.expenses, group.members.map { it.name })
        val paid = group.payments
            .groupBy { it.member }
            .mapValues { (_, ps) ->
                ps.filterNot { it.comment.startsWith(REFUND_TAG_PREFIX) }.sumOf { it.amount } -
                    ps.filter { it.comment.startsWith(REFUND_TAG_PREFIX) }.sumOf { it.amount }
            }
        return removed.map { m ->
            val totalPaid = round2(paid[m.name] ?: 0.0)
            val expenseShare = round2(expenseShares[m.name] ?: 0.0)
            listOf<Any?>(
                m.name,
                money(totalPaid),
                money(expenseShare),
                money(round2(totalPaid - expenseShare)),
            )
        }
    }

    /** Сводная матрица для пустой группы (только что созданной). */
    fun emptySummaryRows(group: GroupData, months: List<MonthPlan>): List<List<Any?>> {
        val s = GroupSummary(
            months = months,
            rows = group.activeMembers.map { m ->
                MemberSummary(
                    member = m,
                    perMonth = months.map { 0.0 },
                    totalPaid = 0.0,
                    expenseShare = 0.0,
                    collectionShare = 0.0,
                    balance = 0.0,
                    paidThrough = null,
                    fullyPaidRequiredCount = 0,
                    requiredMonthIndices = requiredIndicesFor(months, m),
                )
            },
            totals = List(months.size + 4) { 0.0 },
        )
        return summaryRows(group, s)
    }
}