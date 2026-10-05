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
import com.groupfund.app.data.sheets.SummaryCalculator
import com.groupfund.app.data.sheets.money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Рисует сводку группы в PNG-картинку, чтобы её можно было отправить мессенджером. */
object SummaryImageRenderer {

    private const val DENSITY = 2f
    private const val MAX_WIDTH_DP = 1400f

    private const val PAD = 16f
    private const val NAME_W = 190f
    private const val MONTH_W = 52f
    private const val TAIL_W = 78f
    private const val ROW_H = 30f
    private const val HEAD_H = 104f
    private const val TITLE_H = 28f

    private const val C_GRID = 0xFFDADCE0.toInt()
    private const val C_TEXT = 0xFF1F1F1F.toInt()
    private const val C_MUTED = 0xFF8A9099.toInt()
    private const val C_SUB = 0xFF6B7280.toInt()
    private const val C_ACCENT = 0xFF1A73E8.toInt()
    private const val C_HEAD_BG = 0xFFEDF2FC.toInt()
    private const val C_ZEBRA = 0xFFF7F8FA.toInt()
    private const val C_TOTAL_BG = 0xFFE3EBF9.toInt()
    private const val C_NEG = 0xFFC5221F.toInt()

    fun render(group: GroupData, s: GroupSummary): Bitmap {
        val monthLabels = s.requiredIndices.map { s.months[it].label() }
        val tailLabels = listOf("Внесено", "Сборы", "Доля расходов", "Баланс")
        val excluded = SummaryCalculator.excludedRows(group)

        val tableW = NAME_W + monthLabels.size * MONTH_W + tailLabels.size * TAIL_W
        val scale = DENSITY * minOf(1f, MAX_WIDTH_DP / tableW)
        val d = { v: Float -> v * scale }

        val pad = d(PAD)
        val nameW = d(NAME_W)
        val monthW = d(MONTH_W)
        val tailW = d(TAIL_W)
        val rowH = d(ROW_H)
        val headH = d(HEAD_H)
        val subHeadH = d(24f)

        val width = (pad * 2 + tableW * scale).toInt().coerceAtLeast(1)
        val height = (
            d(14f) + d(TITLE_H) + d(16f) + headH +
                (s.rows.size + 1) * rowH +
                (if (excluded.isEmpty()) 0f else d(16f) + d(20f) + subHeadH + excluded.size * rowH) +
                d(26f)
            ).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        fun paint(sizeDp: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = d(sizeDp)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        val pTitle = paint(19f, true).apply { color = C_TEXT }
        val pSub = paint(12f, false).apply { color = C_SUB }
        val pHead = paint(11f, true).apply { color = C_ACCENT }
        val pName = paint(13f, false).apply { color = C_TEXT }
        val pNameBold = paint(13f, true).apply { color = C_TEXT }
        val pNum = paint(12f, false).apply { color = C_TEXT }
        val pNumBold = paint(12f, true).apply { color = C_TEXT }
        val pFill = Paint().apply { color = C_HEAD_BG }
        val pZebra = Paint().apply { color = C_ZEBRA }
        val pTotal = Paint().apply { color = C_TOTAL_BG }
        val pGrid = Paint().apply {
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
            color: Int,
        ) {
            if (text.isEmpty()) return
            p.color = color
            val inner = (r - l - d(10f)).coerceAtLeast(1f)
            val t = if (p.measureText(text) > inner) {
                TextUtils.ellipsize(text, p, inner, TextUtils.TruncateAt.END).toString()
            } else {
                text
            }
            val fm = p.fontMetrics
            val base = cy - (fm.ascent + fm.descent) / 2f
            val tx = if (alignRight) r - d(8f) - p.measureText(t) else l + d(8f)
            c.drawText(t, tx, base, p)
        }

        fun wrap2(text: String, p: TextPaint, inner: Float): List<String> {
            val words = text.split(' ')
            if (words.size < 2) return listOf(text)
            var a = ""
            var b = ""
            for (w in words) {
                val cand = if (a.isEmpty()) w else "$a $w"
                if (p.measureText(cand) <= inner) a = cand else b = w
            }
            return if (b.isEmpty()) listOf(a) else listOf(a, b)
        }

        val left = pad
        val right = width - pad
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US))

        var y = d(14f)
        drawCell(group.title, pTitle, left, right, y + d(TITLE_H) / 2f, false, C_TEXT)
        y += d(TITLE_H)
        val activeCount = s.activeCount
        drawCell(
            "Сводка на $today · участников: $activeCount · месяцев в окне: ${s.months.size}",
            pSub,
            left,
            right,
            y + d(8f),
            false,
            C_SUB,
        )
        y += d(16f)

        val xs = mutableListOf(left, left + nameW)
        for (i in 0 until monthLabels.size) xs.add(xs.last() + monthW)
        for (i in 0 until tailLabels.size) xs.add(xs.last() + tailW)

        c.drawRect(left, y, right, y + headH, pFill)
        drawCell("Участник", pHead, xs[0], xs[1], y + headH / 2f, false, C_ACCENT)
        for (i in monthLabels.indices) {
            val l = xs[1 + i]
            val r = xs[2 + i]
            val label = monthLabels[i]
            c.save()
            c.translate((l + r) / 2f, y + headH - d(10f))
            c.rotate(-90f)
            pHead.color = C_ACCENT
            c.drawText(label, -pHead.measureText(label) / 2f, 0f, pHead)
            c.restore()
        }
        for (i in tailLabels.indices) {
            val l = xs[1 + monthLabels.size + i]
            val r = xs[2 + monthLabels.size + i]
            val lines = wrap2(tailLabels[i], pHead, r - l - d(10f))
            val lineH = d(13f)
            val cy = y + headH / 2f - (lines.size - 1) * lineH / 2f
            lines.forEachIndexed { li, line ->
                drawCell(line, pHead, l, r, cy + li * lineH, false, C_ACCENT)
            }
        }

        val ys = mutableListOf(y, y + headH)
        var ry = ys[1]
        s.rows.forEachIndexed { i, r ->
            if (i % 2 == 1) c.drawRect(left, ry, right, ry + rowH, pZebra)
            val cy = ry + rowH / 2f
            drawCell(r.member.name, pName, xs[0], xs[1], cy, false, C_TEXT)
            var ci = 0
            for (idx in s.requiredIndices) {
                val v = r.perMonth[idx]
                val l = xs[1 + ci]
                drawCell(if (v > 0) money(v) else "—", pNum, l, xs[2 + ci], cy, true, if (v > 0) C_TEXT else C_MUTED)
                ci++
            }
            val vals = listOf(r.totalPaid, r.collectionShare, r.expenseShare, r.balance)
            for (j in vals.indices) {
                val l = xs[1 + monthLabels.size + j]
                drawCell(money(vals[j]), pNum, l, xs[2 + monthLabels.size + j], cy, true, if (vals[j] < 0) C_NEG else C_TEXT)
            }
            ry += rowH
            ys.add(ry)
        }

        c.drawRect(left, ry, right, ry + rowH, pTotal)
        val cy = ry + rowH / 2f
        drawCell("ИТОГО", pNameBold, xs[0], xs[1], cy, false, C_TEXT)
        var ti = 0
        for (idx in s.requiredIndices) {
            drawCell(money(s.totals[idx]), pNumBold, xs[1 + ti], xs[2 + ti], cy, true, C_TEXT)
            ti++
        }
        val n = s.months.size
        val totalsTail = listOf(s.totals[n], s.totals[n + 1], s.totals[n + 2], s.totals[n + 3])
        for (j in totalsTail.indices) {
            drawCell(
                money(totalsTail[j]),
                pNumBold,
                xs[1 + monthLabels.size + j],
                xs[2 + monthLabels.size + j],
                cy,
                true,
                if (totalsTail[j] < 0) C_NEG else C_TEXT,
            )
        }
        ry += rowH
        ys.add(ry)

        for (xv in xs) c.drawLine(xv, ys.first(), xv, ys.last(), pGrid)
        for (yv in ys) c.drawLine(xs.first(), yv, xs.last(), yv, pGrid)

        if (excluded.isNotEmpty()) {
            y = ry + d(16f)
            drawCell("ИСКЛЮЧЁННЫЕ · возврат", pHead, left, right, y + d(10f), false, C_ACCENT)
            y += d(20f)
            val exs = mutableListOf(left, left + nameW)
            repeat(3) { exs.add(exs.last() + tailW) }
            c.drawRect(left, y, right, y + subHeadH, pFill)
            val exLabels = listOf("Имя", "Внесено", "Доля расходов", "Возврат")
            for (i in exLabels.indices) {
                val lines = wrap2(exLabels[i], pHead, exs[i + 1] - exs[i] - d(10f))
                val lineH = d(13f)
                val c2 = y + subHeadH / 2f - (lines.size - 1) * lineH / 2f
                lines.forEachIndexed { li, line ->
                    drawCell(line, pHead, exs[i], exs[i + 1], c2 + li * lineH, false, C_ACCENT)
                }
            }
            val eys = mutableListOf(y, y + subHeadH)
            var ey = y + subHeadH
            excluded.forEachIndexed { i, row ->
                if (i % 2 == 1) c.drawRect(left, ey, right, ey + rowH, pZebra)
                val ec = ey + rowH / 2f
                for (j in row.indices) {
                    val text = row[j]?.toString().orEmpty()
                    val col = if (text.startsWith("-")) C_NEG else C_TEXT
                    drawCell(text, pNum, exs[j], exs[j + 1], ec, j > 0, col)
                }
                ey += rowH
                eys.add(ey)
            }
            for (xv in exs) c.drawLine(xv, eys.first(), xv, eys.last(), pGrid)
            for (yv in eys) c.drawLine(exs.first(), yv, exs.last(), yv, pGrid)
            ry = ey
        }

        drawCell("GroupFund", pSub, left, right, ry + d(20f), false, C_MUTED)

        return bmp
    }
}