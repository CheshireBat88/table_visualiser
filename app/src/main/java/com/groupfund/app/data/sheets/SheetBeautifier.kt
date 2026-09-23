package com.groupfund.app.data.sheets

import com.groupfund.app.data.sheets.SpreadsheetBatchUpdateRequest.Request
import java.time.YearMonth

/**
 * Приводит таблицу группы к читаемому виду: цветные шапки, чередование строк,
 * тонкие рамки, разделители между смысловыми блоками на «Сводке».
 * Всё считается по текущим данным, поэтому после каждой записи можно
 * переприменять форматирование целиком.
 */
object SheetBeautifier {

    private fun rgb(r: Int, g: Int, b: Int) = Rgb(r / 255.0, g / 255.0, b / 255.0)

    private val HEADER_BG = rgb(31, 78, 121)          // #1F4E79 — тёмно-синий
    private val HEADER_FG = rgb(255, 255, 255)
    private val LIGHT_HEADER_BG = rgb(217, 226, 243)  // #D9E2F3 — светло-голубой
    private val DARK_TEXT = rgb(38, 38, 38)
    private val BAND_EVEN = rgb(255, 255, 255)
    private val BAND_ODD = rgb(238, 244, 250)          // #EEF4FA
    private val BORDER = rgb(182, 196, 214)            // #B6C4D6
    private val SECTION_BG = rgb(68, 114, 196)         // #4472C4
    private val TOTAL_BG = rgb(252, 228, 214)          // #FCE4D6 — тёплый итог
    private val TOTAL_BORDER = rgb(150, 150, 150)
    private val MUTED = rgb(89, 89, 89)
    private val TAB_SUMMARY = rgb(31, 78, 121)
    private val TAB_MEMBERS = rgb(70, 130, 180)
    private val TAB_PAYMENTS = rgb(112, 173, 71)
    private val TAB_EXPENSES = rgb(197, 90, 17)
    private val TAB_COLLECTIONS = rgb(230, 145, 56)
    private val TAB_SETTINGS = rgb(128, 128, 128)

    // Статусы строк «Сводки».
    private val GOOD_BG = rgb(198, 239, 206)    // #C6EFCE — месяц сдан
    private val WARN_BG = rgb(255, 235, 156)    // #FFEB9C — месяц не сдан
    private val NEUTRAL_BG = rgb(217, 217, 217) // #D9D9D9 — обязанности в этом месяце нет
    private val DEBT_MILD = rgb(255, 199, 206)  // #FFC7CE — красный (долг / текущий месяц)

    private const val CENTER = "CENTER"
    private const val RIGHT = "RIGHT"
    private const val MIDDLE = "MIDDLE"

    // ---------- маленькие строители ----------

    private fun border(color: Rgb = BORDER, style: String = "SOLID") = Border(style = style, color = color)

    private fun borders(color: Rgb = BORDER): Borders = Borders(
        top = border(color), bottom = border(color), left = border(color), right = border(color),
    )

    /** Тонкие рамки по всем сторонам + толстая верхняя/нижняя (для Итого и титула). */
    private fun thickEdges(): Borders = Borders(
        top = border(TOTAL_BORDER, "THICK"),
        bottom = border(TOTAL_BORDER, "THICK"),
    )

    private fun fmt(
        bg: Rgb = BAND_EVEN,
        fg: Rgb = DARK_TEXT,
        size: Int = 10,
        bold: Boolean = false,
        italic: Boolean = false,
        align: String? = null,
        edge: Borders? = null,
    ): CellData = CellData(
        userEnteredFormat = CellFormat(
            backgroundColor = bg,
            textFormat = TextFormat(foregroundColor = fg, fontSize = size, bold = bold, italic = italic),
            horizontalAlignment = align,
            verticalAlignment = MIDDLE,
            borders = edge,
        ),
    )

    private fun filled(
        bg: Rgb,
        fg: Rgb,
        size: Int,
        bold: Boolean,
        align: String? = null,
        edge: Borders? = null,
    ): CellData = CellData(
        userEnteredFormat = CellFormat(
            backgroundColor = bg,
            textFormat = TextFormat(foregroundColor = fg, fontSize = size, bold = bold),
            horizontalAlignment = align,
            verticalAlignment = MIDDLE,
            borders = edge,
        ),
    )

    // ---------- раскладка «Сводки» ----------

    data class SummaryLayout(
        val colCount: Int,
        val tableHeaderRow: Int = 2,
        val dataStart: Int,
        val dataEnd: Int,
        val totalRow: Int,
        val expensesBanner: Int,
        val expensesHeaderRow: Int?,
        val expensesDataStart: Int,
        val expensesDataEnd: Int,
        val excludedBanner: Int?,
        val excludedHeaderRow: Int?,
        val excludedDataStart: Int,
        val excludedDataEnd: Int,
    )

    /** Повторяет структуру SummaryCalculator.summaryRows. */
    fun summaryLayout(group: GroupData, summary: GroupSummary): SummaryLayout {
        val colCount = 1 + summary.requiredIndices.size + 4
        val dataStart = 3
        val dataEnd = dataStart + summary.rows.size
        val totalRow = dataEnd + 1
        val expensesBanner = totalRow + 2
        var next = expensesBanner + 1

        val (expensesHeaderRow, expensesDataStart, expensesDataEnd) =
            if (group.expenses.isEmpty()) Triple(null, next, next)
            else Triple(next, next + 1, next + 1 + group.expenses.size)
        next = expensesDataEnd

        val removed = group.members.filter { !it.active }
        return if (removed.isEmpty()) {
            SummaryLayout(
                colCount = colCount,
                dataStart = dataStart,
                dataEnd = dataEnd,
                totalRow = totalRow,
                expensesBanner = expensesBanner,
                expensesHeaderRow = expensesHeaderRow,
                expensesDataStart = expensesDataStart,
                expensesDataEnd = expensesDataEnd,
                excludedBanner = null,
                excludedHeaderRow = null,
                excludedDataStart = 0,
                excludedDataEnd = 0,
            )
        } else {
            SummaryLayout(
                colCount = colCount,
                dataStart = dataStart,
                dataEnd = dataEnd,
                totalRow = totalRow,
                expensesBanner = expensesBanner,
                expensesHeaderRow = expensesHeaderRow,
                expensesDataStart = expensesDataStart,
                expensesDataEnd = expensesDataEnd,
                excludedBanner = next + 1,
                excludedHeaderRow = next + 2,
                excludedDataStart = next + 3,
                excludedDataEnd = next + 3 + removed.size,
            )
        }
    }

    // ---------- генерация запросов ----------

    fun styleRequests(
        sheetIds: Map<String, Int>,
        group: GroupData,
        summary: GroupSummary,
    ): List<Request> {
        val req = mutableListOf<Request>()
        val summaryId = sheetIds[Tabs.SUMMARY]
        if (summaryId != null) {
            req += summaryRequests(summaryId, group, summary)
        }
        req += listSheetRequests(
            sheetIds[Tabs.MEMBERS], header = 8, rightAligned = emptyList(),
            dataCount = group.members.size, banding = true,
        )
        req += listSheetRequests(
            sheetIds[Tabs.PAYMENTS], header = 4, rightAligned = listOf(2),
            dataCount = group.payments.size, banding = group.payments.size <= 200,
        )
        req += listSheetRequests(
            sheetIds[Tabs.COLLECTIONS], header = 5, rightAligned = listOf(2, 4),
            dataCount = group.collections.size, banding = group.collections.size <= 200,
        )
        req += listSheetRequests(
            sheetIds[Tabs.EXPENSES], header = 5, rightAligned = listOf(2, 4),
            dataCount = group.expenses.size, banding = group.expenses.size <= 200,
        )
        req += tuningRequests(sheetIds)
        return req
    }

    private fun summaryRequests(
        sheetId: Int,
        group: GroupData,
        summary: GroupSummary,
    ): List<Request> {
        val req = mutableListOf<Request>()
        val lay = summaryLayout(group, summary)
        val months = summary.months
        val cols = lay.colCount

        fun range(rs: Int, re: Int, cs: Int = 0, ce: Int = cols) =
            GridRange(sheetId, rs, re, cs, ce)

        // Титул: полоса с названием группы.
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(0, 1),
            cell = filled(HEADER_BG, HEADER_FG, 13, bold = true, align = CENTER, edge = thickEdges()),
        )))

        // Базовая сетка таблицы: белый фон, тонкие рамки.
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(lay.tableHeaderRow, lay.totalRow + 1),
            cell = fmt(edge = borders()),
        )))
        // Шапка таблицы.
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(2, 3),
            cell = filled(HEADER_BG, HEADER_FG, 10, bold = true, align = CENTER, edge = borders()),
        )))
        // Строки данных: чередование + правое выравнивание числовых колонок.
        for ((i, r) in (lay.dataStart until lay.dataEnd).withIndex()) {
            val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(r, r + 1),
                cell = fmt(bg = bg, edge = borders()),
            )))
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(r, r + 1, 1, cols),
                cell = fmt(bg = bg, align = RIGHT, edge = borders()),
            )))
        }
        // Цветовая разметка по ячейкам (плитка): сдано — зелёный, не сдано — жёлтый,
        // не сдано в текущем месяце — красный, месяц без обязанности (позже вступил,
        // освобождён) — серый; отрицательный баланс — красный. Текст всегда тёмный.
        val now = YearMonth.now()
        val colsReq = summary.requiredIndices
        summary.rows.forEachIndexed { i, r ->
            val rowIdx = lay.dataStart + i
            colsReq.forEachIndexed { colIdx, monthIdx ->
                val mp = months[monthIdx]
                val bg = when {
                    monthIdx !in r.requiredMonthIndices -> NEUTRAL_BG
                    r.perMonth[monthIdx] >= mp.amount - 0.005 -> GOOD_BG
                    months[monthIdx].month == now -> DEBT_MILD
                    else -> WARN_BG
                }
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(rowIdx, rowIdx + 1, 1 + colIdx, 2 + colIdx),
                    cell = fmt(bg = bg, align = CENTER, edge = borders()),
                )))
            }
            if (r.balance < -0.005) {
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(rowIdx, rowIdx + 1, cols - 1, cols),
                    cell = fmt(bg = DEBT_MILD, align = RIGHT, edge = borders()),
                )))
            }
        }
        // Строка «Итого».
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(lay.totalRow, lay.totalRow + 1),
            cell = filled(TOTAL_BG, DARK_TEXT, 10, bold = true, align = CENTER, edge = thickEdges()),
        )))
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(lay.totalRow, lay.totalRow + 1, 1, cols),
            cell = filled(TOTAL_BG, DARK_TEXT, 10, bold = true, align = RIGHT, edge = thickEdges()),
        )))

        // Секция «Расходы».
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(lay.expensesBanner, lay.expensesBanner + 1),
            cell = filled(SECTION_BG, HEADER_FG, 10, bold = true, edge = thickEdges()),
        )))
        val expHeader = lay.expensesHeaderRow
        if (expHeader != null) {
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(expHeader, expHeader + 1, 0, 5),
                cell = filled(LIGHT_HEADER_BG, DARK_TEXT, 10, bold = true, align = CENTER, edge = borders()),
            )))
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(lay.expensesDataStart, lay.expensesDataEnd, 0, 5),
                cell = fmt(edge = borders()),
            )))
            for ((i, r) in (lay.expensesDataStart until lay.expensesDataEnd).withIndex()) {
                val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(r, r + 1, 0, 5),
                    cell = fmt(bg = bg, edge = borders()),
                )))
            }
            listOf(2, 4).forEach { c ->
                for ((i, r) in (lay.expensesDataStart until lay.expensesDataEnd).withIndex()) {
                    val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                    req.add(Request(repeatCell = RepeatCellRequest(
                        range = range(r, r + 1, c, c + 1),
                        cell = fmt(bg = bg, align = RIGHT, edge = borders()),
                    )))
                }
            }
        } else {
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(lay.expensesDataStart, lay.expensesDataStart + 1),
                cell = fmt(fg = MUTED, italic = true, edge = borders()),
            )))
        }

        // Секция «Исключённые».
        val exBanner = lay.excludedBanner
        if (exBanner != null && lay.excludedHeaderRow != null) {
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(exBanner, exBanner + 1),
                cell = filled(SECTION_BG, HEADER_FG, 10, bold = true, edge = thickEdges()),
            )))
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(lay.excludedHeaderRow, lay.excludedHeaderRow + 1, 0, 4),
                cell = filled(LIGHT_HEADER_BG, DARK_TEXT, 10, bold = true, align = CENTER, edge = borders()),
            )))
            req.add(Request(repeatCell = RepeatCellRequest(
                range = range(lay.excludedDataStart, lay.excludedDataEnd, 0, 4),
                cell = fmt(edge = borders()),
            )))
            for ((i, r) in (lay.excludedDataStart until lay.excludedDataEnd).withIndex()) {
                val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(r, r + 1, 0, 4),
                    cell = fmt(bg = bg, edge = borders()),
                )))
            }
            listOf(1, 2, 3).forEach { c ->
                for ((i, r) in (lay.excludedDataStart until lay.excludedDataEnd).withIndex()) {
                    val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                    req.add(Request(repeatCell = RepeatCellRequest(
                        range = range(r, r + 1, c, c + 1),
                        cell = fmt(bg = bg, align = RIGHT, edge = borders()),
                    )))
                }
            }
        }

        // Закрепляем строки шапки (титул + пустая + заголовки).
        req.add(Request(updateSheetProperties = UpdateSheetPropertiesRequest(
            properties = SheetPropertiesUpdate(sheetId = sheetId, gridProperties = GridPropertiesUpdate(frozenRowCount = 3)),
            fields = "gridProperties.frozenRowCount",
        )))
        // Ширина колонок по содержимому и высота строк таблицы — попросторнее.
        req.add(Request(autoResizeDimensions = AutoResizeDimensionsRequest(
            dimensions = DimensionRange(sheetId, startIndex = 0, endIndex = cols),
        )))
        req.add(Request(updateDimensionProperties = UpdateDimensionPropertiesRequest(
            range = DimensionRange(sheetId, dimension = "ROWS", startIndex = lay.tableHeaderRow, endIndex = lay.totalRow + 1),
            properties = DimensionsProperties(pixelSize = 24),
            fields = "pixelSize",
        )))
        return req
    }

    /**
     * Оформление «служебного» листа: шапка, рамки, правое выравнивание чисел,
     * чередование (если строк немного), закрепление шапки, ширина колонок.
     */
    private fun listSheetRequests(
        sheetId: Int?,
        header: Int,
        rightAligned: List<Int>,
        dataCount: Int,
        banding: Boolean,
    ): List<Request> {
        if (sheetId == null) return emptyList()
        val req = mutableListOf<Request>()
        val lastRow = dataCount + 1 // +шапка

        fun range(rs: Int, re: Int, cs: Int = 0, ce: Int = header) =
            GridRange(sheetId, rs, re, cs, ce)

        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(0, lastRow),
            cell = fmt(edge = borders()),
        )))
        req.add(Request(repeatCell = RepeatCellRequest(
            range = range(0, 1),
            cell = filled(LIGHT_HEADER_BG, DARK_TEXT, 10, bold = true, align = CENTER, edge = borders()),
        )))

        if (banding) {
            for ((i, r) in (1 until lastRow).withIndex()) {
                val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(r, r + 1),
                    cell = fmt(bg = bg, edge = borders()),
                )))
            }
        }
        rightAligned.forEach { c ->
            if (banding) {
                for ((i, r) in (1 until lastRow).withIndex()) {
                    val bg = if (i % 2 == 0) BAND_EVEN else BAND_ODD
                    req.add(Request(repeatCell = RepeatCellRequest(
                        range = range(r, r + 1, c, c + 1),
                        cell = fmt(bg = bg, align = RIGHT, edge = borders()),
                    )))
                }
            } else {
                req.add(Request(repeatCell = RepeatCellRequest(
                    range = range(0, lastRow, c, c + 1),
                    cell = fmt(align = RIGHT, edge = borders()),
                )))
            }
        }

        req.add(Request(updateSheetProperties = UpdateSheetPropertiesRequest(
            properties = SheetPropertiesUpdate(sheetId = sheetId, gridProperties = GridPropertiesUpdate(frozenRowCount = 1)),
            fields = "gridProperties.frozenRowCount",
        )))
        req.add(Request(autoResizeDimensions = AutoResizeDimensionsRequest(
            dimensions = DimensionRange(sheetId, startIndex = 0, endIndex = header),
        )))
        return req
    }

    /** Порядок вкладок, их цвета и сокрытие служебной «Настройки». */
    private fun tuningRequests(sheetIds: Map<String, Int>): List<Request> {
        val order = mapOf(
            Tabs.SUMMARY to 0,
            Tabs.MEMBERS to 1,
            Tabs.PAYMENTS to 2,
            Tabs.COLLECTIONS to 3,
            Tabs.EXPENSES to 4,
            Tabs.SETTINGS to 5,
        )
        val colors = mapOf(
            Tabs.SUMMARY to TAB_SUMMARY,
            Tabs.MEMBERS to TAB_MEMBERS,
            Tabs.PAYMENTS to TAB_PAYMENTS,
            Tabs.EXPENSES to TAB_EXPENSES,
            Tabs.COLLECTIONS to TAB_COLLECTIONS,
            Tabs.SETTINGS to TAB_SETTINGS,
        )
        val req = mutableListOf<Request>()
        sheetIds.forEach { (title, id) ->
            // Пропускаем чужие/неизвестные листы, чтобы не слать null-свойства и не ронять весь батч.
            val idx = order[title] ?: return@forEach
            val tabColor = colors[title] ?: return@forEach
            req.add(Request(updateSheetProperties = UpdateSheetPropertiesRequest(
                properties = SheetPropertiesUpdate(sheetId = id, index = idx),
                fields = "index",
            )))
            req.add(Request(updateSheetProperties = UpdateSheetPropertiesRequest(
                properties = SheetPropertiesUpdate(sheetId = id, tabColor = tabColor),
                fields = "tabColor",
            )))
            if (title == Tabs.SETTINGS) {
                req.add(Request(updateSheetProperties = UpdateSheetPropertiesRequest(
                    properties = SheetPropertiesUpdate(
                        sheetId = id,
                        gridProperties = GridPropertiesUpdate(hidden = true),
                    ),
                    fields = "gridProperties.hidden",
                )))
            }
        }
        return req
    }
}