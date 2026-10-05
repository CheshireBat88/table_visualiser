package com.groupfund.app.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.groupfund.app.data.sheets.GroupData
import com.groupfund.app.data.sheets.GroupSummary
import com.groupfund.app.data.sheets.MemberSummary
import com.groupfund.app.data.sheets.MonthPlan
import com.groupfund.app.data.sheets.SummaryCalculator
import com.groupfund.app.data.sheets.money
import com.groupfund.app.ui.currencySymbol
import com.groupfund.app.ui.monthShortLabel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Рисует сводку в PNG так же, как она выглядит во вкладке «Сводка» приложения:
 * цветные плитки месяцев, колонки Внесено/Сборы/Расходы/Баланс и «Оплачено до».
 */
object SummaryImageRenderer {

    private const val DENSITY = 2f
    private const val MAX_WIDTH_DP = 1600f

    private const val PAD = 14f
    private const val NAME_W = 140f
    private const val MONTH_W = 56f
    private const val CELL_W = 64f
    private const val UNTIL_W = 96f
    private const val ROW_H = 26f
    private const val HEAD_H = 28f

    private const val TILE_GOOD = 0xFFC6EFCE.toInt()
    private const val TILE_WARN = 0xFFFFEB9C.toInt()
    private const val TILE_NEUTRAL = 0xFFD9D9D9.toInt()
    private const val TILE_DEBT = 0xFFFFC7CE.toInt()
    private const val TILE_TEXT = 0xFF1F1F1F.toInt()

    private const val C_GRID = 0xFFD9D9D9.toInt()
    private const val C_TEXT = 0xFF1F1F1F.toInt()
    private const val C_SUB = 0xFF6B7280.toInt()
    private const val C_ACCENT = 0xFF1A73E8.toInt()
    private const val C_HEAD_BG = 0xFFEEF3FD.toInt()
    private const val C_TOTAL_BG = 0xFFE8EEF9.toInt()

    /** Цвет плитки месяца: сдан — зелёный, не сдан — жёлтый, текущий не сдан — красный, без обязанности — серый. */
    private fun tileColor(mp: MonthPlan, r: MemberSummary, monthIdx: Int): Int = when {
        monthIdx !in r.requiredMonthIndices -> TILE_NEUTRAL
        r.perMonth[monthIdx] >= mp.amount - 0.005 -> TILE_GOOD
        mp.month == YearMonth.now() -> TILE_DEBT
        else -> TILE_WARN
    }

    fun render(group: GroupData, s: GroupSummary): Bitmap {
        val months = s.months
        val cols = s.requiredIndices
        val requiredCount = s.requiredMonths.size
        val excluded = SummaryCalculator.excludedRows(group)

        val tableW = NAME_W + cols.size * MONTH_W + 4 * CELL_W + UNTIL_W
        val scale = DENSITY * minOf(1f, MAX_WIDTH_DP / tableW)
        val d = { v: Float -> v * scale }

        val pad = d(PAD)
        val nameW = d(NAME_W)
        val monthW = d(MONTH_W)
        val cellW = d(CELL_W)
        val untilW = d(UNTIL_W)
        val rowH = d(ROW_H)
        val headH = d(HEAD_H)

        val width = (pad * 2 + tableW * scale).toInt().coerceAtLeast(1)
        val exH = if (excluded.isEmpty()) 0f else d(34f) + headH + d(ROW_H) * excluded.size
        val height = (
            d(14f) + d(24f) + d(16f) + headH + (s.rows.size + 1) * rowH + exH + d(30f)
            ).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        fun tp(sizeDp: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = d(sizeDp)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        val pTitle = tp(18f, true).apply { color = C_TEXT }
        val pSub = tp(11f, false).apply { color = C_SUB }
        val pHead = tp(11f, true).apply { color = C_ACCENT }
        val pCell = tp(11f, false).apply { color = C_TEXT }
        val pCellBold = tp(11f, true).apply { color = C_TEXT }
        val pCellTile = tp(11f, false).apply { color = TILE_TEXT }
        val pCellTileBold = tp(11f, true).apply { color = TILE_TEXT }
        val pLegend = tp(10f, false).apply { color = C_SUB }
        val fill = Paint()
        val grid = Paint().apply {
            color = C_GRID
            strokeWidth = maxOf(1f, d(0.7f))
        }

        fun drawCell(
            text: String,
            p: TextPaint,
            l: Float,
            r: Float,
            cy: Float,
            alignRight: Boolean,
            bg: Int?,
        ) {
            if (bg != null) {
                fill.color = bg
                c.drawRect(l, cy - rowH / 2f, r, cy + rowH / 2f, fill)
            }
            if (text.isEmpty()) return
            val inner = (r - l - d(8f)).coerceAtLeast(1f)
            val t = if (p.measureText(text) > inner) {
                TextUtils.ellipsize(text, p, inner, TextUtils.TruncateAt.END).toString()
            } else {
                text
            }
            val fm = p.fontMetrics
            val base = cy - (fm.ascent + fm.descent) / 2f
            val tx = if (alignRight) r - d(4f) - p.measureText(t) else l + d(4f)
            c.drawText(t, tx, base, p)
        }

        val left = pad
        val right = width - pad
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US))

        var y = d(14f)
        drawCell(group.title, pTitle, left, right, y + d(12f), false, null)
        y += d(24f)
        drawCell(
            "Сводка на $today · ${currencySymbol(group.currency)} · " +
                "участников: ${s.activeCount} · обязательных месяцев: $requiredCount",
            pSub,
            left,
            right,
            y + d(6f),
            false,
            null,
        )
        y += d(16f)

        val xs = mutableListOf(left, left + nameW)
        repeat(cols.size) { xs.add(xs.last() + monthW) }
        repeat(4) { xs.add(xs.last() + cellW) }
        xs.add(xs.last() + untilW)

        val tailIdx = 1 + cols.size
        val gridW = (right - left).coerceAtMost(width - left * 2)

        fun headCell(text: String, i: Int, fromRight: Boolean = false) {
            drawCell(text, pHead, xs[i], xs[i + 1], y + headH / 2f, fromRight, C_HEAD_BG)
        }

        val ys = mutableListOf(y, y + headH)
        headCell("Участник", 0)
        cols.forEachIndexed { k, ci -> headCell(monthShortLabel(months[ci].month), 1 + k) }
        headCell("Внесено", tailIdx, true)
        headCell("Сборы", tailIdx + 1, true)
        headCell("Расходы", tailIdx + 2, true)
        headCell("Баланс", tailIdx + 3, true)
        headCell("Оплачено до", tailIdx + 4)

        var ry = ys.last()
        s.rows.forEach { r ->
            drawCell(r.member.name, pCell, xs[0], xs[1], ry + rowH / 2f, false, null)
            cols.forEachIndexed { k, ci ->
                val paid = r.perMonth[ci] > 0
                drawCell(
                    if (paid) money(r.perMonth[ci]) else "",
                    if (paid) pCellTileBold else pCellTile,
                    xs[1 + k],
                    xs[2 + k],
                    ry + rowH / 2f,
                    true,
                    tileColor(months[ci], r, ci),
                )
            }
            val cy = ry + rowH / 2f
            val vals = listOf(r.totalPaid, r.collectionShare, r.expenseShare, r.balance)
            vals.forEachIndexed { j, v ->
                drawCell(
                    money(v),
                    pCell,
                    xs[tailIdx + j],
                    xs[tailIdx + j + 1],
                    cy,
                    true,
                    if (j == 3 && v < -0.005) TILE_DEBT else null,
                )
            }
            drawCell(
                r.paidThrough?.let { "до ${monthShortLabel(it)} (${r.fullyPaidRequiredCount}/$requiredCount)" } ?: "—",
                pCell,
                xs[tailIdx + 4],
                xs[tailIdx + 5],
                cy,
                false,
                null,
            )
            ry += rowH
            ys.add(ry)
        }

        drawCell("Итого", pCellBold, xs[0], xs[1], ry + rowH / 2f, false, C_TOTAL_BG)
        cols.forEachIndexed { k, ci ->
            drawCell(
                money(s.totals[ci]),
                pCellBold,
                xs[1 + k],
                xs[2 + k],
                ry + rowH / 2f,
                true,
                C_TOTAL_BG,
            )
        }
        val n = months.size
        val totalsTail = listOf(s.totals[n], s.totals[n + 1], s.totals[n + 2], s.totals[n + 3])
        totalsTail.forEachIndexed { j, v ->
            drawCell(
                money(v),
                pCellBold,
                xs[tailIdx + j],
                xs[tailIdx + j + 1],
                ry + rowH / 2f,
                true,
                if (j == 3 && v < -0.005) TILE_DEBT else C_TOTAL_BG,
            )
        }
        drawCell("", pCellBold, xs[tailIdx + 4], xs[tailIdx + 5], ry + rowH / 2f, false, C_TOTAL_BG)
        ry += rowH
        ys.add(ry)

        for (xv in xs) c.drawLine(xv, ys.first(), xv, ys.last(), grid)
        for (yv in ys) c.drawLine(left, yv, left + gridW, yv, grid)

        if (excluded.isNotEmpty()) {
            y = ry + d(14f)
            drawCell("ИСКЛЮЧЁННЫЕ · возврат", pHead, left, left + gridW, y + d(8f), false, null)
            y += d(18f)
            val exs = mutableListOf(left, left + nameW)
            repeat(3) { exs.add(exs.last() + cellW) }
            fill.color = C_HEAD_BG
            c.drawRect(left, y, left + gridW, y + headH, fill)
            listOf("Имя", "Внесено", "Доля расходов", "Возврат").forEachIndexed { i, t ->
                drawCell(t, pHead, exs[i], exs[i + 1], y + headH / 2f, i > 0, null)
            }
            val eys = mutableListOf(y, y + headH)
            var ey = y + headH
            excluded.forEach { row ->
                row.forEachIndexed { j, v ->
                    drawCell(
                        v?.toString().orEmpty(),
                        pCell,
                        exs[j],
                        exs[j + 1],
                        ey + rowH / 2f,
                        j > 0,
                        null,
                    )
                }
                ey += rowH
                eys.add(ey)
            }
            for (xv in exs) c.drawLine(xv, eys.first(), xv, eys.last(), grid)
            for (yv in eys) c.drawLine(left, yv, left + gridW, yv, grid)
            ry = ey
        }

        var lx = left
        val ly = ry + d(16f)
        listOf(
            TILE_GOOD to "оплачено",
            TILE_WARN to "не оплачено",
            TILE_DEBT to "текущий месяц не оплачен",
            TILE_NEUTRAL to "нет обязанности",
        ).forEach { (col, label) ->
            fill.color = col
            c.drawRect(lx, ly - d(8f), lx + d(11f), ly + d(3f), fill)
            lx += d(15f)
            pLegend.color = C_SUB
            c.drawText(label, lx, ly, pLegend)
            lx += pLegend.measureText(label) + d(14f)
        }

        return bmp
    }
}