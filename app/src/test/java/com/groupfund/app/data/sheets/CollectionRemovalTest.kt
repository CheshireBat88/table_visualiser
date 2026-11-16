package com.groupfund.app.data.sheets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты удаления участника из сбора с выбором судьбы его взноса. */
class CollectionRemovalTest {

    private fun collection(
        participants: List<String> = listOf("A", "B", "C"),
        contrib: Map<String, Double> = mapOf("A" to 100.0, "B" to 50.0),
        name: String = "Gift",
    ) = CollectionData(
        name = name,
        date = "2026-01-01",
        target = 300.0,
        participants = participants,
        contrib = contrib,
        sharedWithBudget = true,
    )

    private fun payment(member: String, amount: Double, comment: String) =
        PaymentData(member, "2026-01-01", amount, comment)

    @Test
    fun `находит только убираемых участников с деньгами`() {
        val c = collection()
        assertEquals(listOf("B"), removedCollectionContributors(c, listOf("A", "C")))
        assertTrue(removedCollectionContributors(c, listOf("A", "B", "C")).isEmpty())
    }

    @Test
    fun `без денег удаление разрешено, с деньгами требует решения`() {
        val c = collection()
        assertNull(collectionParticipantRemovalError(c, listOf("A", "B", "C")))
        val error = collectionParticipantRemovalError(c, listOf("A", "C"))
        assertTrue(error != null && "B" in error)
    }

    @Test
    fun `возврат убирает все строки сбора вышедшего участника`() {
        val source = listOf(
            payment("B", 50.0, "Сбор: Gift"),
            payment("B", 20.0, "Возврат из сбора: Gift"),
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 10.0, "Обычный платёж"),
        )
        val base = listOf(
            payment("B", 20.0, "Возврат из сбора: Gift"),
            payment("A", 100.0, "Сбор: Gift"),
            payment("A", 10.0, "Обычный платёж"),
        )
        val out = settleRemovedCollectionContributions(
            basePayments = base,
            sourcePayments = source,
            collection = collection(),
            participants = listOf("A", "C"),
            oldName = "Gift",
            newName = "Gift",
            action = RemovedContributionAction.REFUND,
        )
        assertTrue(out.none { it.member == "B" })
        assertEquals(2, out.size)
        assertEquals(100.0, out.first { it.member == "A" && it.comment == "Сбор: Gift" }.amount, 0.001)
        assertEquals(10.0, out.first { it.member == "A" && it.comment == "Обычный платёж" }.amount, 0.001)
    }

    @Test
    fun `перевод в месячные заменяет строки сбора одной обычной строкой`() {
        val source = listOf(
            payment("B", 50.0, "Сбор: Gift"),
            payment("B", 20.0, "Возврат из сбора: Gift"),
            payment("A", 100.0, "Сбор: Gift"),
        )
        val base = listOf(
            payment("B", 20.0, "Возврат из сбора: Gift"),
            payment("A", 100.0, "Сбор: Gift"),
        )
        val out = settleRemovedCollectionContributions(
            basePayments = base,
            sourcePayments = source,
            collection = collection(),
            participants = listOf("A", "C"),
            oldName = "Gift",
            newName = "Gift",
            action = RemovedContributionAction.MONTHLY,
        )
        val b = out.filter { it.member == "B" }
        assertEquals(1, b.size)
        assertEquals(70.0, b.single().amount, 0.001)
        assertEquals("Перенос из сбора: Gift", b.single().comment)
        // Деньги оставшихся участников не тронуты.
        assertEquals(100.0, out.first { it.member == "A" }.amount, 0.001)
    }

    @Test
    fun `без решения строки не меняются`() {
        val base = listOf(payment("B", 50.0, "Сбор: Gift"))
        val out = settleRemovedCollectionContributions(
            basePayments = base,
            sourcePayments = base,
            collection = collection(),
            participants = listOf("A", "C"),
            oldName = "Gift",
            newName = "Gift",
            action = null,
        )
        assertEquals(base, out)
    }
}
