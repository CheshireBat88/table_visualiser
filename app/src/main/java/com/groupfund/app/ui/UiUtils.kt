package com.groupfund.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.time.YearMonth

private val MONTHS_RU = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
)

private val MONTHS_RU_SHORT = listOf(
    "янв", "фев", "мар", "апр", "май", "июн",
    "июл", "авг", "сен", "окт", "ноя", "дек",
)

fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

fun copyUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("spreadsheet-url", url))
    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
}

/** Генерирует QR-код (Bitmap) для произвольного текста/ссылки. */
fun qrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    bmp
}.getOrNull()

/** Полное название месяца, например "Сентябрь 2026". */
fun monthFullLabel(month: YearMonth): String =
    "${MONTHS_RU[month.monthValue - 1]} ${month.year}"

/** Короткое название, например "сен 26". */
fun monthShortLabel(month: YearMonth): String =
    "${MONTHS_RU_SHORT[month.monthValue - 1]} '${month.year % 100}"

/** Символ валюты для ISO-кода. */
fun currencySymbol(code: String): String = when (code) {
    "RUB" -> "₽"
    "USD" -> "$"
    "EUR" -> "€"
    else -> code
}