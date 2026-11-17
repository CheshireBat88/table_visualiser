package com.groupfund.app.data.export

/**
 * Геометрия PNG-сводки без Android-графики: ширины колонок, масштаб под предел
 * ширины и итоговый размер картинки. Вынесено из рендерера, чтобы раскладку
 * можно было проверить тестами и повторить 1:1 в десктопной версии.
 */
internal object SummaryImageLayout {

    /** Плотность по умолчанию: 1 dp = 2 px. */
    const val DENSITY = 2f

    /** Предел ширины картинки в dp: дальше масштаб уменьшается. */
    const val MAX_WIDTH_DP = 1600f

    const val PAD = 14f
    const val NAME_W = 140f
    const val MONTH_W = 56f
    const val CELL_W = 64f
    const val UNTIL_W = 96f
    const val ROW_H = 26f
    const val HEAD_H = 28f

    /** Заголовок группы, подпись и отступ перед таблицей. */
    const val TOP_DECORATION_DP = 14f + 24f + 16f

    /** Нижний отступ под легендой. */
    const val BOTTOM_DECORATION_DP = 30f

    /** Ширина таблицы в dp: имя + месяцы + 4 итоговых колонки + «Оплачено до». */
    fun tableWidthDp(requiredColumns: Int): Float =
        NAME_W + requiredColumns * MONTH_W + 4 * CELL_W + UNTIL_W

    /** Масштаб: плотность 2×, но не шире [MAX_WIDTH_DP]. */
    fun scaleFor(tableWidthDp: Float): Float =
        DENSITY * minOf(1f, MAX_WIDTH_DP / tableWidthDp)

    /** Ширина картинки в пикселях (минимум 1 — Bitmap не принимает 0). */
    fun widthPx(tableWidthDp: Float, scale: Float): Int =
        (PAD * scale * 2 + tableWidthDp * scale).toInt().coerceAtLeast(1)

    /**
     * Высота картинки: верхние подписи, шапка, [rows] строк, строка «Итого»,
     * блок исключённых (если он есть) и нижний отступ под легендой.
     */
    fun heightPx(rows: Int, excludedCount: Int, scale: Float): Int {
        val head = HEAD_H * scale
        val rowH = ROW_H * scale
        val excludedBlock = if (excludedCount == 0) {
            0f
        } else {
            34f * scale + head + rowH * excludedCount
        }
        val total = TOP_DECORATION_DP * scale + head + (rows + 1) * rowH + excludedBlock +
            BOTTOM_DECORATION_DP * scale
        return total.toInt().coerceAtLeast(1)
    }
}
