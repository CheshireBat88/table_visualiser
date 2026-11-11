package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка комментария ручного платежа (P0.7): служебные строки сборов
 * («Сбор: X» / «Возврат из сбора: X») нельзя вносить руками.
 *
 * Ручная строка «Сбор: X» с несуществующим сбором удалялась бы чисткой осиротевших
 * строк прямо в той же записи — пользователь видел «успех», а платежа и денег в
 * таблице не оказывалось. С существующим — ломала бы соответствие вклада и сводку.
 */
class ManualPaymentCommentTest {

    @Test
    fun `тег сбора отклоняется`() {
        assertEquals(
            "Взносы и возвраты по сборам создаются во вкладке «Сборы»",
            manualPaymentCommentError("Сбор: Подарок"),
        )
    }

    @Test
    fun `тег возврата из сбора отклоняется`() {
        assertEquals(
            "Взносы и возвраты по сборам создаются во вкладке «Сборы»",
            manualPaymentCommentError("Возврат из сбора: Подарок"),
        )
    }

    @Test
    fun `пробелы вокруг тега не обходят проверку`() {
        // Комментарий обрезается до проверки: "  Сбор: X " должен отклоняться,
        // а не уйти в таблицу строкой, которую чистка потом молча удалит.
        assertEquals(
            "Взносы и возвраты по сборам создаются во вкладке «Сборы»",
            manualPaymentCommentError("   Сбор: Подарок "),
        )
    }

    @Test
    fun `возврат участнику разрешён`() {
        // Регрессия: refundMember вносит платёж через appendPayment с комментарием
        // «Возврат: …» — это НЕ служебная строка сбора и блокироваться не должна.
        assertNull(manualPaymentCommentError("Возврат: сдал лишнее"))
        assertNull(manualPaymentCommentError("Возврат: "))
    }

    @Test
    fun `обычные комментарии разрешены`() {
        assertNull(manualPaymentCommentError(""))
        assertNull(manualPaymentCommentError("за январь"))
        assertNull(manualPaymentCommentError("наличные"))
    }

    @Test
    fun `слово без двоеточия и пробела - обычный комментарий`() {
        // Граница тега — «Сбор: » именно с пробелом (как у чистки и сводки):
        // «Сбор:» без пробела не является служебной строкой и трактуется как
        // обычный текст во всём приложении — проверка этому не противоречит.
        assertNull(manualPaymentCommentError("Сбор:"))
        assertNull(manualPaymentCommentError("Сбор на чай"))
        assertNull(manualPaymentCommentError("Возврат из сбора"))
    }
}
