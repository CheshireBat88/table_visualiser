package com.groupfund.app.data.sheets

import java.time.LocalDate
import java.time.YearMonth

/** Разбивка по участнику для сводки за период. */
data class PeriodMemberLine(
    val name: String,
    /** Доли обычных трат (расходы + общие сборы) за период. */
    val expenseShare: Double,
    /** Доли внебюджетных трат (отдельные сборы) за период. */
    val offBudgetShare: Double,
) {
    val total: Double get() = round2(expenseShare + offBudgetShare)
}

/**
 * Сводка группы за период (неделя/месяц/произвольный):
 * обычные траты, траты вне бюджета (отдельные сборы) и поступления.
 *
 * Специализация путём вступления в силу в периоде:
 * - траты — расходы [ExpenseData] с датой в периоде;
 * - траты вне бюджета — отдельные сборы (sharedWithBudget=false), созданные в периоде
 *   (доли считаются в момент создания сбора, как и в общей сводке);
 * - поступления — платежи в периоде без возвратов («Возврат: …») и без взносов
 *   в отдельные сборы (их деньги лежат в пуле сбора, а не в общем бюджете);
 * - разбивка по участникам — начисленные доли (одинаковые для равных кругов).
 */
data class PeriodSummary(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val expenses: List<ExpenseData>,
    val expensesTotal: Double,
    val offBudgetCollections: List<CollectionData>,
    val offBudgetTotal: Double,
    val income: List<PaymentData>,
    val incomeTotal: Double,
    val members: List<PeriodMemberLine>,
) {
    /** Разница «поступления − траты − вне бюджета»: сколько добавилось в бюджет за период. */
    val delta: Double get() = round2(incomeTotal - expensesTotal - offBudgetTotal)
}

/** Строка внебюджетных трат в «Моей информации»: отдельный сбор и доля участника. */
data class OffBudgetRow(
    val month: YearMonth,
    val name: String,
    val share: Double,
)

object PeriodStatistics {

    /** Круг участников расхода/сбора: пустой список означает всех участников группы. */
    private fun poolOf(participants: List<String>, allMembers: List<String>): List<String> =
        participants.ifEmpty { allMembers }

    /** Доля одного участника при раскладке (округление вверх до копейки). */
    private fun shareOf(total: Double, poolSize: Int): Double =
        if (poolSize == 0 || total <= 0.0) 0.0 else ceilCent(total / poolSize)

    private fun yearMonthOf(date: String): YearMonth? =
        runCatching { YearMonth.from(LocalDate.parse(date)) }.getOrNull()

    private fun inRange(date: String, from: LocalDate, to: LocalDate): Boolean {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
        return !d.isBefore(from) && !d.isAfter(to)
    }

    /** Сводка по группе за [fromDate]–[toDate] включительно. */
    fun compute(group: GroupData, fromDate: LocalDate, toDate: LocalDate): PeriodSummary {
        val allNames = group.members.map { it.name }
        val expenses = group.expenses.filter { inRange(it.date, fromDate, toDate) }
        val sharedCollections = group.collections
            .filter { it.sharedWithBudget && inRange(it.date, fromDate, toDate) }
        val standaloneCollections = group.collections
            .filter { !it.sharedWithBudget && inRange(it.date, fromDate, toDate) }

        val expensesTotal = round2(expenses.sumOf { it.amount })
        val offBudgetTotal = round2(standaloneCollections.sumOf { it.target })

        // Поступления: все платежи периода, кроме возвратов и взносов в отдельные сборы.
        val standaloneTags = standaloneCollections
            .map { "${SummaryCalculator.COLLECTION_TAG_PREFIX}${it.name}" }
            .toSet()
        val income = group.payments.filter { p ->
            inRange(p.date, fromDate, toDate) &&
                !p.comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX) &&
                p.comment !in standaloneTags
        }
        val incomeTotal = round2(income.sumOf { it.amount })

        // Доли участников: расходы и общие сборы периода + отдельные сборы периода.
        val expenseShares = HashMap<String, Double>()
        expenses.forEach { e ->
            val pool = poolOf(e.participants, allNames)
            val perHead = shareOf(e.amount, pool.size)
            for (p in pool) expenseShares[p] = round2((expenseShares[p] ?: 0.0) + perHead)
        }
        sharedCollections.forEach { c ->
            val pool = poolOf(c.participants, allNames)
            val perHead = shareOf(c.target, pool.size)
            for (p in pool) expenseShares[p] = round2((expenseShares[p] ?: 0.0) + perHead)
        }
        val offBudgetShares = HashMap<String, Double>()
        standaloneCollections.forEach { c ->
            val pool = poolOf(c.participants, allNames)
            val perHead = shareOf(c.target, pool.size)
            for (p in pool) offBudgetShares[p] = round2((offBudgetShares[p] ?: 0.0) + perHead)
        }

        val members = group.members.map { m ->
            PeriodMemberLine(
                name = m.name,
                expenseShare = round2(expenseShares[m.name] ?: 0.0),
                offBudgetShare = round2(offBudgetShares[m.name] ?: 0.0),
            )
        }.sortedBy { it.name.lowercase() }

        return PeriodSummary(
            fromDate = fromDate,
            toDate = toDate,
            expenses = expenses.sortedBy { it.date },
            expensesTotal = expensesTotal,
            offBudgetCollections = standaloneCollections.sortedBy { it.date },
            offBudgetTotal = offBudgetTotal,
            income = income.sortedBy { it.date },
            incomeTotal = incomeTotal,
            members = members,
        )
    }

    /**
     * Доли обычных трат участника по месяцам ([months] — окно группы).
     * Расходы и общие сборы учитываются в месяце их даты.
     */
    fun memberExpenseSharesByMonth(
        group: GroupData,
        member: String,
        months: List<MonthPlan>,
    ): Map<YearMonth, Double> {
        val allNames = group.members.map { it.name }
        val out = HashMap<YearMonth, Double>()
        val fill = { ym: YearMonth, add: Double ->
            out[ym] = round2((out[ym] ?: 0.0) + add)
        }
        group.expenses.forEach { e ->
            val ym = yearMonthOf(e.date) ?: return@forEach
            if (member in poolOf(e.participants, allNames)) {
                fill(ym, shareOf(e.amount, poolOf(e.participants, allNames).size))
            }
        }
        group.collections.filter { it.sharedWithBudget }.forEach { c ->
            val ym = yearMonthOf(c.date) ?: return@forEach
            if (member in poolOf(c.participants, allNames)) {
                fill(ym, shareOf(c.target, poolOf(c.participants, allNames).size))
            }
        }
        return out
    }

    /** Внебюджетные траты участника: отдельные сборы по месяцам создания и доля в каждом. */
    fun memberOffBudgetRows(group: GroupData, member: String): List<OffBudgetRow> {
        val allNames = group.members.map { it.name }
        return group.collections
            .filter { !it.sharedWithBudget }
            .mapNotNull { c ->
                val ym = yearMonthOf(c.date) ?: return@mapNotNull null
                if (member !in poolOf(c.participants, allNames)) return@mapNotNull null
                OffBudgetRow(ym, c.name, shareOf(c.target, poolOf(c.participants, allNames).size))
            }
            .sortedWith(compareByDescending<OffBudgetRow> { it.month }.thenBy { it.name.lowercase() })
    }
}