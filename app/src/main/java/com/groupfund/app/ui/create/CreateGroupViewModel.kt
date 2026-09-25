package com.groupfund.app.ui.create

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.auth.OAuthConsentRequiredException
import com.groupfund.app.data.registry.GroupRegistry
import com.groupfund.app.data.sheets.GroupDraft
import com.groupfund.app.data.sheets.MonthPlan
import com.groupfund.app.data.sheets.SheetsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

/** Месяц в мастере создания: сумма вводится отдельно; пусто = базовый взнос. */
data class MonthDraft(
    val month: YearMonth,
    val required: Boolean = true,
    val amount: String = "",
)

data class CreateGroupUiState(
    val title: String = "",
    val baseAmount: String = "1500",
    val months: List<MonthDraft> = defaultWindow(),
    val memberInput: String = "",
    val memberBirthdayMillis: Long? = null,
    val members: List<String> = emptyList(),
    val memberBirthdays: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val consentIntent: Intent? = null,
    val created: Boolean = false,
    /** В списке пользователя уже есть группа с таким названием. */
    val duplicateTitle: Boolean = false,
)

val MONTHS_IN_WINDOW = 13

fun defaultWindow(): List<MonthDraft> =
    (0 until MONTHS_IN_WINDOW).map {
        MonthDraft(YearMonth.now().plusMonths(it.toLong()), required = true, amount = "")
    }

class CreateGroupViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = GoogleAuthManager(app)
    private val registry = GroupRegistry(app)
    private val repository = SheetsRepository(auth)

    private val _uiState = MutableStateFlow(CreateGroupUiState())
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()

    private var lastDraft: GroupDraft? = null

    /** Имена уже имеющихся групп (локальные и «настоящие», без учёта регистра). */
    private var existingGroupNames: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            registry.groups
                .map { list -> list.mapNotNull { (it.localTitle ?: it.title).trim().lowercase() }.toSet() }
                .collect { existingGroupNames = it }
        }
    }

    fun onTitleChange(v: String) = _uiState.update { st ->
        val trimmed = v.trim()
        st.copy(
            title = v,
            duplicateTitle = trimmed.isNotEmpty() && trimmed.lowercase() in existingGroupNames,
        )
    }
    fun onBaseChange(v: String) = _uiState.update { it.copy(baseAmount = v.filter { c -> c.isDigit() }) }
    fun onMemberInputChange(v: String) = _uiState.update { it.copy(memberInput = v) }
    fun onMemberBirthdaySet(millis: Long?) = _uiState.update { it.copy(memberBirthdayMillis = millis) }

    fun onMonthRequiredChange(index: Int, required: Boolean) = _uiState.update { st ->
        if (index !in st.months.indices) st
        else st.copy(
            months = st.months.toMutableList().also {
                it[index] = it[index].copy(required = required)
            },
        )
    }

    fun onMonthAmountChange(index: Int, amount: String) = _uiState.update { st ->
        if (index !in st.months.indices) st
        else st.copy(
            months = st.months.toMutableList().also {
                it[index] = it[index].copy(amount = amount.filter { c -> c.isDigit() })
            },
        )
    }

    fun addMember() {
        val st = _uiState.value
        val name = st.memberInput.trim()
        if (name.isNotEmpty() && name !in st.members) {
            val bday = st.memberBirthdayMillis?.let(::fullDateString) ?: ""
            _uiState.update {
                it.copy(
                    members = it.members + name,
                    memberBirthdays = if (bday.isNotEmpty()) it.memberBirthdays + (name to bday) else it.memberBirthdays,
                    memberInput = "",
                    memberBirthdayMillis = null,
                )
            }
        }
    }

    fun removeMember(index: Int) = _uiState.update { st ->
        val name = st.members[index]
        st.copy(
            members = st.members.toMutableList().also { it.removeAt(index) },
            memberBirthdays = st.memberBirthdays - name,
        )
    }

    fun consumeConsentIntent() = _uiState.update { it.copy(consentIntent = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    /** Пользователь отменил/отклонил экран согласия Google — повторять действие нельзя. */
    fun onConsentDenied() = _uiState.update { it.copy(error = "Доступ к Google Таблицам не предоставлен") }

    /**
     * Заполняет мастер данными исходной группы для клона: название с пометкой «(копия)»,
     * тот же базовый взнос, те же активные участники с датами рождения.
     * Финансы (платежи/расходы/сборы) в копию не переносятся — месячный план проставляется
     * заново от текущего месяца.
     */
    fun applyPrefill(prefill: ClonePrefill) {
        _uiState.update {
            it.copy(
                title = prefill.title,
                baseAmount = prefill.baseAmount.toString(),
                months = defaultWindow(),
                members = prefill.members,
                memberBirthdays = prefill.memberBirthdays,
            )
        }
    }

    fun retryLastAction() {
        lastDraft?.let { performCreate(it) }
    }

    fun createGroup() {
        val st = _uiState.value
        val base = st.baseAmount.toIntOrNull() ?: 0

        when {
            st.title.isBlank() -> _uiState.update { it.copy(error = "Укажите название группы") }
            base <= 0 -> _uiState.update { it.copy(error = "Укажите базовую сумму в месяц") }
            st.members.isEmpty() -> _uiState.update { it.copy(error = "Добавьте хотя бы одного участника") }
            !st.months.any { it.required } -> _uiState.update { it.copy(error = "Отметьте хотя бы один обязательный месяц") }
            else -> {
                val months = st.months.map {
                    MonthPlan(
                        month = it.month,
                        required = it.required,
                        amount = if (it.required) (it.amount.toIntOrNull() ?: base) else 0,
                    )
                }
                val draft = GroupDraft(
                    title = st.title.trim(),
                    baseAmount = base,
                    months = months,
                    members = st.members,
                    memberBirthdays = st.memberBirthdays,
                )
                performCreate(draft)
            }
        }
    }

    private fun performCreate(draft: GroupDraft) {
        if (_uiState.value.busy) return
        lastDraft = draft
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = repository.createGroup(draft)
            _uiState.update { it.copy(busy = false) }
            result.fold(
                onSuccess = { entry ->
                    registry.addGroup(entry)
                    _uiState.update { it.copy(created = true) }
                },
                onFailure = { e ->
                    if (e is OAuthConsentRequiredException) {
                        _uiState.update { it.copy(consentIntent = e.intent) }
                    } else {
                        _uiState.update { it.copy(error = e.message ?: "Не удалось создать группу") }
                    }
                },
            )
        }
    }
}

/** Полная дата "гггг-мм-дд" из UTC-миллис DatePicker. */
private fun fullDateString(millis: Long): String {
    val date = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
}