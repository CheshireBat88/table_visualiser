package com.groupfund.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты геометрии PNG-сводки: ширины колонок, масштаб и высота картинки. */
class SummaryImageLayoutTest {

    @Test
    fun `ширина таблицы складывается из колонок`() {
        // 140 (имя) + 4×64 (итоги) + 96 («Оплачено до») + месяцы по 56.
        assertEquals(548f, SummaryImageLayout.tableWidthDp(1), 0.001f)
        assertEquals(1164f, SummaryImageLayout.tableWidthDp(12), 0.001f)
        assertEquals(1836f, SummaryImageLayout.tableWidthDp(24), 0.001f)
        // Каждый месяц добавляет ровно одну колонку.
        assertEquals(
            SummaryImageLayout.MONTH_W,
            SummaryImageLayout.tableWidthDp(13) - SummaryImageLayout.tableWidthDp(12),
            0.001f,
        )
    }

    @Test
    fun `до предела ширины масштаб равен плотности`() {
        assertEquals(2f, SummaryImageLayout.scaleFor(SummaryImageLayout.tableWidthDp(1)), 0.001f)
        assertEquals(2f, SummaryImageLayout.scaleFor(SummaryImageLayout.tableWidthDp(12)), 0.001f)
        // Ровно предел — ещё без уменьшения.
        assertEquals(2f, SummaryImageLayout.scaleFor(SummaryImageLayout.MAX_WIDTH_DP), 0.001f)
    }

    @Test
    fun `длинное окно месяцев уменьшается до предела ширины`() {
        val tableW = SummaryImageLayout.tableWidthDp(24)
        val scale = SummaryImageLayout.scaleFor(tableW)
        assertTrue(scale < 2f)
        assertEquals(3200f, tableW * scale, 0.01f)
        val width = SummaryImageLayout.widthPx(tableW, scale)
        assertEquals(3248, width)
        // Картинка не выходит за предел: таблица занимает не больше 1600 dp.
        assertTrue(width < 3300)
    }

    @Test
    fun `высота считается по строкам и блоку исключённых`() {
        // 54 dp сверху + шапка + (строки + «Итого») + 30 dp снизу.
        assertEquals(380, SummaryImageLayout.heightPx(rows = 2, excludedCount = 0, scale = 2f))
        // 12 участников: 108 + 56 + 13×52 + 60.
        assertEquals(900, SummaryImageLayout.heightPx(rows = 12, excludedCount = 0, scale = 2f))
        // Блок исключённых: отступ 34 dp, шапка и по строке на человека.
        assertEquals(556, SummaryImageLayout.heightPx(rows = 2, excludedCount = 1, scale = 2f))
        assertEquals(
            SummaryImageLayout.heightPx(rows = 2, excludedCount = 1, scale = 2f) + 52,
            SummaryImageLayout.heightPx(rows = 2, excludedCount = 2, scale = 2f),
        )
    }

    @Test
    fun `пустая сводка не даёт нулевой размер`() {
        assertTrue(SummaryImageLayout.widthPx(0f, 2f) >= 1)
        assertTrue(SummaryImageLayout.heightPx(rows = 0, excludedCount = 0, scale = 0f) >= 1)
    }
}
