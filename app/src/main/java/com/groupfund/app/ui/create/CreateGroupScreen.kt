package com.groupfund.app.ui.create

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groupfund.app.ui.monthFullLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: CreateGroupViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Клон группы: подставляем название, базовый взнос и участников из исходной группы.
    LaunchedEffect(Unit) {
        CloneHolder.take()?.let { viewModel.applyPrefill(it) }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Повторяем только при реально выданном согласии, иначе снова откроется экран согласия.
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryLastAction()
        else viewModel.onConsentDenied()
    }

    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let {
            viewModel.consumeConsentIntent()
            consentLauncher.launch(it)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.created) {
        if (state.created) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая группа") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, "Закрыть")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Button(
                onClick = { viewModel.createGroup() },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.padding(start = 8.dp))
                }
                Text(if (state.busy) "Создаю…" else "Создать группу")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = state.baseAmount,
                    onValueChange = viewModel::onBaseChange,
                    label = { Text("Базовая сумма в месяц, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    "Окно: следующие $MONTHS_IN_WINDOW месяцев",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Отметьте месяцы, за которые берётся взнос. " +
                        "Сумма пустая = базовый взнос.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            items(state.months.size) { idx ->
                val m = state.months[idx]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            monthFullLabel(m.month),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (m.required) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (m.required) {
                            OutlinedTextField(
                                value = m.amount,
                                onValueChange = { viewModel.onMonthAmountChange(idx, it) },
                                placeholder = { Text(state.baseAmount.ifBlank { "0" }) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.size(width = 110.dp, height = 56.dp),
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                        }
                        Switch(
                            checked = m.required,
                            onCheckedChange = { viewModel.onMonthRequiredChange(idx, it) },
                        )
                    }
                }
            }

            item {
                Text(
                    "Участники",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                var showBirthdayPicker by remember { mutableStateOf(false) }
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = state.memberBirthdayMillis,
                    yearRange = 1920..java.time.LocalDate.now().year,
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                            utcTimeMillis in MIN_BIRTHDAY_MILLIS..System.currentTimeMillis()

                        override fun isSelectableYear(year: Int): Boolean =
                            year in 1920..java.time.LocalDate.now().year
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.memberInput,
                            onValueChange = viewModel::onMemberInputChange,
                            label = { Text("Имя участника") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.memberBirthdayMillis?.let(::dateShortLabel) ?: "",
                            onValueChange = {},
                            label = { Text("ДР") },
                            placeholder = { Text("ДД.ММ") },
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { showBirthdayPicker = true }) {
                                    Icon(Icons.Default.Cake, "Выбрать дату")
                                }
                            },
                            modifier = Modifier
                                .size(width = 118.dp, height = 56.dp)
                                .clickable { showBirthdayPicker = true },
                        )
                        IconButton(onClick = { viewModel.addMember() }) {
                            Icon(Icons.Default.Add, "Добавить участника")
                        }
                    }
                    Text(
                        "День рождения — необязательно. Выберите календарём.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showBirthdayPicker) {
                    DatePickerDialog(
                        onDismissRequest = { showBirthdayPicker = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    datePickerState.selectedDateMillis?.let {
                                        viewModel.onMemberBirthdaySet(it)
                                    }
                                    showBirthdayPicker = false
                                },
                            ) {
                                Text("ОК")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBirthdayPicker = false }) {
                                Text("Отмена")
                            }
                        },
                    ) {
                        DatePicker(state = datePickerState)
                    }
                }
            }

            if (state.members.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.members.forEachIndexed { i, name ->
                            val bday = state.memberBirthdays[name]
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        if (bday.isNullOrEmpty()) name else "$name · ${storedBirthdayShort(bday)}",
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { viewModel.removeMember(i) }) {
                                        Icon(Icons.Default.Close, "Удалить")
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                item {
                    Text(
                        "Добавьте участников. Создатель обычно вносит и себя.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** "ДД.ММ" из UTC-миллис выбранной даты. */
private fun dateShortLabel(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "%02d.%02d".format(d.dayOfMonth, d.monthValue)
}

/** 01.01.1920 UTC — самая ранняя выбираемая дата рождения. */
private val MIN_BIRTHDAY_MILLIS: Long =
    java.time.LocalDate.of(1920, 1, 1)
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

/** "ДД.ММ" из хранимой даты "гггг-мм-дд". */
private fun storedBirthdayShort(stored: String): String =
    try {
        val p = stored.split('-')
        if (p.size == 3) "%02d.%02d".format(p[2].toInt(), p[1].toInt()) else stored
    } catch (e: Exception) {
        stored
    }