package com.groupfund.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Тесты разбора ссылки передачи группы. */
class TransferLinkTest {

    private val id = "1AbCdEfGhIjKlMnOpQrStUv"
    private val code = "ABCD2345"

    @Test
    fun `схема приложения и веб-ссылка дают id и код`() {
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("groupfund://transfer/$id?code=$code"),
        )
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("https://cheshirebat88.github.io/transfer/$id?code=$code"),
        )
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("HTTPS://CHESHIREBAT88.GITHUB.IO/TRANSFER/$id?code=$code"),
        )
    }

    @Test
    fun `лишние параметры и слэш не мешают`() {
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("https://cheshirebat88.github.io/transfer/$id/?code=$code&from=qr"),
        )
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("https://cheshirebat88.github.io/transfer/$id?utm=1&code=$code#top"),
        )
        assertEquals(
            TransferLinkParts(id, code),
            parseTransferLink("  $id|$code  "),
        )
    }

    @Test
    fun `посторонний текст не считается передачей`() {
        assertNull(parseTransferLink(""))
        assertNull(parseTransferLink("просто текст"))
        assertNull(parseTransferLink("groupfund://transfer/$id"))
        assertNull(parseTransferLink("groupfund://transfer/short?code=$code"))
        assertNull(parseTransferLink("https://example.com/transfer/$id?code=$code"))
        assertNull(parseTransferLink("groupfund://transfer/$id?code=короткий"))
        assertNull(parseTransferLink("groupfund://transfer/$id?code=ABCD"))
    }
}
