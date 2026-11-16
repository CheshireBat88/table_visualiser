package com.groupfund.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тесты разбора QR-кода/ссылки приглашения. */
class InviteLinkTest {

    private val id = "1AbCdEfGhIjKlMnOpQrStUv"

    @Test
    fun `схема groupfund и веб-ссылка дают id`() {
        assertEquals(id, parseInviteTarget("groupfund://invite/$id"))
        assertEquals(id, parseInviteTarget("https://cheshirebat88.github.io/invite/$id"))
        assertEquals(id, parseInviteTarget("https://cheshirebat88.github.io/invite/$id/"))
        assertEquals(id, parseInviteTarget("https://cheshirebat88.github.io/invite/$id?from=qr"))
    }

    @Test
    fun `ссылка Google Таблиц даёт id`() {
        assertEquals(
            id,
            parseInviteTarget("https://docs.google.com/spreadsheets/d/$id/edit#gid=0"),
        )
    }

    @Test
    fun `голый id таблицы принимается`() {
        assertEquals(id, parseInviteTarget("  $id  "))
    }

    @Test
    fun `посторонний QR не считается приглашением`() {
        assertNull(parseInviteTarget(""))
        assertNull(parseInviteTarget("просто текст"))
        assertNull(parseInviteTarget("https://example.com/invite/$id"))
        assertNull(parseInviteTarget("groupfund://invite/short"))
        assertNull(parseInviteTarget("https://docs.google.com/spreadsheets/d/короткий/edit"))
    }
}
