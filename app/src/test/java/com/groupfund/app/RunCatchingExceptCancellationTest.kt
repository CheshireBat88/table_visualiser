package com.groupfund.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * runCatchingExceptCancellation (N1): отмена корутины не должна выглядеть успешной
 * операцией — CancellationException пробрасывается, прочие ошибки гасятся в Result.
 */
class RunCatchingExceptCancellationTest {

    @Test
    fun `успех - значение приходит в Result`() {
        val r: Result<Int> = runCatchingExceptCancellation { 2 * 21 }
        assertEquals(42, r.getOrNull())
        assertEquals(null, r.exceptionOrNull())
    }

    @Test
    fun `обычная ошибка - гасится в failure как в runCatching`() {
        val r = runCatchingExceptCancellation { error("HTTP 500") }
        assertTrue(r.isFailure)
        assertEquals("HTTP 500", r.exceptionOrNull()?.message)
    }

    @Test
    fun `CancellationException - пробрасывается наружу`() {
        var thrown: Throwable? = null
        try {
            runCatchingExceptCancellation<Unit> { throw CancellationException("отмена") }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("ожидали CancellationException, получили $thrown", thrown is CancellationException)
    }

    @Test
    fun `suspend вызов в блоке - отмена пробрасывается а не гасится`() {
        var thrown: Throwable? = null
        runBlocking {
            try {
                runCatchingExceptCancellation { cancelledSuspendCall() }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("ожидали CancellationException, получили $thrown", thrown is CancellationException)
    }

    @Test
    fun `suspend вызов в блоке - обычная ошибка гасится в failure`() {
        runBlocking {
            val r = runCatchingExceptCancellation { networkFailure() }
            assertTrue(r.isFailure)
            assertEquals("HTTP 500", r.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `IllegalStateException с причиной-отменой - это не отмена, гасится в failure`() {
        // CancellationException наследует IllegalStateException, но проверка идёт по
        // самому исключению: обёртка с причиной-отменой — обычная ошибка, не проброс.
        runBlocking {
            val r = runCatchingExceptCancellation<Unit> {
                throw IllegalStateException("отмена", CancellationException("причина"))
            }
            assertTrue(r.isFailure)
            assertTrue(r.exceptionOrNull() is IllegalStateException)
        }
    }

    private suspend fun networkFailure(): Int = error("HTTP 500")

    private suspend fun cancelledSuspendCall(): Unit = throw CancellationException("отмена корутины")
}
