package com.groupfund.app.data.sheets

import com.groupfund.app.data.auth.TokenProvider
import com.groupfund.app.data.auth.TokenResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты сетевого слоя на MockWebServer: маркер передачи, права доступа и
 * опознание таблицы группы при приглашении. Раньше эти пути не проверялись
 * вовсе — ошибки в них приводили к «пустым» передачам и потере групп.
 */
class SheetsRepositoryTransferTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: SheetsRepository

    private val auth = object : TokenProvider {
        override suspend fun accessToken(): TokenResult = TokenResult.Success("test-token")
        override suspend fun invalidateToken(token: String) = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        repository = SheetsRepository(auth, sheetsBaseUrl = base, driveBaseUrl = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun okJson(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun metaWithCoreTabs() = okJson(
        """
        {"spreadsheetId":"sid","properties":{"title":"Группа"},"spreadsheetUrl":"https://docs/x",
         "sheets":[
           {"properties":{"title":"Сводка"}},
           {"properties":{"title":"Настройки"}},
           {"properties":{"title":"Участники"}},
           {"properties":{"title":"Платежи"}},
           {"properties":{"title":"Расходы"}}
         ]}
        """.trimIndent(),
    )

    @Test
    fun `маркер передачи читается из E1-F2`() = runBlocking {
        val copyId = "1AbCdEfGhIjKlMnOpQrStUv"
        server.enqueue(
            okJson("""{"values":[["transferPending","ABCD2345"],["transferTo","$copyId"]]}"""),
        )
        val marker = repository.readTransferMarker("sid").getOrThrow()
        assertEquals("ABCD2345", marker.pendingCode)
        assertEquals(copyId, marker.newSpreadsheetId)
    }

    @Test
    fun `заметка в свободных столбцах E-F не считается маркером`() = runBlocking {
        // До появления передачи столбцы E:F были свободны: заметка не должна
        // уводить группу на посторонний id или блокировать передачу.
        server.enqueue(
            okJson(
                """{"values":[["Заметка","позвонить Васе"],["Ссылка","https://example.com"]]}""",
            ),
        )
        val marker = repository.readTransferMarker("sid").getOrThrow()
        assertNull(marker.pendingCode)
        assertNull(marker.newSpreadsheetId)
        assertTrue(!transferStartBlocked(marker))
    }

    @Test
    fun `маркер с ключами но неверным форматом игнорируется`() = runBlocking {
        server.enqueue(
            okJson("""{"values":[["transferPending","ABC"],["transferTo","не id"]]}"""),
        )
        val marker = repository.readTransferMarker("sid").getOrThrow()
        assertNull(marker.pendingCode)
        assertNull(marker.newSpreadsheetId)
    }

    @Test
    fun `пустой маркер не блокирует передачу`() = runBlocking {
        server.enqueue(okJson("""{"values":[]}"""))
        val marker = repository.readTransferMarker("sid").getOrThrow()
        assertNull(marker.pendingCode)
        assertNull(marker.newSpreadsheetId)
        assertTrue(!transferStartBlocked(marker))
    }

    @Test
    fun `завершение передачи пишет id копии в E2`() = runBlocking {
        server.enqueue(okJson("{}"))
        repository.writeTransferDone("sid", "copy-1").getOrThrow()
        val request = server.takeRequest()
        assertTrue(request.path!!.contains("values:batchUpdate"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("transferTo"))
        assertTrue(body.contains("copy-1"))
        assertTrue(body.contains("E2:F2"))
    }

    @Test
    fun `очистка маркера стирает E1-F2 целиком`() = runBlocking {
        server.enqueue(okJson("{}"))
        repository.clearTransferMarker("copy-1").getOrThrow()
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("E1:F2"))
        // Две пустые строки: и код передачи, и id копии.
        assertTrue(body.contains("[[\"\",\"\"],[\"\",\"\"]]"))
    }

    @Test
    fun `открытие доступа по ссылке не плодит права`() = runBlocking {
        server.enqueue(
            okJson("""{"permissions":[{"id":"anyoneWithLink","type":"anyone","role":"reader"}]}"""),
        )
        repository.shareForInvite("sid").getOrThrow()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `открытие доступа по ссылке добавляет право один раз`() = runBlocking {
        server.enqueue(okJson("""{"permissions":[{"id":"u1","type":"user","role":"reader"}]}"""))
        server.enqueue(okJson("""{"id":"anyoneWithLink"}"""))
        repository.shareForInvite("sid").getOrThrow()
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val add = server.takeRequest()
        assertEquals("POST", add.method)
        assertTrue(add.path!!.contains("/permissions"))
        assertTrue(add.body.readUtf8().contains("anyone"))
    }

    @Test
    fun `закрытие доступа по ссылке удаляет право anyone`() = runBlocking {
        server.enqueue(
            okJson(
                """
                {"permissions":[
                  {"id":"u1","type":"user","role":"reader"},
                  {"id":"anyoneWithLink","type":"anyone","role":"reader"}
                ]}
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        repository.closeLinkSharing("sid").getOrThrow()
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.path!!.endsWith("/permissions/anyoneWithLink"))
    }

    @Test
    fun `приглашение принимает помеченную группу`() = runBlocking {
        server.enqueue(metaWithCoreTabs())
        server.enqueue(okJson("""{"id":"sid","appProperties":{"app":"groupfund"}}"""))
        val entry = repository.prepareInvite("sid").getOrThrow()
        assertEquals("sid", entry.spreadsheetId)
        assertEquals("observer", entry.role)
        // Метка в appProperties достаточна: «Настройки» читать не нужно.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `приглашение отвергает таблицу с теми же листами но без настроек`() = runBlocking {
        server.enqueue(metaWithCoreTabs())
        server.enqueue(okJson("""{"id":"sid"}"""))
        server.enqueue(okJson("""{"values":[["Название","Дата"],["Пицца","2026-01-01"]]}"""))
        val result = repository.prepareInvite("sid")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("не таблица группы"))
    }

    @Test
    fun `приглашение принимает старую таблицу без метки по настройкам`() = runBlocking {
        server.enqueue(metaWithCoreTabs())
        server.enqueue(okJson("""{"id":"sid"}"""))
        server.enqueue(
            okJson(
                """
                {"values":[
                  ["groupName","Группа"],
                  ["baseAmount","1000"],
                  ["currency","RUB"],
                  ["createdAt","2026-01-01"],
                  [],
                  ["Месяц","Обязательный","Сумма"],
                  ["01.2026","да","1000"]
                ]}
                """.trimIndent(),
            ),
        )
        val entry = repository.prepareInvite("sid").getOrThrow()
        assertEquals("observer", entry.role)
    }

    @Test
    fun `ошибка удаления с диска возвращается как failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val result = repository.deleteFromDrive("sid")
        assertTrue(result.isFailure)
    }
}
