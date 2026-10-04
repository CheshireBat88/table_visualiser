package com.groupfund.app.ui.detail

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.auth.OAuthConsentRequiredException
import com.groupfund.app.data.registry.GroupEntry
import com.groupfund.app.data.registry.GroupRegistry
import com.groupfund.app.data.sheets.GroupData
import com.groupfund.app.data.sheets.GroupSummary
import com.groupfund.app.data.sheets.MemberData
import com.groupfund.app.data.sheets.NotFoundException
import com.groupfund.app.data.sheets.PaymentInput
import com.groupfund.app.data.sheets.SheetsRepository
import com.groupfund.app.data.sheets.SummaryCalculator
import com.groupfund.app.data.sheets.isCollectionPayment
import com.groupfund.app.data.sheets.money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupDetailUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val group: GroupData? = null,
    val summary: GroupSummary? = null,
    val title: String? = null,
    val url: String? = null,
    val spreadsheetId: String? = null,
    val role: String = "member",
    val error: String? = null,
    val consentIntent: Intent? = null,
    val deleted: Boolean = false,
    /** Таблица открыта «по ссылке» — можно показывать QR-приглашение. */
    val shareReady: Boolean = false,
    /** Участник, которого этот аккаунт выбрал как «это я» в этой группе. */
    val selectedMember: String? = null,
)

class GroupDetailViewModel(
    app: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val groupId: String = checkNotNull(savedStateHandle["groupId"])
    private val registry = GroupRegistry(app)
    private var entry: GroupEntry? = null

    private val auth = GoogleAuthManager(app)
    private val repository = SheetsRepository(auth)

    private val _uiState = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private var lastAction: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            entry = registry.groupById(groupId)
            _uiState.update {
                it.copy(
                    title = entry?.localTitle ?: entry?.title,
                    url = entry?.spreadsheetUrl,
                    spreadsheetId = entry?.spreadsheetId,
                    role = entry?.role ?: "member",
                    loading = true,
                )
            }
            ackSelectedMember()
            initLoad()
        }
    }

    private suspend fun ackSelectedMember() {
        val e = entry ?: return
        val email = auth.storedEmail()
        if (email == null) return
        val sel = registry.selectedMember(email, e.spreadsheetId)
        _uiState.update { it.copy(selectedMember = sel) }
    }

    /** Пользователь выбрал, кто он в этой группе (локально, для этой группы). */
    fun selectMember(name: String) {
        val e = entry ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            auth.storedEmail()?.let { email ->
                registry.saveSelectedMember(email, e.spreadsheetId, name)
            }
            _uiState.update { it.copy(selectedMember = name) }
        }
    }

    private fun initLoad() {
        lastAction = { doLoad() }
        runLast()
    }

    fun consumeConsentIntent() = _uiState.update { it.copy(consentIntent = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Пользователь отменил/отклонил экран согласия Google — повторять действие нельзя. */
    fun onConsentDenied() = _uiState.update { it.copy(error = "Доступ к Google Таблицам не предоставлен") }
    fun consumeShareReady() = _uiState.update { it.copy(shareReady = false) }

    /** Открывает доступ к таблице «по ссылке» (автор для наблюдателей). */
    fun shareForInvite() {
        val entry = entry ?: return
        if (_uiState.value.busy) return
        lastAction = { doShareForInvite(entry.spreadsheetId) }
        runLast()
    }

    /** Повтор после выдачи согласия на OAuth-скопы. */
    fun retryLastAction() {
        runLast()
    }

    fun refresh() {
        if (_uiState.value.busy) return
        lastAction = { doLoad() }
        runLast()
    }

    fun deleteGroup() {
        val entry = entry ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                if (entry.role == "creator") {
                    repository.deleteFromDrive(entry.spreadsheetId)
                }
                registry.removeGroup(entry.id)
                _uiState.update { it.copy(deleted = true) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    /** Проверка: можно ли внести наличными перевод… (не используется). */
    fun addPayment(member: String, amount: Double, comment: String) {
        if (_uiState.value.busy || amount <= 0) return
        lastAction = { doAddPayment(member, amount, comment) }
        runLast()
    }

    /** Пакетное внесение: несколько (участник, сумма[, сбор]) одним сохранением. */
    fun addPaymentsBatch(inputs: List<PaymentInput>) {
        if (_uiState.value.busy || inputs.isEmpty()) return
        if (inputs.any { it.amount <= 0 || it.member.isBlank() }) return
        lastAction = { doAddPaymentsBatch(inputs) }
        runLast()
    }

    /** Возврат средств участнику: не больше его положительного остатка. */
    fun refundMember(name: String, amount: Double, comment: String) {
        if (_uiState.value.busy || amount <= 0) return
        val ms = _uiState.value.summary?.rows?.firstOrNull { it.member.name == name } ?: return
        if (amount > ms.balance) {
            _uiState.update { st -> st.copy(error = "Нельзя вернуть больше остатка (${money(ms.balance)})") }
            return
        }
        lastAction = { doRefundMember(name, amount, comment) }
        runLast()
    }

    fun addExpense(description: String, amount: Double, participants: List<String>, date: String) {
        if (_uiState.value.busy || amount <= 0 || description.isBlank()) return
        lastAction = { doAddExpense(description, amount, participants, date) }
        runLast()
    }

    /** Редактирует платёж по индексу в списке (дата не меняется). */
    fun editPayment(index: Int, member: String, amount: Double, comment: String) {
        if (_uiState.value.busy || amount <= 0 || member.isBlank()) return
        val group = _uiState.value.group ?: return
        if (index !in group.payments.indices) return
        if (group.payments[index].comment.isCollectionPayment()) return
        lastAction = { doEditPayment(index, member.trim(), amount, comment.trim()) }
        runLast()
    }

    /** Редактирует расход по индексу в списке (дата не меняется). */
    fun editExpense(index: Int, description: String, amount: Double, participants: List<String>) {
        if (_uiState.value.busy || amount <= 0 || description.isBlank()) return
        val group = _uiState.value.group ?: return
        if (index !in group.expenses.indices) return
        lastAction = { doEditExpense(index, description.trim(), amount, participants) }
        runLast()
    }

    /** Удаляет платёж по индексу в списке. Для платежей сборов также откатывает взнос во вкладке «Сборы». */
    fun deletePayment(index: Int) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        if (index !in group.payments.indices) return
        lastAction = { doDeletePayment(index) }
        runLast()
    }

    /** Удаляет расход по индексу в списке. */
    fun deleteExpense(index: Int) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        if (index !in group.expenses.indices) return
        lastAction = { doDeleteExpense(index) }
        runLast()
    }

    fun removeMember(name: String) {
        if (_uiState.value.busy) return
        val balance = _uiState.value.group?.let { g ->
            SummaryCalculator.compute(g).rows.firstOrNull { it.member.name == name }?.balance
        } ?: return
        if (balance < 0) {
            _uiState.update { st -> st.copy(error = "Нельзя исключить: у участника долг ${money(-balance)}") }
            return
        }
        if (_uiState.value.selectedMember == name) clearSelection()
        lastAction = { doRemoveMember(name) }
        runLast()
    }

    private fun clearSelection() {
        val e = _uiState.value
        viewModelScope.launch {
            auth.storedEmail()?.let { email -> e.spreadsheetId?.let { registry.clearSelectedMember(email, it) } }
            _uiState.update { it.copy(selectedMember = null) }
        }
    }

    /** Обновляет имя/день рождения участника. */
    fun updateMember(oldName: String, newName: String, birthday: String) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        val member = group.members.firstOrNull { it.name == oldName } ?: return
        val normalized = newName.trim()
        if (normalized.isBlank()) {
            _uiState.update { st -> st.copy(error = "Имя не может быть пустым") }
            return
        }
        if (normalized != oldName && group.members.any { it.name.equals(normalized, ignoreCase = true) }) {
            _uiState.update { st -> st.copy(error = "Участник с именем «$normalized» уже есть") }
            return
        }
        if (_uiState.value.selectedMember == oldName) {
            val e = entry
            viewModelScope.launch {
                auth.storedEmail()?.let { email ->
                    e?.let { registry.saveSelectedMember(email, it.spreadsheetId, normalized) }
                }
                _uiState.update { it.copy(selectedMember = normalized) }
            }
        }
        lastAction = { doUpdateMember(oldName, member.copy(name = normalized, birthday = birthday)) }
        runLast()
    }

    /** Создаёт отдельный сбор. */
    fun createCollection(name: String, target: Double, participants: List<String>, sharedWithBudget: Boolean = true) {
        if (_uiState.value.busy || name.isBlank() || target <= 0 || participants.isEmpty()) return
        lastAction = { doCreateCollection(name, target, participants, sharedWithBudget) }
        runLast()
    }

    /** Добавляет нового участника (присоединение — текущим днём). */
    fun addMember(name: String, birthday: String) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        val normalized = name.trim()
        if (normalized.isBlank()) {
            _uiState.update { st -> st.copy(error = "Укажите имя участника") }
            return
        }
        if (group.members.any { it.name.equals(normalized, ignoreCase = true) }) {
            _uiState.update { st -> st.copy(error = "Участник с именем «$normalized» уже есть") }
            return
        }
        lastAction = { doAddMember(normalized, birthday) }
        runLast()
    }

    /** Перезаписывает план месяцев (обязательность и суммы). */
    fun updateMonthPlan(months: List<com.groupfund.app.data.sheets.MonthPlan>) {
        if (_uiState.value.busy || months.isEmpty()) return
        lastAction = { doUpdateMonthPlan(months) }
        runLast()
    }

    /** Освобождает участника от взноса в выбранных месяцах. */
    fun setMemberOffMonths(name: String, offMonths: List<String>) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        val member = group.members.firstOrNull { it.name == name } ?: return
        val updated = member.copy(offMonths = offMonths.distinct().sorted())
        lastAction = { doUpdateMember(name, updated) }
        runLast()
    }

    /** Отмечает/снимает «сдал» участника по сбору. */
    fun toggleMemberPaid(collectionName: String, memberName: String, paid: Boolean) {
        if (_uiState.value.busy) return
        lastAction = { doToggleMemberPaid(collectionName, memberName, paid) }
        runLast()
    }

    /** Доплата по сбору: участник вносит оставшийся недобор (или иную сумму). */
    fun topUpCollection(collectionName: String, memberName: String, amount: Double?) {
        if (_uiState.value.busy) return
        val group = _uiState.value.group ?: return
        val collection = group.collections.firstOrNull { it.name == collectionName } ?: return
        if (memberName !in collection.participants) return
        val topUp = amount ?: collection.deficit(memberName)
        if (topUp <= 0) return
        lastAction = { doAddCollectionContribution(collectionName, memberName, topUp) }
        runLast()
    }

    /** Зачисляет в сбор сумму из свободного остатка месячных взносов участника (переклассификация). */
    fun reclassifyMonthlyToCollection(collectionName: String, memberName: String, amount: Double) {
        if (_uiState.value.busy || amount <= 0) return
        lastAction = { doReclassifyMonthlyToCollection(collectionName, memberName, amount) }
        runLast()
    }

    /** Редактирует сбор: новый состав, сумма и/или название. */
    fun editCollection(name: String, newName: String, target: Double, participants: List<String>, sharedWithBudget: Boolean = true) {
        if (_uiState.value.busy || newName.isBlank() || target <= 0 || participants.isEmpty()) return
        lastAction = { doEditCollection(name, newName, target, participants, sharedWithBudget) }
        runLast()
    }

    /** Удаляет отдельный сбор вместе со связанными платежами. */
    fun deleteCollection(name: String) {
        if (_uiState.value.busy) return
        lastAction = { doDeleteCollection(name) }
        runLast()
    }

    /**
     * Зачисляет в сбор сумму из свободного остатка месячных взносов участника:
     * деньги не новые — уже внесённые месячные взносы переклассифицируются в сбор,
     * баланс участника не уходит в минус (amount должен быть ≤ его свободного остатка).
     */
    fun topUpCollectionFromFreeBalance(collectionName: String, memberName: String, amount: Double) {
        if (_uiState.value.busy || amount <= 0) return
        lastAction = { doReclassifyMonthlyToCollection(collectionName, memberName, amount) }
        runLast()
    }

    private fun runLast() {
        val action = lastAction ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                action()
            } finally {
                // Иначе неожиданное исключение навсегда оставило бы UI в состоянии busy.
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun doShareForInvite(spreadsheetId: String) {
        _uiState.update { it.copy(shareReady = false) }
        val result = repository.shareForInvite(spreadsheetId)
        _uiState.update { st ->
            result.fold(
                onSuccess = { st.copy(shareReady = true) },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось открыть доступ по ссылке")
                    }
                },
            )
        }
    }

    private suspend fun doLoad() {
        val entry = entry ?: return
        val result = repository.loadGroup(entry.spreadsheetId)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    entry.let { registry.setUnavailable(it.id, false) }
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is NotFoundException) {
                        entry.let { registry.setUnavailable(it.id, true) }
                        st.copy(
                            loading = false,
                            error = "Группа удалена или создатель ограничил к ней доступ",
                        )
                    } else if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(
                            loading = false,
                            error = e.message ?: "Не удалось загрузить группу",
                        )
                    }
                },
            )
        }
    }

    /** Отображаемое название: локальное переименование пользователя поверх названия таблицы. */
    private fun displayTitle(sheetTitle: String): String = entry?.localTitle ?: sheetTitle

    private suspend fun doAddPayment(member: String, amount: Double, comment: String) {
        val entry = entry ?: return
        val result = repository.appendPayment(entry.spreadsheetId, member, amount, comment)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось внести платёж")
                    }
                },
            )
        }
    }

    private suspend fun doAddPaymentsBatch(inputs: List<PaymentInput>) {
        val entry = entry ?: return
        val result = repository.appendPaymentsBatch(entry.spreadsheetId, inputs)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось внести платежи")
                    }
                },
            )
        }
    }

    private suspend fun doRefundMember(name: String, amount: Double, comment: String) {
        val entry = entry ?: return
        val reason = comment.trim().ifEmpty { "возврат средств" }
        val result = repository.appendPayment(entry.spreadsheetId, name, amount, "Возврат: $reason")
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось оформить возврат")
                    }
                },
            )
        }
    }

    private suspend fun doAddExpense(
        description: String,
        amount: Double,
        participants: List<String>,
        date: String,
    ) {
        val entry = entry ?: return
        val result = repository.appendExpense(entry.spreadsheetId, description, amount, participants, date)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось добавить расход")
                    }
                },
            )
        }
    }

    private suspend fun doEditPayment(index: Int, member: String, amount: Double, comment: String) {
        val entry = entry ?: return
        val result = repository.editPayment(entry.spreadsheetId, index, member, amount, comment)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось изменить платёж")
                    }
                },
            )
        }
    }

    private suspend fun doEditExpense(
        index: Int,
        description: String,
        amount: Double,
        participants: List<String>,
    ) {
        val entry = entry ?: return
        val result = repository.editExpense(entry.spreadsheetId, index, description, amount, participants)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось изменить расход")
                    }
                },
            )
        }
    }

    private suspend fun doDeletePayment(index: Int) {
        val entry = entry ?: return
        val result = repository.deletePayment(entry.spreadsheetId, index)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось удалить платёж")
                    }
                },
            )
        }
    }

    private suspend fun doDeleteExpense(index: Int) {
        val entry = entry ?: return
        val result = repository.deleteExpense(entry.spreadsheetId, index)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось удалить расход")
                    }
                },
            )
        }
    }

    private suspend fun doRemoveMember(name: String) {
        val entry = entry ?: return
        val result = repository.removeMember(entry.spreadsheetId, name)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось исключить участника")
                    }
                },
            )
        }
    }

    private suspend fun doUpdateMember(oldName: String, updatedMember: MemberData) {
        val entry = entry ?: return
        val result = repository.updateMember(entry.spreadsheetId, oldName, updatedMember)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось обновить участника")
                    }
                },
            )
        }
    }

    private suspend fun doUpdateMonthPlan(months: List<com.groupfund.app.data.sheets.MonthPlan>) {
        val entry = entry ?: return
        val result = repository.updateMonthPlan(entry.spreadsheetId, months)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось обновить план месяцев")
                    }
                },
            )
        }
    }

    private suspend fun doAddMember(name: String, birthday: String) {
        val entry = entry ?: return
        val result = repository.addMember(entry.spreadsheetId, name, birthday)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось добавить участника")
                    }
                },
            )
        }
    }

    private suspend fun doCreateCollection(name: String, target: Double, participants: List<String>, sharedWithBudget: Boolean) {
        val entry = entry ?: return
        val result = repository.createCollection(entry.spreadsheetId, name, target, participants, sharedWithBudget)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось создать сбор")
                    }
                },
            )
        }
    }

    private suspend fun doToggleMemberPaid(collectionName: String, memberName: String, paid: Boolean) {
        val entry = entry ?: return
        val result = repository.toggleMemberPaid(entry.spreadsheetId, collectionName, memberName, paid)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось обновить сбор")
                    }
                },
            )
        }
    }

    private suspend fun doAddCollectionContribution(collectionName: String, memberName: String, amount: Double) {
        val entry = entry ?: return
        val result = repository.addCollectionContribution(entry.spreadsheetId, collectionName, memberName, amount)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось зачислить доплату по сбору")
                    }
                },
            )
        }
    }

    private suspend fun doReclassifyMonthlyToCollection(collectionName: String, memberName: String, amount: Double) {
        val entry = entry ?: return
        val result = repository.reclassifyMonthlyToCollection(entry.spreadsheetId, collectionName, memberName, amount)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось зачесть из месячных взносов")
                    }
                },
            )
        }
    }

    private suspend fun doEditCollection(
        name: String,
        newName: String,
        target: Double,
        participants: List<String>,
        sharedWithBudget: Boolean,
    ) {
        val entry = entry ?: return
        val result = repository.editCollection(entry.spreadsheetId, name, newName, target, participants, sharedWithBudget)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось изменить сбор")
                    }
                },
            )
        }
    }

    private suspend fun doDeleteCollection(name: String) {
        val entry = entry ?: return
        val result = repository.deleteCollection(entry.spreadsheetId, name)
        _uiState.update { st ->
            result.fold(
                onSuccess = { g ->
                    st.copy(
                        loading = false,
                        group = g,
                        summary = SummaryCalculator.compute(g),
                        title = displayTitle(g.title),
                    )
                    // Автоматическая одноразовая стилизация «Сводки» (только для создателя)
                    viewModelScope.launch {
                        try {
                            if (entry?.summaryStyled != true && entry?.role == "creator") {
                                repository.applySummaryStyle(entry.spreadsheetId)
                                entry?.let { registry.markSummaryStyled(it.id, true) }
                            }
                        } catch (_: Exception) {
                        }
                    }
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        st.copy(consentIntent = e.intent)
                    } else {
                        st.copy(error = e.message ?: "Не удалось удалить сбор")
                    }
                },
            )
        }
    }
}