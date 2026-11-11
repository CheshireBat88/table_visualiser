package com.groupfund.app

import kotlinx.coroutines.CancellationException

/**
 * runCatching, не глотающий отмену корутины: [CancellationException] пробрасывается
 * наружу, остальные исключения оборачиваются в Result.failure.
 *
 * Обычный runCatching ловит и отмену: корутина, отменённая уходом с экрана, свайпом
 * приложения из памяти или остановкой воркера, вместо падения «успешно» завершает
 * блок — ViewModel считает работу сделанной, WorkManager возвращает success, хотя
 * сетевой вызов оборвался на середине. Проброшенная отмена гасится выше по structured
 * concurrency там, где она и задумана.
 *
 * Функция inline — как и stdlib runCatching: suspend-вызовы внутри блока работают
 * в контексте вызывающей suspend-функции.
 */
inline fun <T> runCatchingExceptCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
