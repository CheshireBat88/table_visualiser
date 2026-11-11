package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистка «осиротевших» платежей сборов (N2+N3): пока «Сборы» прочитаны успешно —
 * лишние строки удаляются; при неудачном чтении ничего не трогаем.
 */
class SanitizeCollectionPaymentsTest {

    private val payments = listOf(
        PaymentData("A", "2026-01-01", 126.67, "Сбор: Подарок"),
        PaymentData("A", "2026-01-02", 73.33, "Возврат из сбора: Подарок"),
        PaymentData("B", "2026-01-03", 200.0, ""),
        PaymentData("C", "2026-01-04", 30.0, "Сбор: Удалённый"),
        PaymentData("C", "2026-01-05", 20.0, "Возврат из сбора: Удалённый"),
        PaymentData("C", "2026-01-06", 15.0, "Отмененный сбор: Подарок"),
    )

    private val collections = listOf(
        CollectionData("Подарок", "2026-01-01", 380.0, listOf("A", "B"), mapOf("A" to 200.0), true),
    )

    @Test
    fun `чтение Сборов упало - платежи по сборам не удаляются`() {
        // Ключевой кейс фикса: список сборов неизвестен (лист мог не прочитаться),
        // поэтому чистка отключена целиком — взносы не теряются.
        val kept = sanitizeCollectionPayments(payments, collections = emptyList(), collectionsReadOk = false)
        assertEquals(payments, kept)
    }

    @Test
    fun `чтение ок и сбора больше нет - осиротевшие строки удаляются`() {
        // Прежнее поведение сохранено: чистка работает при успешном чтении.
        val kept = sanitizeCollectionPayments(payments, collections, collectionsReadOk = true)
        assertEquals(listOf(payments[0], payments[1], payments[2], payments[5]), kept)
    }

    @Test
    fun `чтение ок и все сборы на месте - ничего не удаляется`() {
        val allCollections = collections + CollectionData(
            "Удалённый", "2026-01-01", 60.0, listOf("C"), emptyMap(), false,
        )
        val kept = sanitizeCollectionPayments(payments, allCollections, collectionsReadOk = true)
        assertEquals(payments, kept)
    }

    @Test
    fun `имя сбора в комментарии обрезается по краям`() {
        val kept = sanitizeCollectionPayments(
            listOf(PaymentData("A", "2026-01-01", 10.0, "Сбор:  Подарок ")),
            collections,
            collectionsReadOk = true,
        )
        // «Сбор:  Подарок » → имя «Подарок» после trim → строка валидна.
        assertEquals(1, kept.size)
    }
}
