package com.groupfund.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.auth.OAuthConsentRequiredException
import com.groupfund.app.data.registry.GroupEntry
import com.groupfund.app.data.registry.GroupRegistry
import com.groupfund.app.data.sheets.NotFoundException
import com.groupfund.app.data.sheets.SheetsRepository
import com.groupfund.app.notifications.BirthdayCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isSignedIn: Boolean = false,
    val email: String? = null,
    val error: String? = null,
    val groups: List<GroupEntry> = emptyList(),
    val accounts: List<String> = emptyList(),
    val accountsLoading: Boolean = false,
    val discovering: Boolean = false,
    val discovered: List<GroupEntry> = emptyList(),
    /** id записи группы, только что добавленной по QR-приглашению (для навигации). */
    val inviteJoined: String? = null,
    /** Ожидается согласие на доступ к Google (экран OAuth). */
    val consentIntent: Intent? = null,
    /** Идёт генерация передачи группы. */
    val transferBusy: Boolean = false,
    /** Сгенерированная ссылка передачи — показывается в диалоге и копируется. */
    val transferLink: String? = null,
    /** id групп, для которых передача начата, но не завершена (бейдж «Передача»). */
    val pendingTransferIds: Set<String> = emptySet(),
    /** Идёт приём передачи (копирование таблицы на диск). */
    val acceptBusy: Boolean = false,
    /** Пришли по ссылке передачи — открыть диалог «Принять» с подставленной ссылкой. */
    val pendingTransferUri: String? = null,
    /** Результат приёма передачи (диалог). */
    val acceptResult: AcceptTransferResult? = null,
    /** id группы, для которой только что завершена передача (кнопка «Убрать прежнюю версию»). */
    val transferJustCompleted: String? = null,
)

/** Итог приёма передачи: копия готова, участников можно переключить самим или через прежнего ведущего. */
data class AcceptTransferResult(
    /** true — новая ссылка записана в прежнюю таблицу, участники переключатся сами. */
    val autoFinalized: Boolean,
    val newSpreadsheetId: String,
    /** Если не null — передача уже завершена другим получателем (id его копии). */
    val alreadyTransferredTo: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)
    private val registry = GroupRegistry(app)
    private val repository = SheetsRepository(auth)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** spreadsheetId, ожидающий подключения (deep-link `groupfund://invite/<id>`). */
    private var pendingInviteId: String? = null

    /** Не выполняем проверку доступности групп дважды параллельно. */
    private var availabilityCheckRunning = false

    init {
        refreshSignedIn()
        viewModelScope.launch {
            registry.groups.collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
        refreshGroupsAvailability()
    }

    fun availableAccounts(): List<String> =
        auth.deviceGoogleAccounts().map { it.name }

    fun selectAccount(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(error = "Аккаунт не выбран — вход не выполнен") }
            return
        }
        auth.rememberSignIn(email)
        refreshSignedIn()
        refreshGroupsAvailability()
        // Проверяем ДР сразу после входа, не дожидаясь перезапуска приложения.
        viewModelScope.launch {
            runCatching { BirthdayCheck.run(getApplication()) }
        }
        // Если до входа пришли по QR-приглашению — подключаемся.
        processPendingInvite()
    }

    /** Пришёл deep-link `groupfund://invite/<spreadsheetId>` — запоминаем и пробуем подключиться. */
    fun setPendingInvite(spreadsheetId: String?) {
        if (spreadsheetId.isNullOrBlank()) return
        pendingInviteId = spreadsheetId
        processPendingInvite()
    }

    /** Приложение открыло добавленную по QR группу; сбрасываем одноразовое событие. */
    fun consumeInviteJoined() {
        _uiState.update { it.copy(inviteJoined = null) }
    }

    /** Удаляет группу из списка устройства, не трогая таблицу на Диске. */
    fun leaveGroup(entryId: String) {
        viewModelScope.launch { registry.removeGroup(entryId) }
    }

    /**
     * Переименовывает группу. Создатель переименовывает сам файл на Drive
     * (новое имя увидят все участники); при неудаче — локальное переименование.
     * Участник/наблюдатель — только локально («как отображается у пользователя»).
     */
    fun renameGroup(entryId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            val entry = runCatching { registry.groupById(entryId) }.getOrNull() ?: return@launch
            if (entry.role != "creator") {
                registry.renameGroup(entryId, newTitle)
                return@launch
            }
            repository.renameSpreadsheet(entry.spreadsheetId, newTitle).fold(
                onSuccess = { registry.renameGroup(entryId, newTitle, sheetRenamed = true) },
                onFailure = {
                    registry.renameGroup(entryId, newTitle)
                    _uiState.update { st ->
                        st.copy(error = "Таблица не переименована: ${it.message ?: "ошибка"}")
                    }
                },
            )
        }
    }

    /**
     * Проверяет доступность всех групп текущему аккаунту и помечает недоступные
     * (таблица удалена или создатель ограничил доступ). Запускается при старте
     * приложения и после смены аккаунта.
     *
     * Заодно читает маркер передачи: если прежняя таблица объявила копию —
     * локальная запись переключается на неё (участнику не нужно переподключаться);
     * если передача начата, но не завершена — id группы попадает в pendingTransferIds
     * (бейдж «Передача» и пункт «Завершить передачу» у создателя).
     */
    fun refreshGroupsAvailability() {
        if (auth.storedEmail() == null || availabilityCheckRunning) return
        availabilityCheckRunning = true
        viewModelScope.launch {
            try {
                val current = registry.groups.first()
                val pending = mutableSetOf<String>()
                current.forEach { entry ->
                    val marker = repository.readTransferMarker(entry.spreadsheetId).getOrNull()
                    val target = marker?.newSpreadsheetId
                    if (!target.isNullOrEmpty() && target != entry.spreadsheetId) {
                        // Прежняя таблица передана: переключаемся на копию и роль, если были создателем.
                        val meta = repository.spreadsheetMeta(target).getOrNull()
                        registry.replaceSpreadsheet(entry.spreadsheetId) { e ->
                            e.copy(
                                spreadsheetId = target,
                                title = meta?.first ?: e.title,
                                spreadsheetUrl = meta?.second ?: e.spreadsheetUrl,
                                role = if (e.role == "creator") "observer" else e.role,
                                localTitle = null,
                                unavailable = false,
                            )
                        }
                        return@forEach
                    }
                    if (marker?.pendingCode != null && entry.role == "creator") {
                        pending += entry.id
                    }
                    val accessible = repository.checkAccessible(entry.spreadsheetId).getOrNull()
                    if (accessible != null) registry.setUnavailable(entry.id, !accessible)
                }
                _uiState.update { it.copy(pendingTransferIds = pending) }
            } finally {
                availabilityCheckRunning = false
            }
        }
    }

    fun consumeConsentIntent() {
        _uiState.update { it.copy(consentIntent = null) }
    }

    /** Повтор попытки подключения после экрана согласия. */
    fun retryPendingInvite() {
        processPendingInvite()
    }

    /** Пользователь не выдал согласие на экране Google — повторять автоподключение нельзя. */
    fun onConsentDenied() {
        _uiState.update { it.copy(error = "Доступ к Google Таблицам не предоставлен") }
    }

    private fun processPendingInvite() {
        val id = pendingInviteId ?: return
        if (auth.storedEmail() == null) return // сначала нужен вход
        viewModelScope.launch {
            val existing = registry.findBySpreadsheetId(id)
            if (existing != null) {
                pendingInviteId = null
                _uiState.update { it.copy(inviteJoined = existing.id) }
                return@launch
            }
            repository.prepareInvite(id)
                .onSuccess { entry ->
                    registry.addGroup(entry)
                    pendingInviteId = null
                    _uiState.update { it.copy(inviteJoined = entry.id) }
                }
                .onFailure { e ->
                    if (e is OAuthConsentRequiredException) {
                        // Показываем системный экран согласия, затем пробуем снова.
                        _uiState.update { it.copy(consentIntent = e.intent) }
                    } else {
                        pendingInviteId = null
                        _uiState.update { it.copy(error = e.message ?: "Не удалось подключиться к группе") }
                    }
                }
        }
    }

    /** Загружает аккаунты устройства. Может показать системный промпт доступа. */
    fun refreshAvailableAccounts() {
        if (_uiState.value.accountsLoading) return
        _uiState.update { it.copy(accountsLoading = true) }
        viewModelScope.launch {
            val acc = auth.deviceGoogleAccountsSuspend().map { it.name }
            _uiState.update { it.copy(accounts = acc, accountsLoading = false) }
        }
    }

    fun signOut() {
        auth.signOut()
        refreshSignedIn()
    }

    fun deleteGroup(entry: GroupEntry) {
        viewModelScope.launch {
            if (entry.role == "creator") {
                repository.deleteFromDrive(entry.spreadsheetId)
            }
            registry.removeGroup(entry.id)
        }
    }

    // ---------- Передача группы другому ведущему ----------

    private val transferAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private fun randomTransferCode(length: Int = 8): String = buildString {
        repeat(length) { append(transferAlphabet.random()) }
    }

    /**
     * Начинает передачу (только создатель): пишет маркер готовности в дабл
     * и показывает ссылку-код передачи, которую нужно отправить новому ведущему.
     */
    fun startTransfer(entryId: String) {
        viewModelScope.launch {
            val entry = runCatching { registry.groupById(entryId) }.getOrNull() ?: return@launch
            if (entry.role != "creator") return@launch
            // Не даём инициировать вторую передачу по той же группе: в локальном списке
            // и/или в маркере таблицы уже может быть активная (или завершённая) передача.
            if (_uiState.value.pendingTransferIds.contains(entryId)) {
                _uiState.update {
                    it.copy(error = "По этой группе уже идёт передача — завершите её или отмените")
                }
                return@launch
            }
            val marker = repository.readTransferMarker(entry.spreadsheetId).getOrNull()
            if (marker?.pendingCode != null || marker?.newSpreadsheetId != null) {
                _uiState.update { it.copy(error = "По этой группе уже идёт или завершена передача") }
                return@launch
            }
            _uiState.update { it.copy(transferBusy = true) }
            // Новый ведущий сможет скопировать таблицу, только если у него есть чтение исходника.
            // Открываем таблицу «по ссылке» (как при приглашении) — иначе приём у постороннего
            // аккаунта падает с 404 «File not found».
            repository.shareForInvite(entry.spreadsheetId)
                .onFailure { e ->
                    _uiState.update { s ->
                        s.copy(
                            transferBusy = false,
                            error = "Не удалось открыть доступ к таблице для передачи: ${e.message ?: "ошибка"}",
                        )
                    }
                    return@launch
                }
            val code = randomTransferCode()
            repository.writeTransferPending(entry.spreadsheetId, code)
                .onSuccess {
                    _uiState.update { s ->
                        s.copy(
                            transferBusy = false,
                            transferLink = "https://cheshirebat88.github.io/transfer/${entry.spreadsheetId}?code=$code",
                            pendingTransferIds = s.pendingTransferIds + entryId,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { s ->
                        s.copy(
                            transferBusy = false,
                            error = "Не удалось начать передачу: ${e.message ?: "ошибка"}",
                        )
                    }
                }
        }
    }

    /** Отменяет начатую передачу (создатель): стирает код в прежней таблице, ссылка перестаёт действовать. */
    fun cancelTransfer(entryId: String) {
        viewModelScope.launch {
            val entry = runCatching { registry.groupById(entryId) }.getOrNull() ?: return@launch
            repository.clearTransferPending(entry.spreadsheetId)
                .onSuccess {
                    _uiState.update { s ->
                        s.copy(
                            pendingTransferIds = s.pendingTransferIds - entryId,
                            error = "Передача отменена — прежняя ссылка больше не действует",
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(error = "Не удалось отменить передачу: ${e.message ?: "ошибка"}")
                    }
                }
        }
    }

    /**
     * Финализирует передачу (создатель): в прежнюю таблицу записывается id копии,
     * локальная запись переключается на неё, становится видна кнопка «Убрать прежнюю версию».
     * @param newSpreadsheetId код завершения, показанный новым ведущим после копирования.
     */
    fun finishTransfer(entryId: String, newSpreadsheetId: String) {
        val newId = newSpreadsheetId.trim()
        if (newId.isEmpty()) return
        viewModelScope.launch {
            val entry = runCatching { registry.groupById(entryId) }.getOrNull() ?: return@launch
            if (entry.spreadsheetId == newId) return@launch
            val marker = repository.readTransferMarker(entry.spreadsheetId).getOrNull()
            if (marker?.newSpreadsheetId != null) {
                _uiState.update { it.copy(error = "Передача уже завершена — ссылка больше не действует") }
                return@launch
            }
            val ok = repository.writeTransferDone(entry.spreadsheetId, newId).isSuccess
            if (!ok) {
                _uiState.update {
                    it.copy(error = "Не удалось завершить передачу: к прежней таблице нужен доступ на запись")
                }
                return@launch
            }
            val meta = repository.spreadsheetMeta(newId).getOrNull()
            registry.replaceSpreadsheet(entry.spreadsheetId) { e ->
                e.copy(
                    spreadsheetId = newId,
                    title = meta?.first ?: e.title,
                    spreadsheetUrl = meta?.second ?: e.spreadsheetUrl,
                    role = "observer",
                    localTitle = null,
                    unavailable = false,
                    retiredSpreadsheetId = entry.spreadsheetId,
                )
            }
            _uiState.update { s ->
                s.copy(
                    pendingTransferIds = s.pendingTransferIds - entryId,
                    transferJustCompleted = entryId,
                )
            }
        }
    }

    /** «Убрать прежнюю версию»: удаляет старую таблицу с диска создателя после передачи. */
    fun deleteRetiredCopy(entryId: String) {
        viewModelScope.launch {
            val entry = runCatching { registry.groupById(entryId) }.getOrNull() ?: return@launch
            val retired = entry.retiredSpreadsheetId ?: return@launch
            repository.deleteFromDrive(retired)
                .onSuccess {
                    registry.replaceSpreadsheet(entry.spreadsheetId) { e -> e.copy(retiredSpreadsheetId = null) }
                    _uiState.update { it.copy(error = "Прежняя таблица удалена из Google Диска") }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Не удалось удалить прежнюю таблицу: ${e.message ?: "ошибка"}") }
                }
        }
    }

    /**
     * Приём передачи (новый ведущий): по ссылке-коду копирует таблицу на свой диск,
     * переносит права, проверяет полноту копии и пытается сам объявить её в прежней
     * таблице. Если записи к прежней таблице нет — показывает код завершения,
     * который нужно передать прежнему ведущему.
     */
    fun acceptTransfer(raw: String) {
        if (_uiState.value.acceptBusy) return
        val parsed = parseTransferLink(raw) ?: run {
            _uiState.update {
                it.copy(error = "Неверная ссылка передачи. Откройте ссылку из диалога «Передать группу»")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(acceptBusy = true) }
            // Проверяем маркер до копирования: передача может быть уже завершена или
            // отменена, а код — не совпадать (устаревшая ссылка).
            val marker = repository.readTransferMarker(parsed.spreadsheetId).getOrNull()
            if (marker?.newSpreadsheetId != null) {
                _uiState.update { s ->
                    s.copy(
                        acceptBusy = false,
                        error = "Эта группа уже передана — попросите новую ссылку у ведущего",
                    )
                }
                return@launch
            }
            if (marker?.pendingCode == null) {
                _uiState.update { s ->
                    s.copy(
                        acceptBusy = false,
                        error = "Передача не найдена: возможно, она отменена или завершена",
                    )
                }
                return@launch
            }
            if (marker.pendingCode != parsed.code) {
                _uiState.update { s ->
                    s.copy(
                        acceptBusy = false,
                        error = "Код передачи не совпадает. Попросите свежую ссылку у ведущего",
                    )
                }
                return@launch
            }
            repository.copySpreadsheetToMe(parsed.spreadsheetId)
                .onSuccess { entry ->
                    val existing = registry.findBySpreadsheetId(parsed.spreadsheetId)
                    if (existing != null) {
                        registry.replaceSpreadsheet(parsed.spreadsheetId) { e ->
                            e.copy(
                                spreadsheetId = entry.spreadsheetId,
                                title = entry.title,
                                spreadsheetUrl = entry.spreadsheetUrl,
                                role = "creator",
                                localTitle = null,
                                unavailable = false,
                            )
                        }
                    } else {
                        registry.addGroup(entry)
                    }
                    // Перечитываем маркер: за время копирования передача могла завершиться
                    // другим получателем. Первый записавший id копии — победитель.
                    val fresh = repository.readTransferMarker(parsed.spreadsheetId).getOrNull()
                    val takenId = fresh?.newSpreadsheetId
                    // Первый записавший id копии — победитель. Если приёмщик не может писать
                    // в прежнюю таблицу (например, он лишь наблюдатель) — выдаём код завершения.
                    val auto = if (takenId == null || takenId == entry.spreadsheetId) {
                        repository.writeTransferDone(parsed.spreadsheetId, entry.spreadsheetId).isSuccess
                    } else {
                        false
                    }
                    _uiState.update { s ->
                        s.copy(
                            acceptBusy = false,
                            acceptResult = AcceptTransferResult(
                                autoFinalized = auto,
                                newSpreadsheetId = entry.spreadsheetId,
                                alreadyTransferredTo = if (auto) null else takenId,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { s ->
                        s.copy(
                            acceptBusy = false,
                            error = if (e is NotFoundException) {
                                "Исходная таблица недоступна для вашего аккаунта. Попросите ведущего " +
                                    "отменить текущую передачу («Отменить передачу» в меню группы) и " +
                                    "начать её заново — при новой передаче таблица открывается по ссылке.\n\n" +
                                    e.message.orEmpty().lineSequence().take(6).joinToString("\n").take(300)
                            } else {
                                e.message ?: "Не удалось принять передачу"
                            },
                        )
                    }
                }
        }
    }

    fun consumeTransferLink() {
        _uiState.update { it.copy(transferLink = null) }
    }

    /** Пришли по ссылке передачи — запоминаем её для диалога «Принять» (в https-виде). */
    fun setPendingTransferUri(uri: String?) {
        if (uri.isNullOrBlank()) return
        _uiState.update { it.copy(pendingTransferUri = uri) }
    }

    /** Диалог «Принять» открыт со ссылкой — сбрасываем одноразовое событие. */
    fun consumePendingTransferUri() {
        _uiState.update { it.copy(pendingTransferUri = null) }
    }

    fun consumeAcceptResult() {
        _uiState.update { it.copy(acceptResult = null) }
    }

    fun consumeTransferJustCompleted() {
        _uiState.update { it.copy(transferJustCompleted = null) }
    }

    private data class TransferLinkParts(val spreadsheetId: String, val code: String)

    private fun parseTransferLink(raw: String): TransferLinkParts? {
        var s = raw.trim()
        listOf(
            "groupfund://transfer/",
            "https://cheshirebat88.github.io/transfer/",
            "http://cheshirebat88.github.io/transfer/",
        ).forEach { if (s.startsWith(it)) s = s.removePrefix(it) }
        val sid: String
        val code: String
        if (s.contains("?code=")) {
            sid = s.substringBefore("?code=").trim()
            code = s.substringAfter("?code=").trim()
        } else if (s.contains("|")) {
            sid = s.substringBefore("|").trim()
            code = s.substringAfter("|").trim()
        } else {
            sid = s.trim()
            code = ""
        }
        val validId = sid.matches(Regex("[A-Za-z0-9_-]{20,}"))
        val validCode = code.matches(Regex("[A-Za-z0-9]{8}"))
        return if (validId && validCode) TransferLinkParts(sid, code) else null
    }

    /** Ищет группы, созданные приложением, на Google Диске. */
    fun discoverFromDrive() {
        if (_uiState.value.discovering) return
        _uiState.update { it.copy(discovering = true, discovered = emptyList()) }
        viewModelScope.launch {
            repository.discoverGroups()
                .onSuccess { _uiState.update { s -> s.copy(discovering = false, discovered = it) } }
                .onFailure {
                    _uiState.update { s -> s.copy(discovering = false, error = it.message) }
                }
        }
    }

    /** Добавляет выбранные найденные группы в локальный список. */
    fun importDiscoveredGroups(selected: List<GroupEntry>) {
        viewModelScope.launch {
            selected.forEach { registry.addGroup(it) }
            _uiState.update { it.copy(discovered = emptyList()) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun refreshSignedIn() {
        val email = auth.storedEmail()
        _uiState.update {
            it.copy(
                isSignedIn = email != null,
                email = email,
            )
        }
    }
}