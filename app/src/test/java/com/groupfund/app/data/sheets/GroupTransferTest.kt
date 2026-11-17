package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** Тесты чистых правил передачи группы и опознания таблицы группы. */
class GroupTransferTest {

    private fun settingsRows() = listOf(
        listOf<Any?>("groupName", "Тест"),
        listOf<Any?>("baseAmount", 1000),
        listOf<Any?>("currency", "RUB"),
        listOf<Any?>("createdAt", "2026-01-01"),
        emptyList(),
        listOf<Any?>("Месяц", "Обязательный", "Сумма"),
        listOf<Any?>("01.2026", "да", 1000),
    )

    private fun member(name: String, active: Boolean = true) =
        MemberData(
            name = name,
            email = "",
            joinDate = "2026-01-01",
            active = active,
            removedAt = null,
            refund = null,
        )

    private fun group(
        payments: List<PaymentData> = listOf(PaymentData("A", "2026-01-01", 100.0, "")),
        expenses: List<ExpenseData> = listOf(ExpenseData("2026-01-01", "Пицца", 300.0, listOf("A", "B"))),
        collections: List<CollectionData> = listOf(
            CollectionData(
                name = "Подарок",
                date = "2026-01-01",
                target = 200.0,
                participants = listOf("A", "B"),
                contrib = mapOf("A" to 100.0),
                sharedWithBudget = true,
            ),
        ),
        members: List<MemberData> = listOf(member("A"), member("B")),
        currency: String = "RUB",
    ) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 1000,
        currency = currency,
        createdAt = "2026-01-01",
        months = listOf(MonthPlan(YearMonth.of(2026, 1), true, 1000)),
        members = members,
        payments = payments,
        expenses = expenses,
        collections = collections,
    )

    @Test
    fun `решение по маркеру передачи`() {
        assertEquals(TransferOutcome.CLAIM, decideTransferOutcome(null, "copy1"))
        assertEquals(TransferOutcome.CLAIM, decideTransferOutcome("", "copy1"))
        assertEquals(TransferOutcome.ALREADY_OURS, decideTransferOutcome("copy1", "copy1"))
        assertEquals(TransferOutcome.TAKEN_BY_OTHER, decideTransferOutcome("other", "copy1"))
    }

    @Test
    fun `новая передача блокируется только занятым маркером`() {
        assertFalse(transferStartBlocked(TransferMarker()))
        assertTrue(transferStartBlocked(TransferMarker(pendingCode = "ABCDEFGH")))
        assertTrue(transferStartBlocked(TransferMarker(newSpreadsheetId = "copy1")))
    }

    @Test
    fun `настройки группы опознаются по ключам и заголовку месяцев`() {
        assertTrue(isGroupSettings(settingsRows()))
        val noCurrency = settingsRows().filterNot { cellText(it.firstOrNull()) == "currency" }
        assertFalse(isGroupSettings(noCurrency))
        val noMonths = settingsRows().filterNot { cellText(it.firstOrNull()) == "Месяц" }
        assertFalse(isGroupSettings(noMonths))
        assertFalse(isGroupSettings(emptyList()))
    }

    @Test
    fun `таблица группы требует базовые листы и узнаваемые настройки`() {
        assertTrue(looksLikeGroupTable(Tabs.CORE, settingsRows()))
        assertFalse(looksLikeGroupTable(Tabs.CORE - Tabs.PAYMENTS, settingsRows()))
        assertFalse(looksLikeGroupTable(Tabs.CORE, listOf(listOf<Any?>("что-то", "другое"))))
    }

    @Test
    fun `полная копия не имеет расхождений`() {
        assertTrue(copyGaps(group(), group()).isEmpty())
        // Порядок строк не важен: копия Drive может переставить записи.
        val reversed = group(
            payments = listOf(
                PaymentData("B", "2026-01-02", 50.0, ""),
                PaymentData("A", "2026-01-01", 100.0, ""),
            ),
        )
        assertTrue(copyGaps(reversed, reversed.copy(payments = reversed.payments.reversed())).isEmpty())
    }

    @Test
    fun `расхождения копии называются по листам`() {
        val base = group()
        assertEquals(
            listOf("платежи"),
            copyGaps(base, base.copy(payments = listOf(PaymentData("A", "2026-01-01", 99.0, "")))),
        )
        assertEquals(
            listOf("расходы"),
            copyGaps(
                base,
                base.copy(expenses = listOf(ExpenseData("2026-01-01", "Пицца", 300.0, listOf("A")))),
            ),
        )
        assertEquals(
            listOf("сборы"),
            copyGaps(
                base,
                base.copy(
                    collections = listOf(
                        base.collections.first().copy(contrib = mapOf("A" to 50.0)),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("участники"),
            copyGaps(base, base.copy(members = listOf(member("A"), member("B", active = false)))),
        )
        assertEquals(
            listOf("план месяцев"),
            copyGaps(
                base,
                base.copy(months = listOf(MonthPlan(YearMonth.of(2026, 1), true, 500))),
            ),
        )
        assertEquals(listOf("настройки"), copyGaps(base, base.copy(currency = "USD")))
    }
}
