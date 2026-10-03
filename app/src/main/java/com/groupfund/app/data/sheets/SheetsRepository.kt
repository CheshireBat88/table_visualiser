package com.groupfund.app.data.sheets

import android.util.Log
import com.groupfund.app.BuildConfig
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.auth.OAuthConsentRequiredException
import com.groupfund.app.data.auth.TokenResult
import com.groupfund.app.data.registry.GroupEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Одна запись внесения (для пакетного ввода): участник, сумма, необязательный тег сбора. */
data class PaymentInput(
    val member: String,
    val amount: Double,
    val comment: String = "",
)

/** Таблица не найдена: удалена или доступ к ней ограничен (HTTP 404 от Google). */
class NotFoundException(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * Маркер передачи группы, живущий в листе «Настройки» прежней таблицы (столбцы E:F).
 * Пока идёт передача — заполнен `pendingCode`; после завершения — `newSpreadsheetId`
 * (id копии), и все участники при следующем запуске автоматически переключаются на неё.
 */
data class TransferMarker(
    val pendingCode: String? = null,
    val newSpreadsheetId: String? = null,
)

/**
 * Репозиторий поверх Sheets/Drive API. Внутри берёт актуальный OAuth-токен
 * из GoogleAuthManager и подставляет его в каждый запрос.
 *
 * Таблица — источник истины: «Сводка» всегда пересчитывается локально из
 * листов Участники/Платежи/Расходы/Настройки и перезаписывается целиком.
 */
class SheetsRepository(private val auth: GoogleAuthManager) {

    /** Маркер в appProperties файла Drive — «создано приложением». */
    private companion object {
        const val APP_MARKER_KEY = "app"
        const val APP_MARKER_VALUE = "groupfund"
        const val MAX_PAGES = 10
        /** Попыток при 429 (экспоненциальный backoff 1с → 2с → 4с → … до ~60с). */
        const         val MAX_RATE_LIMIT_ATTEMPTS = 6

        val A1_CELL = Regex("^([A-Za-z]+)(\\d+)$")
        /** Маркер передачи в листе «Настройки»: E1:F1 — код передачи, E2:F2 — id копии. */
        val TRANSFER_MARKER_RANGE = "${Tabs.SETTINGS}!E1:F2"
        const val TRANSFER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    private val sheetsApi: SheetsApiService by lazy {
        buildApi(SheetsApiService::class.java, "https://sheets.googleapis.com/")
    }

    private val driveApi: DriveApiService by lazy {
        buildApi(DriveApiService::class.java, "https://www.googleapis.com/")
    }

    /** sheetId по имени листа (кэш: id листов не меняются). */
    private val sheetIdCache = mutableMapOf<String, Map<String, Int>>()

    private fun <T> buildApi(klass: Class<T>, baseUrl: String): T {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                if (BuildConfig.DEBUG) level = HttpLoggingInterceptor.Level.BASIC
            })
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(klass)
    }

    /**
     * Обёртка: получает токен и делает сетевой вызов, пробрасывая согласие.
     * При 429 (лимит запросов) операция повторяется целиком с экспоненциальным backoff:
     * для read-modify-write это перечитывает свежее состояние и применяет изменение заново.
     * Повтор безопасен, только если запись не оставляет лист в промежуточном пустом
     * состоянии — поэтому writeData пишет данные ДО очистки «хвостов» (см. ниже).
     */
    private suspend fun <T> withToken(
        retryOn429: Boolean = true,
        block: suspend (SheetsApiService, DriveApiService, String) -> T,
    ): Result<T> {
        val run: suspend (String) -> T = { token ->
            if (retryOn429) retryOnRateLimit { block(sheetsApi, driveApi, token) }
            else block(sheetsApi, driveApi, token)
        }
        return when (val tr = auth.accessToken()) {
            is TokenResult.Success -> try {
                Result.success(run(tr.token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // 401 — токен протух; 403 — токен может не покрывать запрошенные скоупы
                // (GoogleAuthUtil для почты/AccountManager иногда возвращает кэш токена
                // с частью скоупов, из-за чего Sheets/Drive отвечают PERMISSION_DENIED).
                // В обоих случаях сбрасываем кэш GoogleAuthUtil (иначе getToken вернёт
                // тот же токен) и ровно один раз повторяем вызов со свежим токеном.
                if (e.code() != 401 && e.code() != 403) {
                    Result.failure(illegal(e))
                } else {
                    auth.invalidateToken(tr.token)
                    when (val retry = auth.accessToken()) {
                        is TokenResult.Success -> try {
                            Result.success(run(retry.token))
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: HttpException) {
                            Result.failure(illegal(e2))
                        } catch (e2: Exception) {
                            Result.failure(e2)
                        }

                        is TokenResult.ConsentRequired ->
                            Result.failure(OAuthConsentRequiredException(retry.intent))

                        is TokenResult.Error ->
                            Result.failure(IllegalStateException(retry.message, retry.cause))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            is TokenResult.ConsentRequired ->
                Result.failure(OAuthConsentRequiredException(tr.intent))

            is TokenResult.Error ->
                Result.failure(IllegalStateException(tr.message, tr.cause))
        }
    }

    /** Собирает читаемый текст для UI/логов: код + эндпоинт + обрывок тела ответа. */
    private fun illegal(e: HttpException): Exception {
        val response = e.response()
        val method = response?.raw()?.request?.method ?: "?"
        val url = response?.raw()?.request?.url?.encodedPath ?: "?"
        val body = response?.errorBody()?.string()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 400) it.take(400) + "…" else it }
        val message = if (body == null) {
            "HTTP ${e.code()} $method $url"
        } else {
            "HTTP ${e.code()} $method $url\n$body"
        }
        Log.w("SheetsApi", message, e)
        return if (e.code() == 404) NotFoundException(message, e) else IllegalStateException(message, e)
    }

    /**
     * Экспоненциальный backoff для 429: 1s → 2s → 4s → … (с минимальным джиттером,
     * чтобы клиенты не синхронизировались). Либо возвращает результат, либо бросает ошибку.
     */
    private suspend fun <T> retryOnRateLimit(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code() != 429 || attempt >= MAX_RATE_LIMIT_ATTEMPTS) throw e
            }
            val baseSeconds = 1L shl attempt
            delay(baseSeconds.coerceAtMost(60L) * 1000L + Random.nextLong(0L, 1000L))
            attempt++
        }
    }

    /** Создаёт «книгу» группы со всеми листами, правами на просмотр по ссылке. */
    suspend fun createGroup(draft: GroupDraft): Result<GroupEntry> = withToken(retryOn429 = false) { sheets, drive, token ->
        val authHeader = "Bearer $token"

        val response = sheets.createSpreadsheet(
            authHeader,
            CreateSpreadsheetRequest(
                properties = SpreadsheetPropertiesRequest(title = draft.title),
                sheets = Tabs.ALL.map {
                    SheetRequestContent(properties = SheetPropertiesRequest(title = it))
                },
            ),
        )
        val sid = response.spreadsheetId

        val emptyGroup = GroupData(
            spreadsheetId = sid,
            title = draft.title,
            baseAmount = draft.baseAmount,
            currency = draft.currency,
            createdAt = SheetCodec.today(),
            months = draft.months,
            members = draft.members.map { name ->
                MemberData(
                    name = name,
                    email = "",
                    joinDate = SheetCodec.today(),
                    active = true,
                    removedAt = null,
                    refund = null,
                    birthday = draft.memberBirthdays[name] ?: "",
                )
            },
            payments = emptyList(),
            expenses = emptyList(),
        )

        val values = mutableListOf(
            ValueRange("${Tabs.SETTINGS}!A1:C40", SheetCodec.settingsRows(draft.title, draft.baseAmount, draft.currency, SheetCodec.today(), draft.months)),
            ValueRange("${Tabs.MEMBERS}!A1:H200", SheetCodec.membersRows(emptyGroup.members)),
            ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsHeader()),
            ValueRange("${Tabs.EXPENSES}!A1:E1000", SheetCodec.expensesHeader()),
            ValueRange(
                "${Tabs.SUMMARY}!A1:R100",
                SummaryCalculator.emptySummaryRows(emptyGroup, draft.months),
            ),
        )
        sheets.batchUpdateValues(authHeader, sid, BatchUpdateValuesRequest(data = values))

        // Красивое оформление листов сразу при создании.
        runCatching {
            applyStyle(sheets, authHeader, sid, emptyGroup)
        }

        // Просмотр по ссылке — всем. Сбой не рушит создание, но логируется:
        // молчаливый провал превращался в 404 у наблюдателей.
        runCatching {
            drive.addPermission(authHeader, sid, body = PermissionRequest())
        }.onFailure { Log.w("SheetsApi", "addPermission failed for $sid", it) }

        // Маркер «создано приложением» — чтобы дикий Drive-поиск легко находил наши группы.
        runCatching {
            drive.patchFile(authHeader, sid, DriveFilePatch(mapOf(APP_MARKER_KEY to APP_MARKER_VALUE)))
        }.onFailure { Log.w("SheetsApi", "patchFile failed for $sid", it) }

        GroupEntry(
            id = UUID.randomUUID().toString(),
            title = draft.title,
            spreadsheetId = sid,
            spreadsheetUrl = response.spreadsheetUrl,
            role = "creator",
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /** Читает все листы и собирает содержимое группы. */
    suspend fun loadGroup(spreadsheetId: String): Result<GroupData> = withToken { sheets, _, token ->
        readGroup(sheets, token, spreadsheetId)
    }

    /**
     * Лёгкая проверка: доступна ли таблица текущему аккаунту (читает только метаданные).
     * true — доступ есть; false — таблицы нет или доступ закрыт; failure — проверить не удалось
     * (сеть/согласие), пометку менять не следует.
     */
    suspend fun checkAccessible(spreadsheetId: String): Result<Boolean> = withToken { sheets, _, token ->
        try {
            sheets.getSpreadsheetMeta("Bearer $token", spreadsheetId)
            true
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 401 || e.code() == 403) throw e // отдаём withToken: повторит со свежим токеном
            false // 404 и прочие — доступа нет
        }
    }

    /**
     * Открывает доступ «по ссылке» (тип anyone, роль reader). Раньше ошибки глотались
     * («уже доступна по ссылке»), из-за чего QR показывался, а доступа у наблюдателей
     * не было → Google отдавал им 404. Теперь ошибка доходит до withToken, который
     * лечит 401/403 свежим токеном, а при настоящем отказе предупредит создателя.
     */
    suspend fun shareForInvite(spreadsheetId: String): Result<Unit> = withToken { _, drive, token ->
        drive.addPermission("Bearer $token", spreadsheetId, body = PermissionRequest())
        Unit
    }

    /** Переименовывает таблицу на Drive (для создателя): новое имя видят все участники. */
    suspend fun renameSpreadsheet(spreadsheetId: String, newTitle: String): Result<Unit> =
        withToken { _, drive, token ->
            val name = newTitle.trim()
            if (name.isEmpty()) error("Укажите название")
            drive.renameFile("Bearer $token", spreadsheetId, DriveFileRename(name))
            Unit
        }

    /**
     * Готовит запись группы для наблюдателя по ID таблицы: проверяет,
     * что это настоящая таблица группы, открывает доступ «по ссылке»
     * и возвращает GroupEntry с ролью "observer".
     */
    suspend fun prepareInvite(spreadsheetId: String): Result<GroupEntry> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val meta = try {
            sheets.getSpreadsheetMeta(authHeader, spreadsheetId)
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                throw IllegalStateException(
                    "Таблица не найдена или недоступна для вашего аккаунта. " +
                        "Попросите создателя ещё раз нажать «Пригласить наблюдателей», чтобы открыть доступ по ссылке.",
                    e,
                )
            }
            throw e
        }
        val titles = meta.sheets.orEmpty().mapNotNull { it.properties?.title }
        if (!Tabs.CORE.all { it in titles }) {
            error("Это не таблица группы: отсутствуют нужные листы")
        }
        GroupEntry(
            id = UUID.randomUUID().toString(),
            title = meta.properties?.title?.takeIf { it.isNotBlank() } ?: "Группа",
            spreadsheetId = spreadsheetId,
            spreadsheetUrl = meta.spreadsheetUrl ?: "",
            role = "observer",
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /** Удаляет файл таблицы с Google Drive (только если токен доступен). */
    suspend fun deleteFromDrive(spreadsheetId: String): Result<Unit> = withToken { _, drive, token ->
        drive.deleteFile("Bearer $token", spreadsheetId)
    }

    // ---------- Передача группы другому ведущему ----------

    /** Название и ссылка таблицы (для договора при передаче). */
    suspend fun spreadsheetMeta(spreadsheetId: String): Result<Pair<String, String>> =
        withToken { sheets, _, token ->
            val m = sheets.getSpreadsheetMeta("Bearer $token", spreadsheetId)
            (m.properties?.title?.takeIf { it.isNotBlank() } ?: "Группа") to (m.spreadsheetUrl ?: "")
        }

    /** Читает маркер передачи из листа «Настройки» прежней таблицы. */
    suspend fun readTransferMarker(spreadsheetId: String): Result<TransferMarker> =
        withToken { sheets, _, token ->
            val authHeader = "Bearer $token"
            val rows = runCatching {
                sheets.getValues(authHeader, spreadsheetId, TRANSFER_MARKER_RANGE).values.orEmpty()
            }.getOrDefault(emptyList())
            fun cell(row: Int, col: Int): String =
                rows.getOrNull(row)?.getOrNull(col)?.let { it.toString() }?.trim().orEmpty()
            TransferMarker(
                pendingCode = cell(0, 1).ifEmpty { null },
                newSpreadsheetId = cell(1, 1).ifEmpty { null },
            )
        }

    /** Пишет код передачи (создатель инициирует передачу; нужна запись в таблице). */
    suspend fun writeTransferPending(spreadsheetId: String, pendingCode: String): Result<Unit> =
        withToken { sheets, _, token ->
            sheets.batchUpdateValues(
                "Bearer $token",
                spreadsheetId,
                BatchUpdateValuesRequest(
                    data = listOf(
                        ValueRange("${Tabs.SETTINGS}!E1:F1", listOf(listOf("transferPending", pendingCode))),
                    ),
                ),
            )
            Unit
        }

    /** Отменяет начатую передачу: стирает код в E1:F1, ссылка перестаёт действовать. */
    suspend fun clearTransferPending(spreadsheetId: String): Result<Unit> =
        withToken { sheets, _, token ->
            sheets.batchUpdateValues(
                "Bearer $token",
                spreadsheetId,
                BatchUpdateValuesRequest(
                    data = listOf(
                        ValueRange("${Tabs.SETTINGS}!E1:F1", listOf(listOf("", ""))),
                    ),
                ),
            )
            Unit
        }

    /** Финализирует передачу: старая таблица объявляет id копии, участники переключатся на неё. */
    suspend fun writeTransferDone(spreadsheetId: String, newSpreadsheetId: String): Result<Unit> =
        withToken { sheets, _, token ->
            sheets.batchUpdateValues(
                "Bearer $token",
                spreadsheetId,
                BatchUpdateValuesRequest(
                    data = listOf(
                        ValueRange("${Tabs.SETTINGS}!E2:F2", listOf(listOf("transferTo", newSpreadsheetId))),
                    ),
                ),
            )
            Unit
        }

    /**
     * Копирует всю таблицу на диск пользователя-получателя (он становится владельцем копии),
     * переносит на копию права исходника и проверяет полноту копии: сверяет состав участников,
     * количество и суммы платежей/расходов/сборов и настройки с оригиналом.
     */
    suspend fun copySpreadsheetToMe(spreadsheetId: String): Result<GroupEntry> =
        withToken { sheets, drive, token ->
            suspend fun attempt(): GroupEntry = copySpreadsheetOnce(sheets, drive, token, spreadsheetId)
            try {
                attempt()
            } catch (e: retrofit2.HttpException) {
                if (e.code() != 404) throw e
                // Сразу после files.copy копия иногда ещё «не открыта» для чтения → 404.
                // Один повтор после короткой паузы лечит это.
                delay(1200)
                attempt()
            }
        }

    /** Однократная попытка скопировать таблицу и проверить полноту копии. */
    private suspend fun copySpreadsheetOnce(
        sheets: SheetsApiService,
        drive: DriveApiService,
        token: String,
        spreadsheetId: String,
    ): GroupEntry {
        val authHeader = "Bearer $token"
        val meta = spreadsheetMeta(spreadsheetId).getOrDefault("Группа" to "")
        val title = meta.first
        val copy = drive.copyFile(
            authHeader,
            spreadsheetId,
            DriveFileCopy(
                name = title,
                appProperties = mapOf(APP_MARKER_KEY to APP_MARKER_VALUE),
            ),
        )
        val copyId = copy.id ?: error("Google не вернул id копии")

        // Переносим права исходника на копию (кроме владельца — им становится получатель).
        val perms = runCatching {
            drive.listPermissions(authHeader, spreadsheetId).permissions.orEmpty()
        }.getOrDefault(emptyList())
        perms.filter { it.role != "owner" }.forEach { p ->
            runCatching {
                drive.addPermission(
                    authHeader,
                    copyId,
                    body = PermissionRequest(
                        role = p.role ?: "reader",
                        type = p.type ?: "anyone",
                        emailAddress = p.emailAddress,
                        domain = p.domain,
                    ),
                )
            }.onFailure { Log.w("SheetsApi", "addPermission on copy $copyId failed", it) }
        }

        // Проверка полноты: копия должна совпадать с оригиналом по составу и суммам.
        val original = readGroup(sheets, token, spreadsheetId)
        val copied = readGroup(sheets, token, copyId)
        verifyCopyComplete(original, copied)

        return GroupEntry(
            id = UUID.randomUUID().toString(),
            title = title,
            spreadsheetId = copyId,
            spreadsheetUrl = copy.webViewLink ?: "",
            role = "creator",
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /** Сверяет копию с оригиналом; при расхождении бросает понятную ошибку. */
    private fun verifyCopyComplete(old: GroupData, copy: GroupData) {
        val gaps = mutableListOf<String>()
        if (old.baseAmount != copy.baseAmount || old.currency != copy.currency) gaps += "настройки"
        if (old.months != copy.months) gaps += "план месяцев"
        if (old.members.map { it.name }.sorted() != copy.members.map { it.name }.sorted()) gaps += "участники"
        if (old.payments.size != copy.payments.size) gaps += "число платежей"
        if (round2(old.payments.sumOf { it.amount }) != round2(copy.payments.sumOf { it.amount })) gaps += "сумму платежей"
        if (old.expenses.size != copy.expenses.size) gaps += "число расходов"
        if (old.collections.map { it.name }.sorted() != copy.collections.map { it.name }.sorted()) gaps += "сборы"
        if (gaps.isNotEmpty()) {
            error("Копия неполная — не совпадают $gaps. Повторите передачу и проверьте, что диск не переполнен")
        }
    }

    /**
     * Ищет на Drive таблицы, созданные приложением (маркер appProperties)
     * или содержащие все листы группы (Сводка/Настройки/Участники/Платежи/Расходы).
     * Возвращает записи для добавления в локальный реестр.
     */
    suspend fun discoverGroups(): Result<List<GroupEntry>> = withToken { sheets, drive, token ->
        val authHeader = "Bearer $token"
        val found = mutableListOf<GroupEntry>()
        var pageToken: String? = null
        var pageCount = 0

        while (pageToken != null || pageCount == 0) {
            if (pageCount >= MAX_PAGES) break
            pageCount++
            val page = drive.listFiles(
                authHeader,
                query = "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false",
                fields = "nextPageToken,files(id,name,webViewLink,createdTime,appProperties)",
                pageSize = 100,
                pageToken = pageToken,
            )
            for (file in page.files.orEmpty()) {
                val id = file.id ?: continue
                val tabTitles = if (file.appProperties?.get(APP_MARKER_KEY) == APP_MARKER_VALUE) {
                    Tabs.CORE
                } else {
                    runCatching {
                        sheets.getSpreadsheetMeta(authHeader, id, "sheets.properties.title")
                            .sheets.orEmpty()
                            .mapNotNull { it.properties?.title }
                    }.getOrDefault(emptyList())
                }
                if (Tabs.CORE.all { it in tabTitles }) {
                    found += GroupEntry(
                        id = UUID.randomUUID().toString(),
                        title = file.name ?: "Группа",
                        spreadsheetId = id,
                        spreadsheetUrl = file.webViewLink ?: "",
                        role = "creator",
                        createdAtEpochMillis = parseDriveTime(file.createdTime),
                    )
                }
            }
            pageToken = page.nextPageToken
        }
        found
    }

    /** Добавляет платёж и пересчитывает сводку. Возвращает обновлённые данные. */
    suspend fun appendPayment(
        spreadsheetId: String,
        member: String,
        amount: Double,
        comment: String = "",
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        val updated = group.copy(
            payments = group.payments +
                PaymentData(member, SheetCodec.today(), round2(amount), comment),
        )
        val summary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(updated.payments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
            ),
        )
        updated
    }

    /**
     * Добавляет лист «Сборы», если его ещё нет (старые таблицы). Ошибку «уже есть» глотаем.
     */
    private suspend fun ensureCollectionsTab(
        sheets: SheetsApiService,
        authHeader: String,
        spreadsheetId: String,
    ) {
        runCatching {
            sheets.batchUpdateSpreadsheet(
                authHeader,
                spreadsheetId,
                SpreadsheetBatchUpdateRequest(
                    requests = listOf(
                        SpreadsheetBatchUpdateRequest.Request(
                            addSheet = AddSheetRequest(
                                properties = AddSheetProperties(title = Tabs.COLLECTIONS),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    /** Создаёт отдельный сбор: цель делится поровну на выбранных участников. */
    suspend fun createCollection(
        spreadsheetId: String,
        name: String,
        target: Double,
        participants: List<String>,
        sharedWithBudget: Boolean = true,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        if (name.isBlank()) error("Укажите название сбора")
        if (target <= 0) error("Укажите сумму сбора")
        if (participants.isEmpty()) error("Выберите участников")
        val group = readGroup(sheets, token, spreadsheetId)
        if (group.collections.any { it.name == name }) error("Сбор с таким названием уже есть")
        ensureCollectionsTab(sheets, authHeader, spreadsheetId)
        val updated = group.copy(
            collections = group.collections +
                CollectionData(
                    name = name,
                    date = SheetCodec.today(),
                    target = round2(target),
                    participants = participants.distinct(),
                    sharedWithBudget = sharedWithBudget,
                ),
        )
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange(
                    "${Tabs.COLLECTIONS}!A1:F1000",
                    SheetCodec.collectionsRows(updated.collections),
                ),
            ),
        )
        updated
    }

    /**
     * Галочка «сдал»: участник вносит всю свою долю одним платежом; при снятии — вклад убирается.
     * Платежи по сбору этого участника пересобираются, как отдельные строки.
     */
    suspend fun toggleMemberPaid(
        spreadsheetId: String,
        collectionName: String,
        memberName: String,
        paid: Boolean,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        val collection = group.collections.firstOrNull { it.name == collectionName }
            ?: error("Сбор не найден")
        if (memberName !in collection.participants) error("Участник не в этом сборе")
        val wasPaid = memberName in collection.paid
        if (wasPaid == paid) return@withToken group

        val share = collection.sharePerPerson
        val updatedColl = if (paid) {
            collection.copy(contrib = collection.contrib + (memberName to share))
        } else {
            collection.copy(contrib = collection.contrib - memberName)
        }
        val newCollections = group.collections.map { if (it.name == collectionName) updatedColl else it }
        val tag = "Сбор: $collectionName"
        val newPayments = if (paid) {
            // Доплата недостающей части до доли — отдельной строкой, не перезаписывая прошлые транши.
            val topUp = round2(share - collection.contributed(memberName))
            if (topUp > 0) {
                group.payments + PaymentData(memberName, SheetCodec.today(), topUp, tag)
            } else {
                group.payments
            }
        } else {
            // Снимаем взнос, но оставляем «Возврат из сбора» — он уже зачислен в месячный баланс.
            removeMemberCollectionPayments(group.payments, memberName, collectionName, keepRefunds = true)
        }
        val updated = group.copy(collections = newCollections, payments = newPayments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Доплата по сбору: добавляет участнику указанную сумму к вкладу.
     * Пока вклад не превысил долю, каждый транш ложится отдельной строкой «Сбор: …»,
     * чтобы история не теряла точность. Только при переизбытке на долю начисляется
     * максимальный платёж сбора, а остаток уходит в месячную оплату («Возврат из сбора»).
     */
    suspend fun addCollectionContribution(
        spreadsheetId: String,
        collectionName: String,
        memberName: String,
        amount: Double,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        if (amount <= 0) return@withToken readGroup(sheets, token, spreadsheetId)
        val group = readGroup(sheets, token, spreadsheetId)
        val (newCollections, newPayments) =
            applyContribution(group.collections, group.payments, collectionName, memberName, amount)
        val updated = group.copy(collections = newCollections, payments = newPayments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Пакетное внесение: несколько записей «участник, сумма[, сбор]» применяются
     * последовательно к одному состоянию и записываются в таблицу ОДИН раз.
     * Записи с комментарием «Сбор: X» идут как взносы по сбору (та же логика, что
     * у addCollectionContribution), остальные — обычные месячные платежи.
     */
    suspend fun appendPaymentsBatch(
        spreadsheetId: String,
        inputs: List<PaymentInput>,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        if (inputs.isEmpty()) return@withToken readGroup(sheets, token, spreadsheetId)
        val group = readGroup(sheets, token, spreadsheetId)
        var collections = group.collections
        var payments = group.payments
        val today = SheetCodec.today()
        inputs.forEach { input ->
            if (input.amount <= 0) error("Сумма платежа должна быть больше нуля")
            if (input.comment.startsWith(SummaryCalculator.COLLECTION_TAG_PREFIX)) {
                val name = input.comment.removePrefix(SummaryCalculator.COLLECTION_TAG_PREFIX).trim()
                val (c, p) = applyContribution(collections, payments, name, input.member, input.amount)
                collections = c
                payments = p
            } else {
                payments = payments + PaymentData(input.member, today, round2(input.amount), input.comment)
            }
        }
        val updated = group.copy(collections = collections, payments = payments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(collections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(payments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Переклассифицирует часть свободного месячного остатка участника во взнос по сбору.
     * В сбор переносится не больше недобора до доли: излишек сверх доли в сбор НЕ
     * засчитывается и остаётся месячным взносом участника. Деньги уже внесены
     * (общий баланс не меняется), сумма не уходит в минус. Текущий месяц не затрагивается.
     */
    suspend fun reclassifyMonthlyToCollection(
        spreadsheetId: String,
        collectionName: String,
        memberName: String,
        amount: Double,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        if (amount <= 0) return@withToken readGroup(sheets, token, spreadsheetId)
        val group = readGroup(sheets, token, spreadsheetId)
        val collection = group.collections.firstOrNull { it.name == collectionName }
            ?: error("Сбор не найден")
        if (memberName !in collection.participants) error("Участник не в этом сборе")
        // В сбор переносим не больше недобора до доли: излишек не засчитывается в сам
        // сбор и остаётся месячным взносом участника.
        val deficit = collection.deficit(memberName)
        if (deficit <= 0.005) error("Доля участника по сбору уже внесена")
        val move = round2(minOf(amount, deficit))
        val summary = SummaryCalculator.compute(group)
        val ms = summary.rows.firstOrNull { it.member.name == memberName }
            ?: error("Участник не найден")
        if (move > ms.balance + 0.005) error("Недостаточно свободного остатка участника")
        val newContrib = round2(collection.contributed(memberName) + move)
        val updatedColl = collection.copy(
            contrib = collection.contrib + (memberName to newContrib),
        )
        val newCollections = group.collections.map { if (it.name == collectionName) updatedColl else it }
        val tag = "Сбор: $collectionName"
        // Переносим move из «будущих» месячных взносов участника: идём с конца списка,
        // урезаем самые поздние месячные платежи (без тегов сборов и возвратов), баланс не меняется.
        var remaining = move
        val monthlyPayments = group.payments
            .filter {
                it.member == memberName &&
                    !it.comment.startsWith("Сбор:") &&
                    !it.comment.startsWith("Возврат из сбора") &&
                    !it.comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX)
            }
            .sortedBy { it.date }
        val adjustedTail = mutableListOf<PaymentData>()
        for (p in monthlyPayments.asReversed()) {
            if (remaining <= 0.005) break
            val take = round2(minOf(remaining, p.amount))
            remaining = round2(remaining - take)
            val left = round2(p.amount - take)
            if (left > 0.005) adjustedTail += p.copy(amount = left)
        }
        if (remaining > 0.005) error("Недостаточно внесённых месячных взносов участника")
        val keptMonthly = group.payments
            .filter {
                it.member != memberName ||
                    it.comment.startsWith("Сбор:") ||
                    it.comment.startsWith("Возврат из сбора") ||
                    it.comment.startsWith(SummaryCalculator.REFUND_TAG_PREFIX)
            }
        val newPayments = keptMonthly + adjustedTail + PaymentData(memberName, SheetCodec.today(), move, tag)
        val updated = group.copy(collections = newCollections, payments = newPayments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Редактирует сбор: участники и/или сумма. Изменение цели пересчитывает долю:
     * при уменьшении избыток уже внесённого автоматически уходит в месячную оплату
     * (платёж «Возврат из сбора»), при увеличении — виден недобор.
     */
    suspend fun editCollection(
        spreadsheetId: String,
        name: String,
        newName: String,
        target: Double,
        participants: List<String>,
        sharedWithBudget: Boolean = true,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val normalizedName = newName.trim()
        if (normalizedName.isEmpty()) error("Укажите название сбора")
        if (target <= 0) error("Укажите сумму сбора")
        if (participants.isEmpty()) error("Выберите участников")
        val group = readGroup(sheets, token, spreadsheetId)
        val collection = group.collections.firstOrNull { it.name == name }
            ?: error("Сбор не найден")
        if (name != normalizedName && group.collections.any { it.name == normalizedName }) {
            error("Сбор с таким названием уже есть")
        }
        val updatedColl = collection.copy(
            name = normalizedName,
            target = round2(target),
            participants = participants.distinct(),
            contrib = collection.contrib.filterKeys { it in participants },
            sharedWithBudget = sharedWithBudget,
        )
        val newCollections = group.collections.map { if (it.name == name) updatedColl else it }
        val newPayments = replaceCollectionPayments(group.payments, name, updatedColl)
        val updated = group.copy(collections = newCollections, payments = newPayments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Удаляет платежи сбора (старое и новое имя) и восстанавливает их из вкладов.
     * Вклад больше доли → разница уходит в месячную оплату платежом «Возврат из сбора».
     */
    private fun replaceCollectionPayments(
        payments: List<PaymentData>,
        oldName: String,
        collection: CollectionData,
    ): List<PaymentData> {
        val comments = setOf(
            "Сбор: $oldName", "Возврат из сбора: $oldName",
            "Сбор: ${collection.name}", "Возврат из сбора: ${collection.name}",
        )
        val participants = collection.participants.toSet()
        // Возвраты исключённых участников не пересобираем: излишек уже зачислен в их
        // месячный баланс, и удаление строки уменьшило бы баланс. Оставляем как есть.
        val orphanRefunds = payments.filter {
            it.comment == "Возврат из сбора: $oldName" && it.member !in participants
        }
        val rest = payments.filterNot { it.comment in comments }
        val share = collection.sharePerPerson
        val tag = "Сбор: ${collection.name}"
        val leftover = "Возврат из сбора: ${collection.name}"
        val rebuilt = mutableListOf<PaymentData>()
        collection.participants.forEach { p ->
            val amt = round2(collection.contributed(p))
            if (amt < 0.01) return@forEach
            if (amt >= share - 0.005) {
                rebuilt.add(PaymentData(p, collection.date, share, tag))
                val excess = round2(amt - share)
                if (excess >= 0.01) rebuilt.add(PaymentData(p, collection.date, excess, leftover))
            } else {
                rebuilt.add(PaymentData(p, collection.date, amt, tag))
            }
        }
        return rest + orphanRefunds + rebuilt
    }

    /**
     * Исключает участника из группы. Вычисляет баланс (внесено − доля расходов)
     * и записывает его как возврат. Возвращает обновлённые данные.
     */
    suspend fun removeMember(
        spreadsheetId: String,
        memberName: String,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        val summary = SummaryCalculator.compute(group)
        val row = summary.rows.firstOrNull { it.member.name == memberName && it.member.active }
            ?: error("Активный участник не найден")
        val balance = row.balance

        val updatedMembers = group.members.map { m ->
            if (m.name == memberName && m.active) {
                m.copy(active = false, removedAt = SheetCodec.today(), refund = balance)
            } else m
        }
        val updated = group.copy(members = updatedMembers)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.MEMBERS}!A1:H200", SheetCodec.membersRows(updatedMembers)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    /**
     * Обновляет данные участника (имя и/или день рождения).
     * При переименовании переписывает ссылки на имя в Платежах, Расходах и сборах
     * (участники и вклады), иначе сборы остались бы за старым именем.
     * Возвращает обновлённые данные.
     */
    suspend fun updateMember(
        spreadsheetId: String,
        oldName: String,
        updated: MemberData,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (group.members.none { it.name == oldName }) error("Участник не найден")
        if (updated.name.isBlank()) error("Имя не может быть пустым")
        if (
            updated.name != oldName &&
            group.members.any { it.name.equals(updated.name, ignoreCase = true) }
        ) {
            error("Участник с именем «${updated.name}» уже есть")
        }

        val renamed = updated.name != oldName
        val newMembers = group.members.map { if (it.name == oldName) updated else it }
        val newPayments = if (renamed) {
            group.payments.map { if (it.member == oldName) it.copy(member = updated.name) else it }
        } else group.payments
        val newExpenses = if (renamed) {
            group.expenses.map { e ->
                if (oldName in e.participants) {
                    e.copy(participants = e.participants.map { if (it == oldName) updated.name else it })
                } else e
            }
        } else group.expenses
        val newCollections = if (renamed) {
            group.collections.map { c ->
                val participants = c.participants.map { if (it == oldName) updated.name else it }
                val contrib = c.contrib.entries.associate { (k, v) ->
                    (if (k == oldName) updated.name else k) to v
                }
                c.copy(participants = participants, contrib = contrib)
            }
        } else group.collections

        val updatedGroup = group.copy(
            members = newMembers,
            payments = newPayments,
            expenses = newExpenses,
            collections = newCollections,
        )
        val newSummary = SummaryCalculator.compute(updatedGroup)

        val data = mutableListOf(
            ValueRange("${Tabs.MEMBERS}!A1:H200", SheetCodec.membersRows(newMembers)),
            ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updatedGroup, newSummary)),
        )
        if (renamed) {
            data.add(ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)))
            data.add(ValueRange("${Tabs.EXPENSES}!A1:E1000", SheetCodec.expensesRows(newExpenses)))
            data.add(ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)))
        }
        writeData(sheets, authHeader, spreadsheetId, updatedGroup, data)
        updatedGroup
    }

    /**
     * Перезаписывает план месяцев (обязательность и суммы) в настройках
     * и пересчитывает сводку.
     */
    suspend fun updateMonthPlan(spreadsheetId: String, months: List<MonthPlan>): Result<GroupData> =
        withToken { sheets, _, token ->
            val authHeader = "Bearer $token"
            if (months.isEmpty()) throw IllegalArgumentException("Список месяцев пуст")
            val group = readGroup(sheets, token, spreadsheetId)
            val sorted = months.sortedBy { it.month }
            val updated = group.copy(months = sorted)
            val summary = SummaryCalculator.compute(updated)
            writeData(
                sheets,
                authHeader,
                spreadsheetId,
                updated,
                listOf(
                    ValueRange(
                        "${Tabs.SETTINGS}!A1:C40",
                        SheetCodec.settingsRows(
                            group.title,
                            group.baseAmount,
                            group.currency,
                            group.createdAt,
                            sorted,
                        ),
                    ),
                    ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
                ),
            )
            updated
        }

    /**
     * Добавляет нового участника. Дата вступления фиксируется текущим днём:
     * взносы начисляются с месяца вступления, предыдущие месяцы, расходы и сборы
     * на новичка не распределяются.
     */
    suspend fun addMember(spreadsheetId: String, name: String, birthday: String = ""): Result<GroupData> =
        withToken { sheets, _, token ->
            val authHeader = "Bearer $token"
            val normalized = name.trim()
            if (normalized.isEmpty()) throw IllegalArgumentException("Укажите имя участника")
            val group = readGroup(sheets, token, spreadsheetId)
            if (group.members.any { it.name.equals(normalized, ignoreCase = true) }) {
                throw IllegalArgumentException("Участник с именем «$normalized» уже есть")
            }
            val member = MemberData(
                name = normalized,
                email = "",
                joinDate = SheetCodec.today(),
                active = true,
                removedAt = null,
                refund = null,
                birthday = birthday,
            )
            val updated = group.copy(members = group.members + member)
            val summary = SummaryCalculator.compute(updated)
            writeData(
                sheets,
                authHeader,
                spreadsheetId,
                updated,
                listOf(
                    ValueRange("${Tabs.MEMBERS}!A1:H200", SheetCodec.membersRows(updated.members)),
                    ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
                ),
            )
            updated
        }

    /**
     * Добавляет расход. Круг участников фиксируется на момент записи
     * (срез активных участников). Возвращает обновлённые данные.
     */
    suspend fun appendExpense(
        spreadsheetId: String,
        description: String,
        amount: Double,
        participants: List<String>,
        date: String = SheetCodec.today(),
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        // Пустой круг («все») фиксируем списком активных участников на момент записи
        val circle = participants.takeIf { it.isNotEmpty() }?.distinct()
            ?: group.activeMembers.map { it.name }
        val updated = group.copy(
            expenses = group.expenses + ExpenseData(date, description, round2(amount), circle),
        )
        val summary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.EXPENSES}!A1:E1000", SheetCodec.expensesRows(updated.expenses)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
            ),
        )
        updated
    }

    /**
     * Редактирует платёж по индексу в списке (дата и номер строки таблицы не меняются).
     * Пересчитывает сводку и перезаписывает вкладку целиком. Возвращает обновлённые данные.
     */
    suspend fun editPayment(
        spreadsheetId: String,
        index: Int,
        member: String,
        amount: Double,
        comment: String,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (index !in group.payments.indices) error("Платёж не найден")
        val newComment = comment.trim()
        // Правка любой строки сбора сломала бы соответствие «вклад ↔ платежи»:
        // «Сбор: X» задаёт взнос, «Возврат из сбора: X» — его излишек.
        if (group.payments[index].comment.isCollectionPayment() || newComment.isCollectionPayment()) {
            error("Записи сборов редактируются во вкладке «Сборы»")
        }
        val updated = group.copy(
            payments = group.payments.toMutableList().also { list ->
                list[index] = group.payments[index].copy(
                    member = member.trim(),
                    amount = round2(amount),
                    comment = newComment,
                )
            },
        )
        val summary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(updated.payments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
            ),
        )
        updated
    }

    /**
     * Редактирует расход по индексу в списке (дата не меняется).
     * Пустой круг участников фиксируется срезом активных участников, как в appendExpense.
     * Возвращает обновлённые данные.
     */
    suspend fun editExpense(
        spreadsheetId: String,
        index: Int,
        description: String,
        amount: Double,
        participants: List<String>,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (index !in group.expenses.indices) error("Расход не найден")
        val circle = participants.takeIf { it.isNotEmpty() }?.distinct()
            ?: group.activeMembers.map { it.name }
        val updated = group.copy(
            expenses = group.expenses.toMutableList().also { list ->
                list[index] = group.expenses[index].copy(
                    description = description.trim(),
                    amount = round2(amount),
                    participants = circle,
                )
            },
        )
        val summary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.EXPENSES}!A1:E1000", SheetCodec.expensesRows(updated.expenses)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
            ),
        )
        updated
    }

    /**
     * Удаляет платёж по индексу в списке. Взнос по сбору пересчитывается из оставшихся
     * платёжных строк участника («Сбор: X» + «Возврат из сбора: X»), а не «взнос минус удалённая
     * сумма»: так удаление дубля строки не затирает реально внесённую сумму, а удаление
     * лишнего платежа не «воскрешает» канонические строки.
     * Пересчитывает сводку и перезаписывает вкладки целиком.
     */
    suspend fun deletePayment(
        spreadsheetId: String,
        index: Int,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (index !in group.payments.indices) error("Платёж не найден")
        val payment = group.payments[index]
        val remaining = group.payments.toMutableList().also { it.removeAt(index) }

        val targetCollection = when {
            payment.comment.startsWith("Сбор: ") ->
                payment.comment.removePrefix("Сбор: ").trim()
            payment.comment.startsWith("Возврат из сбора: ") ->
                payment.comment.removePrefix("Возврат из сбора: ").trim()
            else -> null
        }

        var collections = group.collections
        val updatedCollection = targetCollection?.let { name ->
            collections.firstOrNull { it.name == name }
        }
        if (updatedCollection != null) {
            val member = payment.member
            val tag = "Сбор: ${targetCollection}"
            val leftover = "Возврат из сбора: ${targetCollection}"
            val newContrib = round2(
                remaining.filter {
                    it.member == member && (it.comment == tag || it.comment == leftover)
                }.sumOf { it.amount },
            )
            val adjusted = if (newContrib <= 0.005) {
                updatedCollection.contrib - member
            } else {
                updatedCollection.contrib + (member to newContrib)
            }
            collections = collections.map {
                if (it.name == targetCollection) it.copy(contrib = adjusted) else it
            }
        }

        val updated = group.copy(collections = collections, payments = remaining)
        val summary = SummaryCalculator.compute(updated)
        val changes = mutableListOf(
            ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(updated.payments)),
            ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
        )
        if (updatedCollection != null) {
            changes.add(ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(updated.collections)))
        }
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            changes,
        )
        updated
    }

    /**
     * Удаляет расход по индексу в списке. Пересчитывает сводку и
     * перезаписывает вкладку целиком. Возвращает обновлённые данные.
     */
    suspend fun deleteExpense(
        spreadsheetId: String,
        index: Int,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (index !in group.expenses.indices) error("Расход не найден")
        val updated = group.copy(
            expenses = group.expenses.toMutableList().also { it.removeAt(index) },
        )
        val summary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.EXPENSES}!A1:E1000", SheetCodec.expensesRows(updated.expenses)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, summary)),
            ),
        )
        updated
    }

    /**
     * Удаляет отдельный сбор: убирает его и все связанные платежи
     * («Сбор: X» и «Возврат из сбора: X»), пересчитывает сводку.
     */
    suspend fun deleteCollection(
        spreadsheetId: String,
        name: String,
    ): Result<GroupData> = withToken { sheets, _, token ->
        val authHeader = "Bearer $token"
        val group = readGroup(sheets, token, spreadsheetId)
        if (group.collections.none { it.name == name }) error("Сбор не найден")
        // Удаляем ВСЕ записи с этим именем: в повреждённых листах может оказаться
        // два дубля с одинаковым именем (наследие сбоев записи), и один из них
        // «воскресал» после удаления.
        val newCollections = group.collections.filterNot { it.name == name }
        // Взносы «Сбор: X» удаляем, а «Возврат из сбора: X» (излишек) оставляем:
        // он уже зачислен в месячный баланс, и его удаление обнулило бы этот баланс.
        val newPayments = group.payments.filterNot { it.comment == "Сбор: $name" }
        val updated = group.copy(collections = newCollections, payments = newPayments)
        val newSummary = SummaryCalculator.compute(updated)
        writeData(
            sheets,
            authHeader,
            spreadsheetId,
            updated,
            listOf(
                ValueRange("${Tabs.COLLECTIONS}!A1:F1000", SheetCodec.collectionsRows(newCollections)),
                ValueRange("${Tabs.PAYMENTS}!A1:D1000", SheetCodec.paymentsRows(newPayments)),
                ValueRange("${Tabs.SUMMARY}!A1:R100", SummaryCalculator.summaryRows(updated, newSummary)),
            ),
        )
        updated
    }

    // ---------- внутренние утилиты ----------

    /** id листов таблицы: лист -> sheetId (числовой). */
    private suspend fun sheetIds(
        sheets: SheetsApiService,
        authHeader: String,
        spreadsheetId: String,
    ): Map<String, Int> {
        sheetIdCache[spreadsheetId]?.let { return it }
        val meta = sheets.getSpreadsheetMeta(
            authHeader,
            spreadsheetId,
            "sheets(properties(sheetId,title,index,gridProperties(hidden)))",
        )
        val map = meta.sheets.orEmpty().mapNotNull { s ->
            val p = s.properties ?: return@mapNotNull null
            val id = p.sheetId ?: return@mapNotNull null
            (p.title ?: "") to id
        }.toMap()
        sheetIdCache[spreadsheetId] = map
        return map
    }

    /** Переприменяет оформление всех листов по текущим данным. */
    private suspend fun applyStyle(
        sheets: SheetsApiService,
        authHeader: String,
        spreadsheetId: String,
        group: GroupData,
    ) {
        val ids = sheetIds(sheets, authHeader, spreadsheetId)
        val summary = SummaryCalculator.compute(group)
        val requests = SheetBeautifier.styleRequests(ids, group, summary)
        if (requests.isNotEmpty()) {
            val resp = sheets.batchUpdateSpreadsheet(
                authHeader,
                spreadsheetId,
                SpreadsheetBatchUpdateRequest(requests = requests),
            )
            if (!resp.isSuccessful) {
                Log.w("GroupFund", "applyStyle http ${resp.code()}: ${resp.errorBody()?.string()}")
            }
        }
    }

    /**
     * Записывает значения и следом обновляет оформление листов.
     * Перед записью чистит «осиротевшие» платёжные строки сборов («Сбор: X», «Возврат из сбора: X»,
     * где сбора X больше нет) и дедуплицирует сборы по имени — лечит дубли/воскрешения от сбоев записи.
     * Пишет данные одним batchUpdate, после чего отдельным (некритичным) вызовом зачищает «хвосты»:
     * так сбой записи не оставляет лист пустым и не ломает повтор при 429.
     */
    private suspend fun writeData(
        sheets: SheetsApiService,
        authHeader: String,
        spreadsheetId: String,
        group: GroupData,
        data0: List<ValueRange>,
    ) {
        val paymentsKey = "${Tabs.PAYMENTS}!A1:D1000"
        val collectionsKey = "${Tabs.COLLECTIONS}!A1:F1000"
        val data = data0.map { range ->
            when (range.range) {
                paymentsKey -> range.copy(
                    values = SheetCodec.paymentsRows(sanitizeCollectionPayments(group.payments, group.collections)),
                )
                collectionsKey -> range.copy(
                    values = SheetCodec.collectionsRows(group.collections.distinctBy { it.name }),
                )
                else -> range
            }
        }
        // Сначала пишем новые значения ровно по размеру данных, и только ПОСЛЕ успешной записи
        // чистим «хвосты» за их пределами. Раньше очистка шла первой: если batchUpdate падал,
        // лист оставался пустым, а retry при 429 перечитывал уже пустые данные — полная потеря.
        // Теперь при сбое записи лист сохраняет прежнее состояние, и retry безопасен.
        val bounded = mutableListOf<ValueRange>()
        val tails = mutableListOf<String>()
        data.forEach { range ->
            val a1 = parseA1Range(range.range)
            val rows = range.values.size
            if (a1 == null) {
                bounded += range
                return@forEach
            }
            if (rows == 0) {
                tails += range.range
                return@forEach
            }
            val lastRow = a1.startRow + rows - 1
            bounded += range.copy(range = "${a1.sheet}!${a1.startCol}${a1.startRow}:${a1.endCol}$lastRow")
            if (lastRow < a1.endRow) {
                tails += "${a1.sheet}!${a1.startCol}${lastRow + 1}:${a1.endCol}${a1.endRow}"
            }
        }
        if (bounded.isNotEmpty()) {
            sheets.batchUpdateValues(authHeader, spreadsheetId, BatchUpdateValuesRequest(data = bounded))
        }
        // Хвосты чистим некритично: сбой очистки не должен откатывать уже сделанную запись,
        // а повторная очистка произойдёт при следующей успешной записи.
        if (tails.isNotEmpty()) {
            runCatching {
                sheets.batchClearValues(authHeader, spreadsheetId, BatchClearValuesRequest(ranges = tails))
            }.onFailure {
                Log.w("GroupFund", "tail clear failed: ${it.message}", it)
            }
        }
        runCatching {
            applyStyle(sheets, authHeader, spreadsheetId, group)
        }.onFailure {
            Log.w("GroupFund", "applyStyle failed: ${it.message}", it)
        }
        // Диагностика после записи: если «Платежи» разошлись со «Сборами», это видно в логе,
        // а не только при ручном разборе таблицы.
        runCatching {
            val warnings = CollectionConsistency.mismatches(group)
            if (warnings.isNotEmpty()) {
                Log.w("GroupFund", "Collection consistency:\n" + warnings.joinToString("\n"))
            }
        }
    }

    /** Разобранный A1-диапазон вида «Лист!A1:D1000» (startRow обычно 1). */
    private data class A1Range(
        val sheet: String,
        val startCol: String,
        val startRow: Int,
        val endCol: String,
        val endRow: Int,
    )

    private fun parseA1Range(range: String): A1Range? {
        val bang = range.lastIndexOf('!')
        if (bang <= 0) return null
        val cells = range.substring(bang + 1)
        val colon = cells.indexOf(':')
        if (colon <= 0) return null
        val start = A1_CELL.matchEntire(cells.substring(0, colon)) ?: return null
        val end = A1_CELL.matchEntire(cells.substring(colon + 1)) ?: return null
        return A1Range(
            sheet = range.substring(0, bang),
            startCol = start.groupValues[1].uppercase(),
            startRow = start.groupValues[2].toInt(),
            endCol = end.groupValues[1].uppercase(),
            endRow = end.groupValues[2].toInt(),
        )
    }

    /** Выкидывает платежи, помеченные сбором, которого больше нет в списке. */
    private fun sanitizeCollectionPayments(
        payments: List<PaymentData>,
        collections: List<CollectionData>,
    ): List<PaymentData> {
        val validNames = collections.map { it.name }.toSet()
        return payments.filter { p ->
            when {
                p.comment.startsWith("Сбор: ") ->
                    p.comment.removePrefix("Сбор: ").trim() in validNames
                p.comment.startsWith("Возврат из сбора: ") ->
                    p.comment.removePrefix("Возврат из сбора: ").trim() in validNames
                else -> true
            }
        }
    }

    private fun parseDriveTime(raw: String?): Long = runCatching {
        OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

    private suspend fun readGroup(
        sheets: SheetsApiService,
        token: String,
        spreadsheetId: String,
    ): GroupData {
        val authHeader = "Bearer $token"
        val ranges = listOf(
            "settings" to "${Tabs.SETTINGS}!A1:C40",
            "members" to "${Tabs.MEMBERS}!A1:H200",
            "payments" to "${Tabs.PAYMENTS}!A1:D1000",
            "expenses" to "${Tabs.EXPENSES}!A1:E1000",
            "collections" to "${Tabs.COLLECTIONS}!A1:F1000",
        )
        // Все листы одним batchGet — 1 запрос вместо 5 (экономит квоту чтения 60/мин).
        // Если листа «Сборы» нет (старые таблицы) — batchGet падает, читаем по отдельности.
        val raw: Map<String, List<List<Any?>>> = runCatching {
            val resp = sheets.batchGetValues(
                authHeader,
                spreadsheetId,
                BatchGetValuesRequest(ranges = ranges.map { it.second }),
            )
            val values = resp.valueRanges.orEmpty()
            if (values.size != ranges.size) {
                error("Ожидалось ${ranges.size} диапазонов, получено ${values.size}")
            }
            ranges.indices.associate { i -> ranges[i].first to (values[i].values.orEmpty()) }
        }.getOrElse {
            mapOf(
                "settings" to (sheets.getValues(authHeader, spreadsheetId, "${Tabs.SETTINGS}!A1:C40").values ?: emptyList()),
                "members" to (sheets.getValues(authHeader, spreadsheetId, "${Tabs.MEMBERS}!A1:H200").values ?: emptyList()),
                "payments" to (sheets.getValues(authHeader, spreadsheetId, "${Tabs.PAYMENTS}!A1:D1000").values ?: emptyList()),
                "expenses" to (sheets.getValues(authHeader, spreadsheetId, "${Tabs.EXPENSES}!A1:E1000").values ?: emptyList()),
                "collections" to runCatching {
                    sheets.getValues(authHeader, spreadsheetId, "${Tabs.COLLECTIONS}!A1:F1000").values ?: emptyList()
                }.getOrDefault(emptyList()),
            )
        }

        val (hdr, months) = SheetCodec.parseSettings(raw["settings"].orEmpty())
        return GroupData(
            spreadsheetId = spreadsheetId,
            title = hdr.title.ifEmpty { "Группа" },
            baseAmount = hdr.baseAmount,
            currency = hdr.currency,
            createdAt = hdr.createdAt,
            months = months,
            members = SheetCodec.parseMembers(raw["members"].orEmpty()),
            payments = SheetCodec.parsePayments(raw["payments"].orEmpty()),
            expenses = SheetCodec.parseExpenses(raw["expenses"].orEmpty()),
            collections = SheetCodec.parseCollections(raw["collections"].orEmpty()),
        )
    }
}