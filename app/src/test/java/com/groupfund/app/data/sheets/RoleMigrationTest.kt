package com.groupfund.app.data.sheets

import com.groupfund.app.data.registry.GroupEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleMigrationTest {

    private fun group(spreadsheetId: String, role: String) = GroupEntry(
        id = "id-$spreadsheetId",
        title = "Группа",
        spreadsheetId = spreadsheetId,
        spreadsheetUrl = "https://docs.google.com/spreadsheets/d/$spreadsheetId",
        role = role,
        createdAtEpochMillis = 0L,
    )

    @Test
    fun `своя таблица с ролью observer возвращается к creator`() {
        val entries = listOf(group("a", "observer"))
        assertEquals(listOf("a"), observerToCreatorCandidates(entries, setOf("a")))
    }

    @Test
    fun `чужая таблица с ролью observer не трогается`() {
        val entries = listOf(group("b", "observer"))
        assertEquals(emptyList<String>(), observerToCreatorCandidates(entries, setOf("a")))
    }

    @Test
    fun `своя таблица с корректной ролью не трогается`() {
        val entries = listOf(group("a", "creator"), group("a", "member"))
        assertEquals(emptyList<String>(), observerToCreatorCandidates(entries, setOf("a")))
    }

    @Test
    fun `смесь чинит только свою observer`() {
        val entries = listOf(
            group("mine", "observer"),
            group("foreign", "observer"),
            group("transferred", "observer"),
        )
        assertEquals(listOf("mine"), observerToCreatorCandidates(entries, setOf("mine", "other")))
    }
}