package com.groupfund.app.data.sheets

/**
 * Диагностика согласованности «Платежи ↔ Сборы».
 *
 * Канонический вид (см. SheetsRepository.replaceCollectionPayments): из взноса участника
 * (contrib) строки «Сбор: X»/«Возврат из сбора: X» строятся так:
 *   - взнос <= доли              → одна строка «Сбор: X» на весь взнос, возвратов нет;
 *   - взнос > доли               → строка на долю «Сбор: X» + строка «Возврат из сбора: X»
 *                                  на излишек.
 * Допустимы два легальных вида для взноса выше доли: канонический консолидированный и
 * неконсолидированный (несколько строк «Сбор: X», появляется при переносе из месячных
 * взносов reclassifyMonthlyToCollection без последующего переизбытка).
 */
object CollectionConsistency {

    private const val EPS = 0.02

    /** Возвращает человекочитаемые предупреждения о расхождениях, или пустой список. */
    fun mismatches(group: GroupData): List<String> {
        val warnings = mutableListOf<String>()
        val collectionsByName = group.collections.associateBy { it.name }

        // Суммы строк «Сбор» и «Возврат из сбора» по (сбор, участник).
        val paidByMember = mutableMapOf<Pair<String, String>, Double>()
        val returnedByMember = mutableMapOf<Pair<String, String>, Double>()
        group.payments.forEach { p ->
            val col = when {
                p.comment.startsWith("Сбор: ") ->
                    p.comment.removePrefix("Сбор: ").trim() to true
                p.comment.startsWith("Возврат из сбора: ") ->
                    p.comment.removePrefix("Возврат из сбора: ").trim() to false
                else -> return@forEach
            }
            val key = col.first to p.member
            if (col.first !in collectionsByName) {
                warnings += "«${p.member}» ${money(p.amount)} в «Платежах» (${p.comment}), но сбора " +
                    "«${col.first}» больше нет — запись лишняя."
                return@forEach
            }
            val bucket = if (col.second) paidByMember else returnedByMember
            bucket[key] = round2((bucket[key] ?: 0.0) + p.amount)
        }

        group.collections.forEach { c ->
            val share = c.sharePerPerson
            c.participants.forEach { m ->
                val contrib = round2(c.contributed(m))
                val paid = paidByMember[c.name to m] ?: 0.0
                val returned = returnedByMember[c.name to m] ?: 0.0
                val consistent = if (contrib >= share - 0.005) {
                    // Выше доли: либо консолидированный вид, либо накопленные строки «Сбор».
                    (approx(paid, share) && approx(returned, round2(contrib - share))) ||
                        (approx(paid, contrib) && approx(returned, 0.0))
                } else {
                    approx(paid, contrib) && approx(returned, 0.0)
                }
                if (!consistent) {
                    warnings += "Сбор «${c.name}»: у «$m» учтено ${money(contrib)}, а в «Платежах» " +
                        "числится ${money(paid)}${if (returned > 0) " (+${money(returned)} возврат)" else ""}."
                }
            }
            // Платежи от участников, которых нет в сборе.
            paidByMember.keys.forEach { (col, m) ->
                if (col == c.name && m !in c.participants) {
                    warnings += "Сбор «${c.name}»: «$m» числится в «Платежах», но не входит в сбор."
                }
            }
        }

        return warnings.distinct()
    }

    private fun approx(a: Double, b: Double) = kotlin.math.abs(a - b) < EPS
}