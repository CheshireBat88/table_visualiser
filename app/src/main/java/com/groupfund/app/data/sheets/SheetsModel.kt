package com.groupfund.app.data.sheets

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** План месяца в окне группы. */
data class MonthPlan(
    val month: YearMonth,
    val required: Boolean,
    val amount: Int,
) {
    /** Читаемый код, например "2026-09". */
    fun label(): String = "%04d-%02d".format(month.year, month.monthValue)

    companion object {
        fun of(label: String): YearMonth {
            val p = label.split("-")
            return YearMonth.of(p[0].toInt(), p[1].toInt())
        }
    }
}

/** Участник группы. */
data class MemberData(
    val name: String,
    val email: String,
    val joinDate: String,
    val active: Boolean,
    val removedAt: String?,
    val refund: Double?,
    val birthday: String = "",
    /** Месяцы, в которые участник освобождён от взноса (метки вида «2026-10»). */
    val offMonths: List<String> = emptyList(),
) {
    /** День рождения компактно: "ДД.ММ". Поддерживает "гггг-мм-дд" и "ДД.ММ". */
    val birthdayShort: String
        get() = Regex("""^\d{4}[-.]\d{2}[-.]\d{2}$""").find(birthday)?.let {
            val parts = it.value.split('-', '.')
            "%02d.%02d".format(parts[2].toInt(), parts[1].toInt())
        } ?: if (birthday.isBlank()) "" else birthday
}

/** Запись о платеже участника. */
data class PaymentData(
    val member: String,
    val date: String,
    val amount: Double,
    val comment: String,
)

/** Запись о расходе (срез участников на момент записи). */
data class ExpenseData(
    val date: String,
    val description: String,
    val amount: Double,
    val participants: List<String>,
) {
    /** Доля с каждого участника (округляется вверх до копейки — все платят одинаково). */
    val averageShare: Double
        get() = if (participants.isEmpty()) amount else ceilCent(amount / participants.size)

    /** Итого с учётом округления: averageShare × число участников (может быть чуть больше amount). */
    val fairTotal: Double
        get() = round2(averageShare * participants.size)
}

/**
 * Равномерная раскладка расходов: доля каждого участника = его доля в каждом расходе,
 * округлённая ВВЕРХ до копейки. Если сумма не делится поровну, сбор «чуть подрастает»,
 * но никто не платит на копейку больше соседа (пример: 1430.01 на 13 → всем по 110.01,
 * итого 1430.13 вместо 12×110.00 + 1×110.01). Раньше использовались «наибольшие остатки»,
 * и кто-то систематически доплачивал копейку.
 */
fun fairExpenseShares(expenses: List<ExpenseData>, allMembers: List<String>): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (e in expenses) {
        if (e.amount <= 0.0) continue
        val pool = e.participants.ifEmpty { allMembers }
        if (pool.isEmpty()) continue
        val perHead = ceilCent(e.amount / pool.size)
        for (p in pool) out[p] = round2((out[p] ?: 0.0) + perHead)
    }
    return out
}

/**
 * Равномерная раскладка долей по ОБЩИМ сборам (sharedWithBudget=true): доля с участника =
 * цель, делённая на участников и округлённая ВВЕРХ до копейки. Все платят одинаково,
 * сумма долей чуть больше целей (пример: 380 на 13 → по 29.24 с человека, итого 380.12).
 */
fun fairCollectionShares(collections: List<CollectionData>, allMembers: List<String>): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (c in collections) {
        if (!c.sharedWithBudget || c.target <= 0.0) continue
        val pool = c.participants.ifEmpty { allMembers }
        if (pool.isEmpty()) continue
        val perHead = ceilCent(c.target / pool.size)
        for (p in pool) out[p] = round2((out[p] ?: 0.0) + perHead)
    }
    return out
}

/** Черновик новой группы из мастера создания. */
data class GroupDraft(
    val title: String,
    val baseAmount: Int,
    val currency: String = "RUB",
    val months: List<MonthPlan>,
    val members: List<String>,
    val memberBirthdays: Map<String, String> = emptyMap(),
)

/**
 * Отдельный сбор вне ежемесячных платежей. Цель делится поровну на участников.
 * За каждым участником хранится фактически внесённая сумма (`contrib`).
 * Если сумма сбора уменьшилась — избыток автоматически уходит в месячную оплату;
 * если увеличилась — виден недобор (дефицит), который не списывается из взносов.
 */
data class CollectionData(
    val name: String,
    val date: String,
    val target: Double,
    val participants: List<String>,
    /** Внесённые суммы по участникам (имя → сколько реально сдал). */
    val contrib: Map<String, Double> = emptyMap(),
    /**
     * true — сбор входит в общий бюджет (доля учитывается в сводке, взносы — в балансе).
     * false — отдельный сбор: не входит в общие траты, показывается в сводке отдельным
     * блоком, пока все участники не внесут деньги.
     */
    val sharedWithBudget: Boolean = true,
) {
/** Доля с одного участника (округляется ВВЕРХ до копейки — все платят одинаково). */
    val sharePerPerson: Double
        get() = if (participants.isEmpty()) 0.0 else ceilCent(target / participants.size)

    /** Участники, полностью покрывшие свою долю. */
    val paid: List<String>
        get() = participants.filter { (contrib[it] ?: 0.0) >= sharePerPerson - 0.005 }

    /** Сколько участник реально внёс. */
    fun contributed(member: String): Double = contrib[member] ?: 0.0

    /** Сколько из внесённого засчитывается в сбор (избыток сверх доли — не в счёт сбора). */
    fun countedContribution(member: String): Double =
        minOf(contrib[member] ?: 0.0, sharePerPerson)

    /** Недобор до полной доли (0, если участник уже покрыл её). */
    fun deficit(member: String): Double = maxOf(0.0, round2(sharePerPerson - contributed(member)))
}

/** Содержимое группы, собранное из листов таблицы. */
data class GroupData(
    val spreadsheetId: String,
    val title: String,
    val baseAmount: Int,
    val currency: String,
    val createdAt: String,
    val months: List<MonthPlan>,
    val members: List<MemberData>,
    val payments: List<PaymentData>,
    val expenses: List<ExpenseData>,
    val collections: List<CollectionData> = emptyList(),
) {
    val activeMembers: List<MemberData> get() = members.filter { it.active }
}

/** Форматирование денег: целые без дробной части, иначе 2 знака. */
fun money(v: Double): String = round2(v).let { r ->
    if (r == Math.floor(r) && !r.isInfinite()) r.toLong().toString() else "%.2f".format(Locale.US, r)
}

fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

/** Округление вверх до копейки: одинаковые доли при сумме, не делящейся без остатка. */
internal fun ceilCent(v: Double): Double = Math.ceil(v * 100.0 - 1e-9) / 100.0

internal fun cellText(v: Any?): String = when (v) {
    null -> ""
    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    else -> v.toString().trim()
}

internal fun cellNum(v: Any?): Double = when (v) {
    null -> 0.0
    is Number -> v.toDouble()
    else -> {
        // Терпим ручной ввод: «500», «500,00», «1 234,56 ₽», «-300».
        val s = v.toString().trim().replace(" ", "").replace("\u00A0", "").replace(',', '.')
        val num = s.takeWhile { it.isDigit() || it == '.' || it == '-' }
        num.toDoubleOrNull() ?: 0.0
    }
}

/** Кодирование/декодирование листов таблицы. */
object SheetCodec {

    const val YES = "ДА"
    const val NO = "—"
    const val ACTIVE = "Активен"
    const val REMOVED = "Удалён"

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val settingsDateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun today(): String = LocalDate.now().format(dayFmt)

    // ---------- Настройки ----------

    fun settingsRows(
        title: String,
        baseAmount: Int,
        currency: String,
        createdAt: String,
        months: List<MonthPlan>,
    ): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>(
            listOf("groupName", title),
            listOf("baseAmount", baseAmount),
            listOf("currency", currency),
            listOf("createdAt", createdAt),
            emptyList(),
            listOf("Месяц", "Обязательный", "Сумма"),
        )
        months.forEach { mp ->
            rows.add(
                listOf(
                    mp.label(),
                    if (mp.required) YES else NO,
                    if (mp.required) mp.amount else "",
                ),
            )
        }
        return rows
    }

    fun parseSettings(rows: List<List<Any?>>): Pair<GroupDataHdr, List<MonthPlan>> {
        var groupName = ""
        var baseAmount = 0
        var currency = "RUB"
        var createdAt = ""
        val months = mutableListOf<MonthPlan>()

        rows.forEachIndexed { i, row ->
            when (row.getOrNull(0)?.let(::cellText)) {
                "groupName" -> groupName = cellText(row.getOrNull(1))
                "baseAmount" -> baseAmount = cellNum(row.getOrNull(1)).toInt()
                "currency" -> currency = cellText(row.getOrNull(1)).ifEmpty { "RUB" }
                "createdAt" -> createdAt = cellText(row.getOrNull(1))
            }
            // строка месяца: первый столбец похож на 2026-09
            val c0 = cellText(row.getOrNull(0))
            if (c0.matches(Regex("""\d{4}-\d{2}"""))) {
                val month = try {
                    YearMonth.of(c0.substring(0, 4).toInt(), c0.substring(5, 7).toInt())
                } catch (e: Exception) {
                    null
                }
                if (month != null) {
                    val required = cellText(row.getOrNull(1)) == YES
                    val amount = if (required) cellNum(row.getOrNull(2)).toInt() else 0
                    months.add(MonthPlan(month, required, amount))
                }
            }
        }
        months.sortBy { it.month }
        return GroupDataHdr(groupName, baseAmount, currency, createdAt) to months
    }

    data class GroupDataHdr(
        val title: String,
        val baseAmount: Int,
        val currency: String,
        val createdAt: String,
    )

    // ---------- Участники ----------

    fun membersRows(data: List<MemberData>): List<List<Any?>> {
        val rows = mutableListOf<List<Any?>>(
            listOf("Имя", "Email", "Дата вступления", "Статус", "Дата удаления", "Возврат", "День рождения", "Без взноса"),
        )
        data.sortedBy { it.name.lowercase() }.forEach { m ->
            rows.add(
                listOf(
                    m.name,
                    m.email,
                    if (m.joinDate.isEmpty()) today() else m.joinDate,
                    if (m.active) ACTIVE else REMOVED,
                    m.removedAt ?: "",
                    m.refund?.let(::money) ?: "",
                    m.birthday,
                    m.offMonths.joinToString("; "),
                ),
            )
        }
        return rows
    }

    fun parseMembers(rows: List<List<Any?>>): List<MemberData> {
        val out = mutableListOf<MemberData>()
        rows.forEachIndexed { i, row ->
            if (i == 0) return@forEachIndexed
            val name = cellText(row.getOrNull(0))
            if (name.isEmpty()) return@forEachIndexed
            val status = cellText(row.getOrNull(3))
            out.add(
                MemberData(
                    name = name,
                    email = cellText(row.getOrNull(1)),
                    joinDate = cellText(row.getOrNull(2)),
                    active = status.isEmpty() || status == ACTIVE,
                    removedAt = cellText(row.getOrNull(4)).ifEmpty { null },
                    refund = cellText(row.getOrNull(5)).toDoubleOrNull(),
                    birthday = cellText(row.getOrNull(6)),
                    offMonths = cellText(row.getOrNull(7))
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                ),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // ---------- Платежи ----------

    fun paymentsHeader(): List<List<Any?>> = listOf(listOf("Имя", "Дата", "Сумма", "Комментарий"))

    fun paymentsRows(data: List<PaymentData>): List<List<Any?>> {
        val rows = mutableListOf(listOf("Имя", "Дата", "Сумма", "Комментарий") as List<Any?>)
        data.forEach { p ->
            rows.add(listOf(p.member, p.date, p.amount, p.comment))
        }
        return rows
    }

    fun parsePayments(rows: List<List<Any?>>): List<PaymentData> {
        val out = mutableListOf<PaymentData>()
        rows.forEachIndexed { i, row ->
            if (i == 0) return@forEachIndexed
            val name = cellText(row.getOrNull(0))
            if (name.isEmpty()) return@forEachIndexed
            out.add(
                PaymentData(
                    member = name,
                    date = cellText(row.getOrNull(1)),
                    amount = cellNum(row.getOrNull(2)),
                    comment = cellText(row.getOrNull(3)),
                ),
            )
        }
        return out
    }

    // ---------- Расходы ----------

    fun expensesHeader(): List<List<Any?>> =
        listOf(listOf("Дата", "Описание", "Сумма", "Участники", "Доля на каждого"))

    fun expensesRows(data: List<ExpenseData>): List<List<Any?>> {
        val rows = mutableListOf(listOf("Дата", "Описание", "Сумма", "Участники", "Доля на каждого") as List<Any?>)
        data.forEach { e ->
            rows.add(
                listOf(
                    e.date,
                    e.description,
                    e.amount,
                    e.participants.joinToString("; "),
                    money(e.averageShare),
                ),
            )
        }
        return rows
    }

    fun parseExpenses(rows: List<List<Any?>>): List<ExpenseData> {
        val out = mutableListOf<ExpenseData>()
        rows.forEachIndexed { i, row ->
            if (i == 0) return@forEachIndexed
            val desc = cellText(row.getOrNull(1))
            if (desc.isEmpty()) return@forEachIndexed
            out.add(
                ExpenseData(
                    date = cellText(row.getOrNull(0)),
                    description = desc,
                    amount = cellNum(row.getOrNull(2)),
                    participants = cellText(row.getOrNull(3))
                        .split(';', ';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                ),
            )
        }
        return out
    }

    // ---------- Сборы ----------

    fun collectionsHeader(): List<List<Any?>> =
        listOf(listOf("Название", "Дата", "Цель", "На кого", "Взносы (имя=сумма)", "В общий бюджет"))

    fun collectionsRows(data: List<CollectionData>): List<List<Any?>> {
        val rows = mutableListOf(listOf(
            "Название", "Дата", "Цель", "На кого", "Взносы (имя=сумма)", "В общий бюджет",
        ) as List<Any?>)
        data.forEach { c ->
            rows.add(
                listOf(
                    c.name,
                    c.date,
                    c.target,
                    c.participants.joinToString("; "),
                    c.contrib.toSortedMap().map { (k, v) -> "$k=${money(v)}" }.joinToString("; "),
                    if (c.sharedWithBudget) "да" else "нет",
                ),
            )
        }
        return rows
    }

fun parseCollections(rows: List<List<Any?>>): List<CollectionData> {
        // Собираем по имени: дубли с одинаковым именем (артефакты сбойной записи) объединяем,
        // иначе вкладка «Сборы» показывала бы взносы как 0 для записи без данных.
        val byName = LinkedHashMap<String, CollectionData>()
        rows.forEachIndexed { i, row ->
            if (i == 0) return@forEachIndexed
            val name = cellText(row.getOrNull(0))
            if (name.isEmpty()) return@forEachIndexed
            val participants = splitNamedList(row.getOrNull(3))
            val target = cellNum(row.getOrNull(2))
            val share = if (participants.isEmpty()) 0.0 else ceilCent(target / participants.size)
            val raw = splitNamedList(row.getOrNull(4))
            // Новый формат «имя=сумма; …»; старый — просто список имён (считаем их сдавшими полную долю).
            val contrib: Map<String, Double> = if (raw.any { it.contains('=') }) {
                raw.mapNotNull { token ->
                    val eq = token.indexOf('=')
                    if (eq < 0) null
                    else token.substring(0, eq).trim() to cellNum(token.substring(eq + 1).trim())
                }.toMap()
            } else {
                raw.associateWith { share }
            }
            val c = CollectionData(
                name = name,
                date = cellText(row.getOrNull(1)),
                target = target,
                participants = participants,
                contrib = contrib,
                sharedWithBudget = cellText(row.getOrNull(5)) != "нет",
            )
            val prev = byName[name]
            byName[name] = if (prev == null) c else mergeCollectionData(prev, c)
        }
        return byName.values.toList()
    }

    /**
     * Объединяет записи сбора с одинаковым именем: суммарные участники, максимум взноса.
     *
     * Взнос каждого участника хранится ОДНИМ числом (см. applyContribution), поэтому
     * строки с одинаковым именем — это снимки одной и той же записи (артефакт сбойной
     * записи), а не отдельные транши. Поэтому берём максимум, а не сумму: сложение
     * удвоило бы вклад при дублировании строки.
     */
    private fun mergeCollectionData(a: CollectionData, b: CollectionData): CollectionData {
        val contrib = a.contrib.toMutableMap()
        b.contrib.forEach { (k, v) ->
            val prev = contrib[k]
            contrib[k] = if (prev == null) v else maxOf(prev, v)
        }
        return CollectionData(
            name = a.name,
            date = a.date.ifEmpty { b.date },
            target = if (a.target != 0.0) a.target else b.target,
            participants = (a.participants + b.participants).distinct(),
            contrib = contrib,
            sharedWithBudget = a.sharedWithBudget || b.sharedWithBudget,
        )
    }

    private fun splitNamedList(v: Any?): List<String> =
        cellText(v).split(';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Даты по умолчанию для ввода (редактируемый формат). */
    fun todayEditable(): String = LocalDate.now().format(settingsDateFmt)
}