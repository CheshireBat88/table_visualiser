package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверки имён и терпимого разбора флагов из ячеек. */
class InputValidationTest {

    @Test
    fun `memberNameError пропускает обычные имена`() {
        assertNull(memberNameError("Иван"))
        assertNull(memberNameError("Анна-Мария"))
        assertNull(memberNameError("Иван Петров"))
    }

    @Test
    fun `memberNameError запрещает разделители и пустое имя`() {
        assertNotNull(memberNameError(""))
        assertNotNull(memberNameError("   "))
        assertNotNull(memberNameError("Иван;Пётр"))
        assertNotNull(memberNameError("Иван=100"))
        assertNotNull(memberNameError("Иван\nПётр"))
    }

    @Test
    fun `parseYesNo терпит регистр и пробелы`() {
        assertTrue(parseYesNo("да"))
        assertTrue(parseYesNo(" ДА "))
        assertTrue(parseYesNo("Yes"))
        assertTrue(parseYesNo("true"))
        assertTrue(parseYesNo("1"))
        assertFalse(parseYesNo("нет"))
        assertFalse(parseYesNo("НЕТ"))
        assertFalse(parseYesNo("—"))
        assertFalse(parseYesNo("false"))
    }

    @Test
    fun `parseYesNo использует значение по умолчанию`() {
        assertFalse(parseYesNo(null))
        assertTrue(parseYesNo(null, default = true))
        assertTrue(parseYesNo("непонятно", default = true))
        assertFalse(parseYesNo("непонятно", default = false))
    }

    @Test
    fun `parseSettings понимает да в нижнем регистре`() {
        val rows = listOf(
            listOf<Any?>("2026-01", "да", "1500"),
            listOf<Any?>("2026-02", "нет", "1500"),
        )
        val months = SheetCodec.parseSettings(rows).second
        assertEquals(2, months.size)
        assertTrue(months[0].required)
        assertEquals(1500, months[0].amount)
        assertFalse(months[1].required)
        assertEquals(0, months[1].amount)
    }

    @Test
    fun `parseMembers понимает активен в нижнем регистре`() {
        val rows = listOf(
            listOf<Any?>("Имя", "Email", "Дата вступления", "Статус", "Дата удаления", "Возврат", "День рождения", "Без взноса"),
            listOf<Any?>("A", "", "2026-01-01", "активен", "", "", "", ""),
            listOf<Any?>("B", "", "2026-01-01", "Удалён", "", "", "", ""),
        )
        val members = SheetCodec.parseMembers(rows)
        assertTrue(members.first { it.name == "A" }.active)
        assertFalse(members.first { it.name == "B" }.active)
    }

    @Test
    fun `parseCollections терпит нет в другом регистре`() {
        val rows = listOf(
            listOf<Any?>("Название", "Дата", "Цель", "На кого", "Взносы", "В общий бюджет"),
            listOf<Any?>("Общий", "2026-01-01", 100.0, "A", "", " ДА "),
            listOf<Any?>("Отдельный", "2026-01-01", 100.0, "A", "", "НЕТ"),
            listOf<Any?>("Старый", "2026-01-01", 100.0, "A", "", ""),
        )
        val parsed = SheetCodec.parseCollections(rows).associateBy { it.name }
        assertTrue(parsed.getValue("Общий").sharedWithBudget)
        assertFalse(parsed.getValue("Отдельный").sharedWithBudget)
        // Пустая ячейка в старых таблицах означает общий сбор.
        assertTrue(parsed.getValue("Старый").sharedWithBudget)
    }

    @Test
    fun `нельзя убрать из сбора участника с внесёнными деньгами`() {
        val collection = CollectionData(
            name = "Подарок",
            date = "2026-01-01",
            target = 300.0,
            participants = listOf("A", "B"),
            contrib = mapOf("A" to 150.0, "B" to 0.0),
        )
        assertNotNull(collectionParticipantRemovalError(collection, listOf("B")))
        assertNull(collectionParticipantRemovalError(collection, listOf("A", "B")))
        assertNull(collectionParticipantRemovalError(collection, listOf("A")))
    }
}
