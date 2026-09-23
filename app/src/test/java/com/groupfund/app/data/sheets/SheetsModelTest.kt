package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Тесты парсинга чисел, ячеек и кодеков листов. */
class SheetsModelTest {

    @Test
    fun `cellNum терпит ручной ввод`() {
        assertEquals(0.0, cellNum(null), 0.0)
        assertEquals(42.0, cellNum(42), 0.0)
        assertEquals(500.0, cellNum("500"), 0.0)
        assertEquals(500.0, cellNum("500,00"), 0.0)
        assertEquals(1234.56, cellNum("1 234,56 ₽"), 0.0)
        assertEquals(1234.56, cellNum("1\u00A0234,56 ₽"), 0.0)
        assertEquals(-300.0, cellNum("-300"), 0.0)
        assertEquals(0.0, cellNum("мусор"), 0.0)
    }

    @Test
    fun `money форматирует целые и дробные`() {
        assertEquals("5", money(5.0))
        assertEquals("5.50", money(5.5))
        assertEquals("5.05", money(5.05))
        assertEquals("0", money(0.0))
        assertEquals("-7", money(-7.0))
    }

    @Test
    fun `parsePayments круговая запись`() {
        val data = listOf(
            PaymentData("A", "2026-01-01", 100.5, "за январь"),
            PaymentData("B", "2026-01-02", 50.0, "Сбор: Подарок"),
        )
        val parsed = SheetCodec.parsePayments(SheetCodec.paymentsRows(data))
        assertEquals(data, parsed)
    }

    @Test
    fun `parseExpenses круговая запись`() {
        val data = listOf(
            ExpenseData("2026-01-01", "Пицца", 1430.0, listOf("A", "B", "C")),
        )
        val parsed = SheetCodec.parseExpenses(SheetCodec.expensesRows(data))
        assertEquals(data, parsed)
    }

    @Test
    fun `parseCollections дубли с одинаковым именем объединяются`() {
        val rows = listOf(
            listOf<Any?>("Название", "Дата", "Цель", "На кого", "Взносы", "В общий бюджет"),
            listOf<Any?>("Подарок", "2026-01-01", 300.0, "A; B", "A=100", "да"),
            listOf<Any?>("Подарок", "2026-01-05", 300.0, "B; C", "B=100; C=100", "да"),
        )
        val parsed = SheetCodec.parseCollections(rows)
        assertEquals(1, parsed.size)
        val c = parsed[0]
        assertEquals("Подарок", c.name)
        assertEquals(listOf("A", "B", "C").sorted(), c.participants.sorted())
        assertEquals(100.0, c.contributed("A"), 0.0)
        assertEquals(100.0, c.contributed("B"), 0.0)
        assertEquals(100.0, c.contributed("C"), 0.0)
    }

    @Test
    fun `parseCollections дубли не суммируют взнос одного участника`() {
        val rows = listOf(
            listOf<Any?>("Название", "Дата", "Цель", "На кого", "Взносы", "В общий бюджет"),
            listOf<Any?>("Подарок", "2026-01-01", 300.0, "A; B", "A=100", "да"),
            listOf<Any?>("Подарок", "2026-01-05", 300.0, "A; B", "A=100; B=100", "да"),
        )
        val c = SheetCodec.parseCollections(rows).first()
        assertEquals(100.0, c.contributed("A"), 0.0)
        assertEquals(100.0, c.contributed("B"), 0.0)
    }

    @Test
    fun `parseCollections старый формат без имен - все сдали долю`() {
        val rows = listOf(
            listOf<Any?>("Название", "Дата", "Цель", "На кого", "Взносы", "В общий бюджет"),
            listOf<Any?>("Подарок", "2026-01-01", 300.0, "A; B; C", "A; B; C", "да"),
        )
        val c = SheetCodec.parseCollections(rows).first()
        assertEquals(100.0, c.sharePerPerson, 0.0)
        assertEquals(100.0, c.contributed("A"), 0.0)
        assertEquals(3, c.paid.size)
    }

    @Test
    fun `parseCollections отдельный сбор определяется по колонке`() {
        val rows = listOf(
            listOf<Any?>("Название", "Дата", "Цель", "На кого", "Взносы", "В общий бюджет"),
            listOf<Any?>("Отдел", "2026-01-01", 300.0, "A; B", "", "нет"),
        )
        val c = SheetCodec.parseCollections(rows).first()
        assertEquals(false, c.sharedWithBudget)
    }

    @Test
    fun `parseMembers активные и удалённые`() {
        val rows = listOf(
            listOf<Any?>("Имя", "Email", "Дата вступления", "Статус", "Дата удаления", "Возврат", "День рождения", "Без взноса"),
            listOf<Any?>("A", "a@x.ru", "2026-01-01", "Активен", "", "", "2020-05-10", "2026-02"),
            listOf<Any?>("B", "", "2026-01-01", "Удалён", "2026-02-01", "120", "", ""),
        )
        val parsed = SheetCodec.parseMembers(rows)
        assertEquals("A", parsed[0].name)
        assertTrue(parsed[0].active)
        assertEquals(listOf("2026-02"), parsed[0].offMonths)
        assertEquals("10.05", parsed[0].birthdayShort)
        assertEquals(false, parsed[1].active)
        assertEquals(120.0, parsed[1].refund!!, 0.0)
    }

    @Test
    fun `parseSettings читает заголовок и месяцы`() {
        val rows = listOf(
            listOf<Any?>("groupName", "Квартира"),
            listOf<Any?>("baseAmount", 5000),
            listOf<Any?>("currency", "RUB"),
            listOf<Any?>("createdAt", "2026-01-01"),
            emptyList<Any?>(),
            listOf<Any?>("Месяц", "Обязательный", "Сумма"),
            listOf<Any?>("2026-01", "ДА", 5000),
            listOf<Any?>("2026-02", "—", ""),
        )
        val (hdr, months) = SheetCodec.parseSettings(rows)
        assertEquals("Квартира", hdr.title)
        assertEquals(5000, hdr.baseAmount)
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2026, 2)), months.map { it.month })
        assertTrue(months[0].required)
        assertEquals(5000, months[0].amount)
        assertEquals(false, months[1].required)
    }

    @Test
    fun `settingsRows и membersRows круговые`() {
        val sourceMonths = listOf(MonthPlan(YearMonth.of(2026, 1), true, 500))
        val rows = SheetCodec.settingsRows("Кв", 500, "RUB", "2026-01-01", sourceMonths)
        val (hdr, months) = SheetCodec.parseSettings(rows)
        assertEquals("Кв", hdr.title)
        assertEquals(sourceMonths, months)

        val members = listOf(MemberData("B", "", "", true, null, null), MemberData("A", "", "", true, null, null))
        val parsed = SheetCodec.parseMembers(SheetCodec.membersRows(members))
        assertEquals(listOf("A", "B"), parsed.map { it.name })
    }

    @Test
    fun `CollectionData share от цели и количества участников`() {
        val c = CollectionData("X", "2026-01-01", 380.0, (1..13).map { "P$it" }, emptyMap(), true)
        // 380 / 13 = 29.2307… → доля округляется вверх до 29.24.
        assertEquals(29.24, c.sharePerPerson, 0.0)
        assertEquals(29.24, c.deficit("P1"), 0.0)
        assertTrue(c.paid.isEmpty())
        val paid = c.copy(contrib = mapOf("P1" to 29.24))
        assertEquals(1, paid.paid.size)
    }
}