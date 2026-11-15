package com.groupfund.app.data.sheets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тесты очистки осиротевшей таблицы при сбое пост-обработки создания группы (N8). */
class OrphanCleanupTest {

    @Test
    fun `успешная пост-обработка не удаляет созданный файл`() = runBlocking {
        var cleaned = false
        var done = false
        withOrphanCleanup(
            cleanup = { cleaned = true },
            block = { done = true },
        )
        assertTrue(done)
        assertEquals(false, cleaned)
    }

    @Test
    fun `сбой пост-обработки удаляет файл и пробрасывает исходную ошибку`() = runBlocking {
        var cleaned = false
        val failure = IllegalStateException("batchUpdateValues failed")
        val thrown = runCatching {
            withOrphanCleanup(
                cleanup = { cleaned = true },
                block = { throw failure },
            )
        }.exceptionOrNull()
        assertTrue(cleaned)
        assertSame(failure, thrown)
    }

    @Test
    fun `ошибка удаления не затирает исходную и уходит в лог-колбэк`() = runBlocking {
        val failure = IllegalStateException("addPermission failed")
        val cleanupFailure = IllegalStateException("deleteFile failed")
        var reported: Throwable? = null
        val thrown = runCatching {
            withOrphanCleanup(
                cleanup = { throw cleanupFailure },
                onCleanupError = { reported = it },
                block = { throw failure },
            )
        }.exceptionOrNull()
        assertSame(failure, thrown)
        // Ошибка очистки может быть пересоздана kotlinx.coroutines (stack-trace recovery),
        // поэтому сверяем тип и сообщение, а не идентичность экземпляра.
        assertTrue(reported is IllegalStateException)
        assertEquals(cleanupFailure.message, reported?.message)
    }

    @Test
    fun `отмена не мешает удалить наполовину созданный файл`() = runBlocking {
        var cleaned = false
        val thrown = runCatching {
            withOrphanCleanup(
                cleanup = { cleaned = true },
                block = { throw CancellationException("cancelled") },
            )
        }.exceptionOrNull()
        assertTrue(cleaned)
        assertTrue(thrown is CancellationException)
    }
}
