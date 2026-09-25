package com.groupfund.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.auth.OAuthConsentRequiredException
import com.groupfund.app.data.registry.GroupEntry
import com.groupfund.app.data.registry.GroupRegistry
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

    /** Переименовывает группу локально («как отображается у пользователя»). */
    fun renameGroup(entryId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch { registry.renameGroup(entryId, newTitle) }
    }

    /**
     * Проверяет доступность всех групп текущему аккаунту и помечает недоступные
     * (таблица удалена или создатель ограничил доступ). Запускается при старте
     * приложения и после смены аккаунта.
     */
    fun refreshGroupsAvailability() {
        if (auth.storedEmail() == null || availabilityCheckRunning) return
        availabilityCheckRunning = true
        viewModelScope.launch {
            try {
                val current = registry.groups.first()
                current.forEach { entry ->
                    val accessible = repository.checkAccessible(entry.spreadsheetId).getOrNull()
                    if (accessible != null) registry.setUnavailable(entry.id, !accessible)
                }
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