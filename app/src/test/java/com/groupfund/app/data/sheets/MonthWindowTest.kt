package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.YearMonth

/**
 * Окно месяцев не должно расти бесконтрольно: раскладка «Сводки» и диапазон записи
 * считаются от одного и того же числа колонок. Здесь фиксируем, что даже при окне
 * больше стандартного (старые таблицы) запись остаётся валидной и совпадает с
 * форматированием, а новое окно ограничено [MAX_WINDOW_MONTHS].
 */
class MonthWindowTest {

    private fun window(n: Int): List<MonthPlan> =
        List(n) { i ->
            MonthPlan(YearMonth.of(2026, 1).plusMonths(i.toLong()), required = true, amount = 100)
        }

    private fun group(months: List<MonthPlan>) = GroupData(
        spreadsheetId = "1",
        title = "Тест",
        baseAmount = 100,
        currency = "RUB",
        createdAt = "2025-01-01",
        months = months,
        members = listOf(
            MemberData(
                name = "Аня",
                email = "",
                joinDate = "2025-01-01",
                active = true,
                removedAt = null,
                refund = null,
            ),
        ),
        payments = emptyList(),
        expenses = emptyList(),
        collections = emptyList(),
    )

    @Test
    fun defaultWindowIsThirteenMonths() {
        assertEquals(13, MAX_WINDOW_MONTHS)
        assertEquals(13, monthPlanLimit(0))
        assertEquals(13, monthPlanLimit(13))
    }

    @Test
    fun existingLargerWindowStaysEditableButCannotGrow() {
        assertEquals(14, monthPlanLimit(14))
        assertEquals(30, monthPlanLimit(30))
    }

    @Test
    fun summaryWriteRangeMatchesLayoutForStandardWindow() {
        assertWriteRangeMatchesLayout(13)
    }

    @Test
    fun summaryWriteRangeMatchesLayoutForFourteenMonths() {
        // Бывшие таблицы могли расширить окно вручную: это не должно ломать запись.
        assertWriteRangeMatchesLayout(14)
    }

    @Test
    fun summaryWriteRangeMatchesLayoutForVeryLargeWindow() {
        assertWriteRangeMatchesLayout(30)
    }

    private fun assertWriteRangeMatchesLayout(monthCount: Int) {
        val g = group(window(monthCount))
        val summary = SummaryCalculator.compute(g)
        val rows = SummaryCalculator.summaryRows(g, summary)

        // Заголовок: участник + месяцы + Внесено/Сборы/Доля расходов/Баланс.
        assertEquals(1 + monthCount + 4, rows[2].size)
        val width = rows.maxOf { it.size }
        assertEquals(1 + monthCount + 4, width)

        // Диапазон записи покрывает все колонки и не зависит от фиксированного лимита.
        val range = vrange(Tabs.SUMMARY, rows)
        assertEquals("${Tabs.SUMMARY}!A1:${colName(width)}${rows.size}", range.range)

        // Форматирование обязано использовать ровно ту же ширину, что и запись.
        assertEquals(width, SheetBeautifier.summaryLayout(g, summary).colCount)
    }
}
