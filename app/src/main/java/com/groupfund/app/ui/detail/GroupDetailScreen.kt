package com.groupfund.app.ui.detail

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groupfund.app.data.sheets.CollectionData
import com.groupfund.app.data.sheets.CollectionConsistency
import com.groupfund.app.data.sheets.GroupData
import com.groupfund.app.data.sheets.GroupSummary
import com.groupfund.app.data.sheets.MonthPlan
import com.groupfund.app.data.sheets.PaymentData
import com.groupfund.app.data.sheets.PaymentInput
import com.groupfund.app.data.sheets.PeriodStatistics
import com.groupfund.app.data.sheets.PeriodSummary
import com.groupfund.app.data.sheets.SheetCodec
import com.groupfund.app.data.sheets.SummaryCalculator
import com.groupfund.app.data.sheets.money
import com.groupfund.app.data.sheets.round2
import com.groupfund.app.ui.copyUrl
import com.groupfund.app.ui.currencySymbol
import com.groupfund.app.ui.monthFullLabel
import com.groupfund.app.ui.monthShortLabel
import com.groupfund.app.ui.openUrl
import com.groupfund.app.ui.qrBitmap
import com.groupfund.app.ui.create.CloneHolder
import com.groupfund.app.ui.create.ClonePrefill
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onClone: () -> Unit = {},
    viewModel: GroupDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Повторяем действие только если согласие реально выдано: при отмене
        // повтор снова упёрся бы в экран согласия и зациклился.
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

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    var showShareDialog by remember { mutableStateOf(false) }

var showPayDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var payMemberIdx by remember { mutableIntStateOf(-1) }
    var payAmount by remember { mutableStateOf("") }
    var payComment by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }

    var showExpenseDialog by remember { mutableStateOf(false) }
    var expDate by remember { mutableStateOf("") }
    var expDesc by remember { mutableStateOf("") }
    var expAmount by remember { mutableStateOf("") }
    var expParticipants by remember { mutableStateOf(setOf<String>()) }
    var expError by remember { mutableStateOf<String?>(null) }

    var editingPaymentIdx by remember { mutableStateOf<Int?>(null) }
    var deletingPaymentIdx by remember { mutableStateOf<Int?>(null) }
    var editingExpenseIdx by remember { mutableStateOf<Int?>(null) }
    var deletingExpenseIdx by remember { mutableStateOf<Int?>(null) }

    var memberToRemove by remember { mutableStateOf<String?>(null) }
    var selectedMember by remember { mutableStateOf<String?>(null) }

    var memberPayTarget by remember { mutableStateOf<String?>(null) }
    var memberPayAmount by remember { mutableStateOf("") }
    var memberPayComment by remember { mutableStateOf("") }
    var memberPayError by remember { mutableStateOf<String?>(null) }
    var memberRefundTarget by remember { mutableStateOf<String?>(null) }
    var memberRefundAmount by remember { mutableStateOf("") }
    var memberRefundComment by remember { mutableStateOf("") }
    var memberRefundError by remember { mutableStateOf<String?>(null) }
    var showRefundDialog by remember { mutableStateOf(false) }
var showCollectionDialog by remember { mutableStateOf(false) }

    var editingCollection by remember { mutableStateOf<String?>(null) }

    var collName by remember { mutableStateOf("") }
    var collTarget by remember { mutableStateOf("") }
    var collParticipants by remember { mutableStateOf(setOf<String>()) }
    var collShared by remember { mutableStateOf(true) }
    var collError by remember { mutableStateOf<String?>(null) }

    var showAddMember by remember { mutableStateOf(false) }
    var showMonthPlan by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.title ?: "Группа",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
actions = {
                    if (state.url != null) {
                        IconButton(onClick = { openUrl(context, state.url!!) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Открыть таблицу")
                        }
                    }
                    if (state.role == "creator") {
                        IconButton(onClick = {
                            showShareDialog = true
                            viewModel.shareForInvite()
                        }) {
                            Icon(Icons.Default.QrCode, "Пригласить наблюдателей")
                        }
                    }
                    IconButton(onClick = {
                        state.group?.let {
                            CloneHolder.prefill = ClonePrefill(
                                title = "${it.title} (копия)",
                                baseAmount = it.baseAmount,
                                members = it.activeMembers.map { m -> m.name },
                                memberBirthdays = it.activeMembers.mapNotNull { m ->
                                    m.birthday.takeIf { b -> b.isNotEmpty() }?.let { b -> m.name to b }
                                }.toMap(),
                            )
                            onClone()
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, "Клонировать группу")
                    }
                    IconButton(
                        onClick = { viewModel.exportSummaryPng() },
                        enabled = state.group != null && state.summary != null && !state.busy,
                    ) {
                        Icon(Icons.Default.Share, "Экспорт сводки в PNG")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Удалить группу")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val group = state.group
        val summary = state.summary

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && group == null -> CenterBox { CircularProgressIndicator() }
                group == null && state.error != null -> CenterBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error.orEmpty(), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Повторить") }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.deleteGroup() }) {
                            Text("Удалить из списка")
                        }
                    }
                }
                group != null && summary != null -> {
                    Box(Modifier.fillMaxSize()) {
                        Content(
                            group = group,
                            summary = summary,
                            busy = state.busy,
                            canEdit = state.role == "creator",
                            selectedMember = state.selectedMember,
                            onSelectMember = viewModel::selectMember,
                            onAddPayment = { showPayDialog = true },
                            onAddBatch = { showBatchDialog = true },
                            onAddExpense = {
                                expDate = com.groupfund.app.data.sheets.SheetCodec.today()
                                expDesc = ""
                                expAmount = ""
                                expParticipants = group.activeMembers.map { it.name }.toSet()
                                expError = null
                                showExpenseDialog = true
                            },
                            onRemoveMember = { memberToRemove = it },
                            onOpenMember = { selectedMember = it },
                            onAddMember = { showAddMember = true },
                            onEditMonths = { showMonthPlan = true },
                            onRefresh = { viewModel.refresh() },
                            onCreateCollection = {
                                collName = ""
                                collTarget = ""
                                collParticipants = group.activeMembers.map { it.name }.toSet()
                                collShared = true
                                collError = null
                                showCollectionDialog = true
                            },
onToggleMemberPaid = viewModel::toggleMemberPaid,
                            onTopUpCollection = viewModel::topUpCollection,
                            onReclassifyToCollection = viewModel::topUpCollectionFromFreeBalance,
                            onEditCollection = { name ->
                                val c = group.collections.firstOrNull { it.name == name }
                                if (c != null) {
                                    editingCollection = name
                                    collName = c.name
                                    collTarget = if (c.target == Math.floor(c.target)) {
                                        c.target.toLong().toString()
                                    } else {
                                        c.target.toString()
                                    }
                                    collParticipants = c.participants.toSet()
                                    collShared = c.sharedWithBudget
                                    showCollectionDialog = true
                                }
                            },
                            onEditPayment = { editingPaymentIdx = it },
                            onDeletePayment = { deletingPaymentIdx = it },
                            onEditExpense = { editingExpenseIdx = it },
                            onDeleteExpense = { deletingExpenseIdx = it },
                        )
                        val selected = selectedMember
                        // «Назад» при открытой карточке участника закрывает её. Этот BackHandler
                        // регистрируется ПОСЛЕ BackHandler во вкладках (Content) и BackHandler,
                        // открывающего результат, поэтому выигрывает приоритет, пока карточка видна.
                        BackHandler(
                            enabled = selectedMember != null,
                            onBack = { selectedMember = null },
                        )
                        if (selected != null) {
                            MemberCard(
                                group = group,
                                name = selected,
                                canEdit = state.role == "creator",
                                busy = state.busy,
                                currency = group.currency,
                                balance = summary?.rows?.find { it.member.name == selected }?.balance ?: 0.0,
                                onClose = { selectedMember = null },
                                onSave = { old, newName, birthday ->
                                    viewModel.updateMember(old, newName, birthday)
                                    selectedMember = null
                                },
                                onRemove = { name ->
                                    selectedMember = null
                                    memberToRemove = name
                                },
                                onRequestPayment = { name ->
                                    selectedMember = null
                                    memberPayTarget = name
                                    memberPayAmount = ""
                                    memberPayComment = ""
                                    memberPayError = null
                                },
                                onRequestRefund = { name ->
                                    selectedMember = null
                                    memberRefundTarget = name
                                    memberRefundAmount = ""
                                    memberRefundComment = ""
                                    memberRefundError = null
                                    showRefundDialog = true
                                },
                                onSetOffMonths = viewModel::setMemberOffMonths,
                            )
                        }
                        if (showAddMember) {
                            AddMemberPanel(
                                group = group,
                                busy = state.busy,
                                onClose = { showAddMember = false },
                                onAdd = { name, birthday ->
                                    viewModel.addMember(name, birthday)
                                    showAddMember = false
                                },
                            )
                        }
                        if (showMonthPlan) {
                            MonthPlanPanel(
                                group = group,
                                busy = state.busy,
                                onClose = { showMonthPlan = false },
                                onSave = { months ->
                                    viewModel.updateMonthPlan(months)
                                    showMonthPlan = false
                                },
                            )
                        }
                    }
                }
            }

            if (showPayDialog && group != null) {
                val members = group.activeMembers.sortedBy { it.name.lowercase() }
                AlertDialog(
                    onDismissRequest = { showPayDialog = false },
                    title = { Text("Внести платёж") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (members.isEmpty()) {
                                Text("Нет активных участников")
                            } else {
                                Text(
                                    "Кому (${members.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    members.forEachIndexed { i, m ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { payMemberIdx = i },
                                        ) {
                                            RadioButton(
                                                selected = payMemberIdx == i,
                                                onClick = { payMemberIdx = i },
                                            )
                                            Text(m.name)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = payAmount,
                                onValueChange = { payAmount = filterAmount(it) },
                                label = { Text("Сумма, ₽") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = payComment,
                                onValueChange = { payComment = it },
                                label = { Text("Комментарий (необязательно)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            dialogError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val amount = payAmount.toDoubleOrNull()
                                val member = members.getOrNull(payMemberIdx)
                                when {
                                    member == null -> dialogError = "Выберите участника"
                                    amount == null || amount <= 0 -> dialogError = "Укажите сумму"
                                    else -> {
                                        viewModel.addPayment(member.name, amount, payComment.trim())
                                        showPayDialog = false
                                        payMemberIdx = -1
                                        payAmount = ""
                                        payComment = ""
                                        dialogError = null
                                    }
                                }
                            },
                        ) {
                            Text("Провести")
                        }
                    },
dismissButton = {
                        TextButton(onClick = { showPayDialog = false }) { Text("Отмена") }
                    },
                )
            }

            // Пакетное внесение: несколько платежей за одно сохранение.
            if (showBatchDialog && group != null) {
                BatchPayDialog(
                    group = group,
                    onSave = { inputs ->
                        if (state.role == "creator") {
                            viewModel.addPaymentsBatch(inputs)
                        }
                        showBatchDialog = false
                    },
                    onDismiss = { showBatchDialog = false },
                )
            }

            // Возврат средств участнику (инициирует создатель)
            if (showRefundDialog) {
                memberRefundTarget?.let { targetName ->
                    val memberBalance =
                        summary?.rows?.find { it.member.name == targetName }?.balance ?: 0.0
                    AlertDialog(
                        onDismissRequest = {
                            showRefundDialog = false
                            memberRefundTarget = null
                            memberRefundError = null
                        },
                        title = { Text("Возврат средств") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Участник: $targetName\nДоступно к возврату: ${money(memberBalance)} " +
                                        "${currencySymbol(group?.currency ?: "RUB")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedTextField(
                                    value = memberRefundAmount,
                                    onValueChange = { memberRefundAmount = filterAmount(it) },
                                    label = { Text("Сумма, ₽") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = memberRefundComment,
                                    onValueChange = { memberRefundComment = it },
                                    label = { Text("Комментарий (необязательно)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                memberRefundError?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val amount = memberRefundAmount.toDoubleOrNull()
                                    when {
                                        amount == null || amount <= 0 -> memberRefundError = "Укажите сумму"
                                        amount > memberBalance -> memberRefundError =
                                            "Сумма превышает доступный остаток"
                                        else -> {
                                            viewModel.refundMember(
                                                targetName,
                                                amount,
                                                memberRefundComment.trim(),
                                            )
                                            showRefundDialog = false
                                            memberRefundTarget = null
                                            memberRefundAmount = ""
                                            memberRefundComment = ""
                                            memberRefundError = null
                                        }
                                    }
                                },
                            ) {
                                Text("Провести")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRefundDialog = false }) { Text("Отмена") }
                        },
                    )
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Удалить группу?") },
                    text = {
                        Text(
                            if (state.role == "creator") {
                                "«${state.title}» будет удалена из приложения и из вашего Google Диска. Таблица удалится безвозвратно."
                            } else {
                                "«${state.title}» будет удалена из вашего списка групп."
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteGroup()
                                showDeleteConfirm = false
                            },
                        ) {
                            Text("Удалить")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Отмена")
                        }
                    },
                )
            }

            if (showShareDialog) {
                val inviteLink = state.spreadsheetId?.let { "https://cheshirebat88.github.io/invite/$it" } ?: ""
                AlertDialog(
                    onDismissRequest = {
                        showShareDialog = false
                        viewModel.consumeShareReady()
                    },
                    title = { Text("Пригласить наблюдателей") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
Text(
                                "Наблюдатель отсканирует QR или откроет ссылку — на устройстве " +
                                    "с установленным приложением она запустит его автоматически, " +
                                    "иначе покажет страницу с кнопкой «Открыть в приложении».",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.busy && !state.shareReady) {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                Text("Открываем доступ по ссылке…", style = MaterialTheme.typography.labelSmall)
                            } else if (state.shareReady) {
                                // QR рисуется попиксельно и не должен блокировать UI: считаем
                                // один раз на ссылку и в фоне, а не при каждой рекомпозиции.
                                val bmp by produceState<Bitmap?>(null, inviteLink) {
                                    value = withContext(Dispatchers.Default) { qrBitmap(inviteLink) }
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp!!.asImageBitmap(),
                                        contentDescription = "QR-приглашение",
                                        modifier = Modifier
                                            .size(220.dp)
                                            .background(Color.White),
                                    )
                                }
                                Text(
                                    inviteLink,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Button(onClick = { copyUrl(context, inviteLink) }) {
                                    Text("Копировать ссылку")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showShareDialog = false
                                viewModel.consumeShareReady()
                            },
                        ) {
                            Text("Готово")
                        }
                    },
                )
            }

            if (showExpenseDialog && group != null) {
                val active = group.activeMembers.map { it.name }
                AlertDialog(
                    onDismissRequest = { showExpenseDialog = false },
                    title = { Text("Добавить расход") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = expDate,
                                onValueChange = { expDate = it },
                                label = { Text("Дата (ГГГГ-ММ-ДД)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = expDesc,
                                onValueChange = { expDesc = it },
                                label = { Text("За что (описание)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = expAmount,
                                onValueChange = { expAmount = filterAmount(it) },
                                label = { Text("Сумма, ₽") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "На кого распределить (круг фиксируется сейчас):",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (active.isEmpty()) {
                                Text("Нет активных участников")
                            } else {
                                Text(
                                    "Всего участников: ${active.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    active.sorted().forEach { name ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expParticipants =
                                                        if (name in expParticipants) expParticipants - name
                                                        else expParticipants + name
                                                },
                                        ) {
                                            Checkbox(
                                                checked = name in expParticipants,
                                                onCheckedChange = {
                                                    expParticipants =
                                                        if (it) expParticipants + name
                                                        else expParticipants - name
                                                },
                                            )
                                            Text(name)
                                        }
                                    }
                                }
                            }
                            Text(
                                if (expParticipants.isEmpty()) {
                                    "Никто не выбран — расход учтётся на всех активных"
                                } else {
                                    "Доля на каждого: ${money((expAmount.toDoubleOrNull() ?: 0.0) / expParticipants.size)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            expError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val amount = expAmount.toDoubleOrNull()
                                val participants = expParticipants.ifEmpty {
                                    (group.activeMembers.map { it.name }).toSet()
                                }
                                when {
                                    expDesc.isBlank() -> expError = "Укажите, за что расход"
                                    amount == null || amount <= 0 -> expError = "Укажите сумму"
                                    participants.isEmpty() -> expError = "Нет участников, на которых распределить"
                                    else -> {
                                        viewModel.addExpense(
                                            date = expDate.ifBlank { com.groupfund.app.data.sheets.SheetCodec.today() },
                                            description = expDesc.trim(),
                                            amount = amount,
                                            participants = participants.toList(),
                                        )
                                        showExpenseDialog = false
                                        expError = null
                                    }
                                }
                            },
                        ) {
                            Text("Добавить")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExpenseDialog = false }) { Text("Отмена") }
                    },
                )
            }

            // Новый отдельный сбор
            if (showCollectionDialog && group != null) {
                val active = group.activeMembers.map { it.name }
                AlertDialog(
                    onDismissRequest = {
                        showCollectionDialog = false
                        editingCollection = null
                    },
                    title = { Text(if (editingCollection == null) "Новый сбор" else "Изменить сбор") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = collName,
                                onValueChange = { collName = it },
                                label = { Text("Название (например, «Подарок» или «Бензин»)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = collTarget,
                                onValueChange = { collTarget = filterAmount(it) },
                                label = { Text("Цель сбора, ₽") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = collShared,
                                    onCheckedChange = { collShared = it },
                                )
                                Text(
                                    "В общий бюджет",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                "На кого разложить поровну:",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (active.isEmpty()) {
                                Text("Нет активных участников")
                            } else {
                                Text(
                                    "Всего участников: ${active.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    active.sorted().forEach { name ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    collParticipants =
                                                        if (name in collParticipants) collParticipants - name
                                                        else collParticipants + name
                                                },
                                        ) {
                                            Checkbox(
                                                checked = name in collParticipants,
                                                onCheckedChange = {
                                                    collParticipants =
                                                        if (it) collParticipants + name
                                                        else collParticipants - name
                                                },
                                            )
                                            Text(name)
                                        }
                                    }
                                }
                            }
                            val target = collTarget.toDoubleOrNull()
                            Text(
                                if (target != null && collParticipants.isNotEmpty()) {
                                    "Доля с каждого: ${money(target / collParticipants.size)}"
                                } else {
                                    "Доля с каждого: —"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            collError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (editingCollection != null) {
                                TextButton(
                                    onClick = {
                                        val cName = editingCollection
                                        if (cName != null) {
                                            viewModel.deleteCollection(cName)
                                        }
                                        showCollectionDialog = false
                                        editingCollection = null
                                        collError = null
                                    },
                                ) {
                                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            TextButton(
                                onClick = {
                                    val target = collTarget.toDoubleOrNull()
                                    when {
                                        collName.isBlank() -> collError = "Укажите название сбора"
                                        target == null || target <= 0 -> collError = "Укажите сумму сбора"
                                        collParticipants.isEmpty() -> collError = "Выберите участников"
                                        else -> {
                                            if (editingCollection == null) {
                                                viewModel.createCollection(collName.trim(), target, collParticipants.toList(), collShared)
                                            } else {
                                                viewModel.editCollection(
                                                    editingCollection!!,
                                                    collName.trim(),
                                                    target,
                                                    collParticipants.toList(),
                                                    collShared,
                                                )
                                            }
                                            showCollectionDialog = false
                                            editingCollection = null
                                            collError = null
                                        }
                                    }
                                },
                            ) {
                                Text(if (editingCollection == null) "Создать" else "Сохранить")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showCollectionDialog = false
                            editingCollection = null
                        }) { Text("Отмена") }
                    },
                )
            }

            // Взнос за конкретного участника (из его карточки)
            memberPayTarget?.let { member ->
                AlertDialog(
                    onDismissRequest = {
                        memberPayTarget = null
                        memberPayError = null
                    },
                    title = { Text("Внести деньги") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Участник: $member",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlinedTextField(
                                value = memberPayAmount,
                                onValueChange = { memberPayAmount = filterAmount(it) },
                                label = { Text("Сумма, ₽") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = memberPayComment,
                                onValueChange = { memberPayComment = it },
                                label = { Text("Комментарий (необязательно)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            memberPayError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val amount = memberPayAmount.toDoubleOrNull()
                                if (amount == null || amount <= 0) {
                                    memberPayError = "Укажите сумму"
                                } else {
                                    viewModel.addPayment(member, amount, memberPayComment.trim())
                                    memberPayTarget = null
                                    memberPayError = null
                                }
                            },
                        ) {
                            Text("Провести")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { memberPayTarget = null }) { Text("Отмена") }
                    },
                )
            }

            // Исключение участника: должника исключить нельзя, пока не погасит долг
            memberToRemove?.let { name ->
                val row = summary?.rows?.find { it.member.name == name }
                val balance = row?.balance ?: 0.0
                AlertDialog(
                    onDismissRequest = { memberToRemove = null },
                    title = {
                        Text(if (balance < 0) "Нельзя исключить участника" else "Исключить участника?")
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (balance < 0) {
                                Text(
                                    "У «$name» остаток долга за расходы: " +
                                        "${money(-balance)} ${currencySymbol(group?.currency ?: "RUB")}.\n\n" +
                                        "Пока долг не погашен, исключить его нельзя. " +
                                        "Сначала внесите платёж за него на недостающую сумму.",
                                )
                            } else if (balance > 0) {
                                Text(
                                    "«$name» исключается из группы.\n\n" +
                                        "Внесено больше, чем доля расходов: возврат " +
                                        "${money(balance)} ${currencySymbol(group?.currency ?: "RUB")} " +
                                        "(деньги отдаёт создатель лично).",
                                )
                            } else {
                                Text("«$name» исключается из группы.\n\nБаланс нулевой — возврата нет.")
                            }
                            Text(
                                "История платежей и расходов участника сохранится на листах таблицы.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    confirmButton = {
                        if (balance >= 0) {
                            TextButton(
                                onClick = {
                                    viewModel.removeMember(name)
                                    memberToRemove = null
                                },
                            ) {
                                Text("Исключить")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { memberToRemove = null }) {
                            Text(if (balance < 0) "Понятно" else "Отмена")
                        }
                    },
                )
            }

            editingPaymentIdx?.let { idx ->
                if (group != null) {
                    EditPaymentDialog(
                        group = group,
                        index = idx,
                        busy = state.busy,
                        onDismiss = { editingPaymentIdx = null },
                        onSave = { memberName, amount, comment ->
                            viewModel.editPayment(idx, memberName, amount, comment)
                            editingPaymentIdx = null
                        },
                    )
                }
            }

deletingPaymentIdx?.let { idx ->
                if (group != null && idx in group.payments.indices) {
                    val p = group.payments[idx]
                    val collectionNote = when {
                        p.comment.startsWith("Сбор: ") -> {
                            "Взнос участника в сбор «${p.comment.removePrefix("Сбор: ").trim()}» уменьшится."
                        }
                        p.comment.startsWith("Возврат из сбора: ") -> {
                            "Сумма вернётся во взнос участника по сбору «${p.comment.removePrefix("Возврат из сбора: ").trim()}»."
                        }
                        else -> null
                    }
                    AlertDialog(
                        onDismissRequest = { deletingPaymentIdx = null },
                        title = { Text("Удалить платёж?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Платёж «${p.member}» на ${money(p.amount)} ${currencySymbol(group.currency)}" +
                                        (if (p.comment.isNotBlank()) " (${p.comment})" else "") +
                                        " будет удалён безвозвратно.",
                                )
                                if (collectionNote != null) {
                                    Text(
                                        collectionNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deletePayment(idx)
                                    deletingPaymentIdx = null
                                },
                            ) {
                                Text("Удалить")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingPaymentIdx = null }) { Text("Отмена") }
                        },
                    )
                }
            }

            editingExpenseIdx?.let { idx ->
                if (group != null) {
                    EditExpenseDialog(
                        group = group,
                        index = idx,
                        busy = state.busy,
                        onDismiss = { editingExpenseIdx = null },
                        onSave = { description, amount, participants ->
                            viewModel.editExpense(idx, description, amount, participants)
                            editingExpenseIdx = null
                        },
                    )
                }
            }

            deletingExpenseIdx?.let { idx ->
                if (group != null && idx in group.expenses.indices) {
                    val e = group.expenses[idx]
                    AlertDialog(
                        onDismissRequest = { deletingExpenseIdx = null },
                        title = { Text("Удалить расход?") },
                        text = {
                            Text(
                                "Расход «${e.description}» на ${money(e.amount)} ${currencySymbol(group.currency)}" +
                                    " будет удалён безвозвратно.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deleteExpense(idx)
                                    deletingExpenseIdx = null
                                },
                            ) {
                                Text("Удалить")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deletingExpenseIdx = null }) { Text("Отмена") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/** Только цифры и одна десятичная точка. */
private fun filterAmount(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> {
                sb.append(c)
                dotSeen = true
            }
        }
    }
return sb.toString()
}

@Composable
private fun Content(
    group: GroupData,
    summary: GroupSummary,
    busy: Boolean,
    canEdit: Boolean,
    selectedMember: String?,
    onSelectMember: (String) -> Unit,
onAddPayment: () -> Unit,
    onAddBatch: () -> Unit,
    onAddExpense: () -> Unit,
    onRemoveMember: (String) -> Unit,
    onOpenMember: (String) -> Unit,
    onAddMember: () -> Unit,
    onEditMonths: () -> Unit,
    onRefresh: () -> Unit,
    onCreateCollection: () -> Unit,
    onToggleMemberPaid: (String, String, Boolean) -> Unit,
onTopUpCollection: (String, String, Double?) -> Unit,
    onReclassifyToCollection: (String, String, Double) -> Unit,
    onEditCollection: (String) -> Unit,
    onEditPayment: (Int) -> Unit,
    onDeletePayment: (Int) -> Unit,
    onEditExpense: (Int) -> Unit,
    onDeleteExpense: (Int) -> Unit,
) {
var selectedTab by remember { mutableIntStateOf(0) }
    var summaryCollapsed by remember { mutableStateOf(true) }
    var standaloneExpanded by remember { mutableStateOf(setOf<String>()) }
    var showPeriodDialog by remember { mutableStateOf(false) }
    val tabs = listOf("Сводка", "Моя информация", "Платежи", "Сборы", "Расходы", "Участники")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // «Назад»: сначала закрывает открытую карточку участника, затем возвращает на «Сводку»,
    // со «Сводки» — выходит из группы. Закрытие карточки обрабатывает BackHandler в группе
    // (он регистрируется позже и выигрывает приоритет), здесь — только возврат на «Сводку».
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = pagerState.currentPage == i,
                    onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                    text = { Text(label, maxLines = 1) },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            when (page) {
                0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Взнос в месяц: ${group.baseAmount} ${currencySymbol(group.currency)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val required = summary.requiredMonths
                        Text(
                            "Платных месяцев: ${required.size} из ${summary.months.size}" +
                                (if (required.isNotEmpty())
                                    " (${
                                        monthShortLabel(required.first().month)
                                    } — ${monthShortLabel(required.last().month)})"
                                else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canEdit) {
                            TextButton(onClick = onEditMonths) {
                                Text("Изменить месяцы и суммы")
                            }
                        }
                    }
                }

// Баланс группы — крупно, над таблицей сводки. Тап открывает сводку за период.
                val groupBalance = summary.totals.getOrNull(summary.months.size + 3) ?: 0.0
                Card(
                    onClick = { showPeriodDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (groupBalance < -0.005)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Баланс группы",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${if (groupBalance >= 0) "+" else ""}${money(groupBalance)} ${currencySymbol(group.currency)}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (groupBalance < -0.005)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Нажмите — траты и поступления за неделю/месяц/период",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ExpandMore,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Оплаты по месяцам (${currencySymbol(group.currency)})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(onClick = { summaryCollapsed = !summaryCollapsed }) {
                        Text(if (summaryCollapsed) "Развернуть" else "Свернуть")
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(40.dp)
                                .height(40.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        TextButton(onClick = onRefresh) {
                            Text("Обновить")
                        }
                    }
                }
                if (summaryCollapsed) CompactSummary(summary) else PaymentTable(summary)
                StandaloneCollectionsBlock(
                    collections = group.collections,
                    currency = group.currency,
                    expandedNames = standaloneExpanded,
                    onToggle = { name ->
                        standaloneExpanded =
                            if (name in standaloneExpanded) standaloneExpanded - name
                            else standaloneExpanded + name
                    },
                )
                if (!canEdit) {
                    Text(
                        "Платежи и расходы ведёт только создатель группы.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!canEdit && selectedMember == null) {
                    Text(
                        "Откройте вкладку «Моя информация» и выберите себя — увидите свои взносы и баланс.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            1 -> MyInfoTab(group, summary, selectedMember, onSelectMember)
2 -> PaymentsTab(
                group = group,
                busy = busy,
                canEdit = canEdit,
                onAddPayment = onAddPayment,
                onAddBatch = onAddBatch,
                onEditPayment = onEditPayment,
                onDeletePayment = onDeletePayment,
            )
3 -> CollectionsTab(
                group = group,
                summary = summary,
                busy = busy,
                canEdit = canEdit,
                onCreateCollection = onCreateCollection,
                onToggleMemberPaid = onToggleMemberPaid,
                onTopUpCollection = onTopUpCollection,
                onReclassifyToCollection = onReclassifyToCollection,
                onEditCollection = onEditCollection,
            )
            4 -> ExpensesTab(
                group = group,
                summary = summary,
                busy = busy,
                canEdit = canEdit,
                onAddExpense = onAddExpense,
                onEditExpense = onEditExpense,
                onDeleteExpense = onDeleteExpense,
            )
5 -> MembersTab(group, summary, canEdit, onRemoveMember, onOpenMember, onAddMember)
            }
        }

        if (showPeriodDialog) {
            PeriodSummaryDialog(
                group = group,
                currency = group.currency,
                onDismiss = { showPeriodDialog = false },
            )
        }
    }
}

private enum class PeriodMode { WEEK, MONTH, CUSTOM }

private enum class DatePickTarget { WEEK, START, END }

/**
 * Окно сводки за период: выбор недели/месяца/произвольного срока, галочки
 * «Траты / Траты вне бюджета / Поступления» и итог с разбивкой по участникам.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSummaryDialog(
    group: GroupData,
    currency: String,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val dateText = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }

    var mode by remember { mutableStateOf(PeriodMode.WEEK) }
    var weekDay by remember { mutableStateOf<LocalDate?>(null) }
    var month by remember { mutableStateOf<YearMonth?>(null) }
    var startDate by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var endDate by remember { mutableStateOf(today) }
    var includeExpenses by remember { mutableStateOf(true) }
    var includeOffBudget by remember { mutableStateOf(true) }
    var includeIncome by remember { mutableStateOf(true) }

    var result by remember { mutableStateOf<PeriodSummary?>(null) }
    var participantsExpanded by remember { mutableStateOf(false) }

    var picking by remember { mutableStateOf<DatePickTarget?>(null) }

    // Все месяцы, упоминаемые данными группы (плюс текущий) — для выбора «Месяц».
    val knownMonths = remember(group) {
        val s = linkedSetOf<YearMonth>()
        group.months.forEach { s.add(it.month) }
        group.expenses.forEach { runCatching { s.add(YearMonth.from(LocalDate.parse(it.date))) } }
        group.payments.forEach { runCatching { s.add(YearMonth.from(LocalDate.parse(it.date))) } }
        group.collections.forEach { runCatching { s.add(YearMonth.from(LocalDate.parse(it.date))) } }
        s.add(YearMonth.now())
        s.sortedDescending()
    }

    val weekRange = remember(weekDay) {
        val day = weekDay ?: today
        val start = day.minusDays((day.dayOfWeek.value - 1).toLong())
        start to start.plusDays(6)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result == null) "Сводка за период" else "Сводка за период") },
        text = {
            val res = result
            if (res != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${dateText.format(res.fromDate)} — ${dateText.format(res.toDate)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (includeExpenses) {
                        Line("Траты", res.expensesTotal, currency)
                    }
                    if (includeOffBudget) {
                        Line("Траты вне бюджета", res.offBudgetTotal, currency)
                    }
                    if (includeIncome) {
                        Line("Поступления", res.incomeTotal, currency)
                    }
                    if (includeIncome && (includeExpenses || includeOffBudget)) {
                        HorizontalDivider()
                        val delta = res.delta
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Разница (доходы − траты)",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${if (delta >= 0) "+" else ""}${money(delta)} ${currencySymbol(currency)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (delta < -0.005) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { participantsExpanded = !participantsExpanded }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            "Траты участников",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (participantsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                        )
                    }
                    if (participantsExpanded) {
                        val listed = res.members
                            .filter { it.total > 0.005 }
                            .sortedByDescending { it.total }
                        if (listed.isEmpty()) {
                            Text("За период начислений по участникам нет", style = MaterialTheme.typography.bodySmall)
                        } else {
                            listed.forEach { m ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            m.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "${money(m.total)} ${currencySymbol(currency)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    if (m.offBudgetShare > 0.005) {
                                        Text(
                                            "в бюджете ${money(m.expenseShare)} · вне бюджета ${money(m.offBudgetShare)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Показываем траты, внебюджетные траты (отдельные сборы) и поступления за выбранный срок.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PeriodMode.WEEK to "Неделя", PeriodMode.MONTH to "Месяц", PeriodMode.CUSTOM to "Произвольный")
                            .forEach { (m, label) ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = { mode = m },
                                    label = { Text(label) },
                                )
                            }
                    }
                    when (mode) {
                        PeriodMode.WEEK -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Неделя ${dateText.format(weekRange.first)} — ${dateText.format(weekRange.second)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { picking = DatePickTarget.WEEK }) {
                                    Text("Выбрать")
                                }
                            }
                        }
                        PeriodMode.MONTH -> {
                            Text(
                                "Месяц: ${month?.let(::monthShortLabel) ?: "выберите ниже"}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                knownMonths.forEach { ym ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { month = ym }
                                            .padding(vertical = 4.dp),
                                    ) {
                                        Text(
                                            monthFullLabel(ym),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (month == ym) {
                                            Text("✓", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        PeriodMode.CUSTOM -> {
                            TextButton(onClick = { picking = DatePickTarget.START }) {
                                Text("Начало: ${dateText.format(startDate)}")
                            }
                            TextButton(onClick = { picking = DatePickTarget.END }) {
                                Text("Конец: ${dateText.format(endDate)}")
                            }
                            if (endDate.isBefore(startDate)) {
                                Text(
                                    "Начало позже конца — поменяем их местами",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    CheckRow("Траты", includeExpenses, { includeExpenses = it })
                    CheckRow("Траты вне бюджета", includeOffBudget, { includeOffBudget = it })
                    CheckRow("Поступления", includeIncome, { includeIncome = it })
                }
            }
        },
        confirmButton = {
            if (result != null) {
                TextButton(onClick = onDismiss) { Text("Готово") }
            } else {
                TextButton(
                    onClick = {
                        val from: LocalDate
                        val to: LocalDate
                        when (mode) {
                            PeriodMode.WEEK -> {
                                from = weekRange.first
                                to = weekRange.second
                            }
                            PeriodMode.MONTH -> {
                                val ym = month ?: YearMonth.now()
                                from = ym.atDay(1)
                                to = ym.atEndOfMonth()
                            }
                            PeriodMode.CUSTOM -> {
                                from = startDate
                                to = endDate
                            }
                        }
                        val (a, b) = if (from.isAfter(to)) to to from else from to to
                        result = PeriodStatistics.compute(group, a, b)
                        participantsExpanded = false
                    },
                ) {
                    Text("Показать")
                }
            }
        },
        dismissButton = {
            if (result != null) {
                TextButton(onClick = { result = null }) { Text("Изменить период") }
            } else {
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )

    picking?.let { target ->
        val initial = when (target) {
            DatePickTarget.WEEK -> weekDay ?: today
            DatePickTarget.START -> startDate
            DatePickTarget.END -> endDate
        }
        DatePickDialog(
            initial = initial,
            onPick = { picked ->
                when (target) {
                    DatePickTarget.WEEK -> weekDay = picked
                    DatePickTarget.START -> startDate = picked
                    DatePickTarget.END -> endDate = picked
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun Line(label: String, amount: Double, currency: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${money(amount)} ${currencySymbol(currency)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms != null) {
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                } else {
                    onDismiss()
                }
            }) { Text("ОК") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@Composable
private fun MyInfoTab(
    group: GroupData,
    summary: GroupSummary,
    selectedMember: String?,
    onSelectMember: (String) -> Unit,
) {
var picking by remember { mutableStateOf(false) }
    var expensesCollapsed by remember { mutableStateOf(true) }
    var offBudgetCollapsed by remember { mutableStateOf(true) }
    var paymentsCollapsed by remember { mutableStateOf(true) }
    val active = group.activeMembers
    val current = selectedMember?.let { name -> active.firstOrNull { it.name == name } }
    val currency = group.currency

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current != null && !picking) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { picking = true }) {
                        Text("Сменить участника")
                    }
                }
            }
        }

        if (current == null || picking) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Кто вы в этой группе?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Выберите себя из участников — покажем ваши взносы, баланс и сборы. " +
                                "Выбор хранится только на этом устройстве и отдельно для каждой группы.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        HorizontalDivider()
                        if (active.isEmpty()) {
                            Text("В группе пока нет участников", style = MaterialTheme.typography.bodySmall)
                        } else {
                            active.forEachIndexed { i, m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelectMember(m.name)
                                            picking = false
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        m.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (m.name == current?.name) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (i < active.size - 1) HorizontalDivider()
                            }
                        }
                    }
                }
            }
            if (picking) {
                item {
                    TextButton(onClick = { picking = false }) {
                        Text("Отмена")
                    }
                }
            }
        } else {
            val ms = summary.rows.firstOrNull { it.member.name == current.name }
            if (ms != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Это вы: ${current.name}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                ms.paidThrough?.let { "Взносы оплачены по ${monthShortLabel(it)}" }
                                    ?: "Обязательные взносы ещё не оплачены",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            HorizontalDivider()
                            Row {
                                Text(
                                    "Внесено всего",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${money(ms.totalPaid)} ${currencySymbol(currency)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row {
                                Text(
                                    "из них по сборам",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${money(ms.collectionShare)} ${currencySymbol(currency)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row {
                                Text(
                                    "Доля расходов и сборов",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${money(ms.expenseShare)} ${currencySymbol(currency)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider()
                            Row {
                                Text(
                                    "Баланс",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${money(ms.balance)} ${currencySymbol(currency)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (ms.balance >= 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            Text(
                                if (ms.balance >= 0) {
                                    "Впереди остаток — можно вернуть или оставить в общем счёте"
                                } else {
                                    "Недоплачено в общий счёт за расходы и сборы"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Взносы по месяцам (${currencySymbol(currency)})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            summary.requiredMonths.forEach { mp ->
                                val idx = summary.months.indexOf(mp)
                                val paid = ms.perMonth.getOrElse(idx) { 0.0 }
                                val exempt = mp.label() in ms.member.offMonths
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        monthShortLabel(mp.month),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        when {
                                            paid > 0 -> money(paid)
                                            exempt -> "без оплаты"
                                            else -> "не оплачено"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (paid > 0) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            paid > 0 -> MaterialTheme.colorScheme.onSurface
                                            exempt -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                            if (summary.requiredMonths.isEmpty()) {
                                Text("Обязательных взносов в окне нет", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

item {
                CollapsibleCard(
                    title = "Траты по месяцам (${currencySymbol(currency)})",
                    collapsed = expensesCollapsed,
                    onToggle = { expensesCollapsed = !expensesCollapsed },
                ) {
                    val byMonth = PeriodStatistics.memberExpenseSharesByMonth(group, current.name, summary.months)
                    if (summary.months.isEmpty()) {
                        Text("В окне группы нет месяцев", style = MaterialTheme.typography.bodySmall)
                    } else {
                        summary.months.forEach { mp ->
                            val v = byMonth[mp.month] ?: 0.0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    monthShortLabel(mp.month),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    if (v > 0) "${money(v)} ${currencySymbol(currency)}" else "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (byMonth.isEmpty()) {
                            Text("Начислений по тратам нет", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                CollapsibleCard(
                    title = "Внебюджетные траты (${currencySymbol(currency)})",
                    collapsed = offBudgetCollapsed,
                    onToggle = { offBudgetCollapsed = !offBudgetCollapsed },
                ) {
                    val rows = PeriodStatistics.memberOffBudgetRows(group, current.name)
                    if (rows.isEmpty()) {
                        Text("Отдельных сборов нет", style = MaterialTheme.typography.bodySmall)
                    } else {
                        rows.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${money(r.share)} ${currencySymbol(currency)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        monthShortLabel(r.month),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Итого",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${money(rows.sumOf { it.share })} ${currencySymbol(currency)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            val myCollections = group.collections.filter { current.name in it.participants }
            if (myCollections.isNotEmpty()) {
                item {
                    Text(
                        "Сборы",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                myCollections.forEach { c ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    c.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Доля: ${money(c.sharePerPerson)} · внесено: ${money(c.contributed(current.name))} " +
                                        currencySymbol(currency),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val deficit = c.deficit(current.name)
                                Text(
                                    if (deficit <= 0) "Взнос собран ✓" else "Не доплачено ${money(deficit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (deficit <= 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                CollapsibleCard(
                    title = "Мои платежи (${currencySymbol(currency)})",
                    collapsed = paymentsCollapsed,
                    onToggle = { paymentsCollapsed = !paymentsCollapsed },
                ) {
                    val memberPayments = group.payments
                        .mapIndexed { i, p -> IndexedValue(i, p) }
                        .filter { it.value.member == current.name }
                        .sortedByDescending { it.value.date }
                    if (memberPayments.isEmpty()) {
                        Text("Платежей пока нет", style = MaterialTheme.typography.bodySmall)
                    } else {
                        PaymentTransactionRows(memberPayments, currency)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    null,
                )
            }
            if (!collapsed) {
                HorizontalDivider()
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun PaymentsTab(
    group: GroupData,
    busy: Boolean,
    canEdit: Boolean,
    onAddPayment: () -> Unit,
    onAddBatch: () -> Unit,
    onEditPayment: (Int) -> Unit,
    onDeletePayment: (Int) -> Unit,
) {
var details by remember { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<String?>(null) }
    val consistencyWarnings = remember(group) { CollectionConsistency.mismatches(group) }
    val byMemberPayments = remember(group.payments) {
        group.payments.groupBy { it.member }.toList().sortedWith(
            compareByDescending<Pair<String, List<PaymentData>>> { pair ->
                pair.second.maxOfOrNull { it.date } ?: ""
            }.thenBy { it.first.lowercase() },
        )
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canEdit) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onAddPayment,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("+ Внести платёж")
                    }
                    OutlinedButton(
                        onClick = onAddBatch,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Мультивнесение")
                    }
                }
            }
        }
        if (selectedMember != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { selectedMember = null }) {
                        Text("Ко всем")
                    }
                }
            }
        } else if (group.payments.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { details = !details }) {
                        Text(if (details) "Свернуть" else "Подробно")
                    }
                }
            }
        }
        if (consistencyWarnings.isNotEmpty()) {
            item {
                ConsistencyWarningCard(consistencyWarnings)
            }
        }
        if (group.payments.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Пока нет платежей. Взносы вносятся вручную.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canEdit) {
                            Button(
                                onClick = onAddPayment,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("+ Внести первый платёж")
                            }
                        }
                    }
                }
            }
        } else {
            val member = selectedMember
            if (member != null) {
                item {
                    val memberPayments = remember(member, group.payments) {
                        group.payments
                            .mapIndexed { i, p -> IndexedValue(i, p) }
                            .filter { it.value.member == member }
                            .asReversed()
                            .sortedByDescending { it.value.date }
                    }
                    val total = memberPayments.sumOf { it.value.amount }
                    val coll = memberPayments.filter { it.value.comment.startsWith("Сбор: ") }.sumOf { it.value.amount }
                    val base = round2(total - coll)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                member,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Взносы: ${money(base)} · Сборы: ${money(coll)} · всего: ${
                                    money(total)
                                } ${currencySymbol(group.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            PaymentTransactionRows(
                                memberPayments,
                                group.currency,
                                canEdit = canEdit,
                                onEdit = onEditPayment,
                                onDelete = onDeletePayment,
                            )
                        }
                    }
                }
            } else if (details) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Все транзакции", style = MaterialTheme.typography.bodyLarge)
                            PaymentTransactionRows(
                                group.payments.mapIndexed { i, p -> IndexedValue(i, p) }
                                    .asReversed()
                                    .sortedByDescending { it.value.date },
                                group.currency,
                                canEdit = canEdit,
                                onEdit = onEditPayment,
                                onDelete = onDeletePayment,
                            )
                        }
                    }
                }
            } else {
                items(byMemberPayments, key = { it.first }) { (memberName, list) ->
                    val total = list.sumOf { it.amount }
                    val coll = list.filter { it.comment.startsWith("Сбор: ") }.sumOf { it.amount }
                    val base = round2(total - coll)
                    val last = list.maxOfOrNull { it.date } ?: ""
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMember = memberName },
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(memberName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Взносы: ${money(base)} · Сборы: ${money(coll)} · " +
                                            "платежей: ${list.size} · последний: $last",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${money(total)} ${currencySymbol(group.currency)}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "всего внесено",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentTransactionRows(
    payments: List<IndexedValue<PaymentData>>,
    currency: String,
    canEdit: Boolean = false,
    onEdit: (Int) -> Unit = {},
    onDelete: (Int) -> Unit = {},
) {
    payments.forEachIndexed { i, (index, p) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.member, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${p.date}${if (p.comment.isNotBlank()) " · ${p.comment}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "${money(p.amount)} ${currencySymbol(currency)}",
                style = MaterialTheme.typography.bodyMedium,
            )
if (canEdit && !p.comment.startsWith("Сбор: ")) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Изменить платёж",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onEdit(index) },
                )
            }
            if (canEdit) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Удалить платёж",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDelete(index) },
                )
            }
        }
        if (i < payments.size - 1) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun ConsistencyWarningCard(warnings: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Рассинхронизация «Платежей» и «Сборов»",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Bold,
            )
            warnings.take(4).forEach { w ->
                Text(
                    w,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (warnings.size > 4) {
                Text(
                    "и ещё ${warnings.size - 4}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
            }
            Text(
                "Удалите повторные записи во вкладке «Платежи».",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun CollectionsTab(
    group: GroupData,
    summary: GroupSummary,
    busy: Boolean,
    canEdit: Boolean,
    onCreateCollection: () -> Unit,
    onToggleMemberPaid: (String, String, Boolean) -> Unit,
    onTopUpCollection: (String, String, Double?) -> Unit,
    onReclassifyToCollection: (String, String, Double) -> Unit,
    onEditCollection: (String) -> Unit,
) {
    // Свободный остаток месячных взносов каждого участника — для зачёта доплаты
    // из месячной оплаты вместо «наличных».
val freeBalances = summary.rows.associate { it.member.name to it.balance }
    val consistencyWarnings = remember(group) { CollectionConsistency.mismatches(group) }
    var expandedNames by remember { mutableStateOf(setOf<String>()) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canEdit) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onCreateCollection, enabled = !busy) {
                        Text("+ Новый сбор")
                    }
                }
            }
        }
        item {
            Text(
                "Отдельные сборы вне ежемесячных платежей. Доля считается с каждого участника; " +
                    "видно, кто сколько внёс. Лишнее при уменьшении цели уходит в месячную оплату.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (consistencyWarnings.isNotEmpty()) {
            item {
                ConsistencyWarningCard(consistencyWarnings)
            }
        }
        if (group.collections.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Сборов пока нет. Например, «Подарок» или «Бензин на поездку»: " +
                                "цель делится поровну, каждый сдаёт свою долю.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canEdit) {
                            Button(
                                onClick = onCreateCollection,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("+ Создать первый сбор")
                            }
                        }
                    }
                }
            }
} else {
            // distinctBy name: защита от дублей с одинаковым именем в листе (наследие сбоев
            // записи 429). Ключи по индексу — не могут конфликтовать.
            val collections = group.collections.distinctBy { it.name }.sortedByDescending { it.date }
            itemsIndexed(
                collections,
                key = { index, _ -> index },
            ) { _, c ->
                val totalContrib = c.participants.sumOf { c.countedContribution(it) }
                val collected = c.participants.isNotEmpty() && totalContrib >= c.target - 0.05
                val collectedLabel =
                    if (collected) "✓ Собрано"
                    else "Собрано ${money(totalContrib)} из ${money(c.target)}"
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (c.name in expandedNames) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Цель ${money(c.target)} ${currencySymbol(group.currency)} · " +
                                            "доля ${money(c.sharePerPerson)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Создан ${c.date}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        collectedLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (collected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (canEdit) {
                                        TextButton(onClick = { onEditCollection(c.name) }, enabled = !busy) {
                                            Text("Изменить")
                                        }
                                    }
                                    TextButton(onClick = { expandedNames = expandedNames - c.name }) {
                                        Text("Свернуть")
                                    }
                                }
                            }
                            c.participants.sorted().forEach { name ->
                                CollectionMemberRow(
                                    collection = c,
                                    name = name,
                                    canToggle = canEdit && !busy,
                                    freeBalance = freeBalances[name] ?: 0.0,
                                    currencySymbol = currencySymbol(group.currency),
                                    onToggle = { onToggleMemberPaid(c.name, name, it) },
                                    onCashTopUp = { amount -> onTopUpCollection(c.name, name, amount) },
                                    onReclassify = { amount -> onReclassifyToCollection(c.name, name, amount) },
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(
                                    collectedLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (collected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                TextButton(onClick = { expandedNames = expandedNames + c.name }) {
                                    Text("Развернуть")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionMemberRow(
    collection: CollectionData,
    name: String,
    canToggle: Boolean,
    freeBalance: Double,
    currencySymbol: String,
    onToggle: (Boolean) -> Unit,
    onCashTopUp: (Double) -> Unit,
    onReclassify: (Double) -> Unit,
) {
    val contributed = collection.contributed(name)
    val share = collection.sharePerPerson
    val deficit = collection.deficit(name)
    val fully = name in collection.paid
    var showTopUp by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = fully,
                enabled = canToggle,
                onCheckedChange = onToggle,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = {
                        if (share <= 0) 0f else (contributed / share).toFloat().coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Text(
                    "внесли ${money(contributed)} из ${money(share)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (canToggle) {
                TextButton(onClick = { showTopUp = true }) {
                    Text(if (deficit > 0) "Доплатить ${money(deficit)}" else "Внести ещё")
                }
            } else if (fully) {
                Text(
                    "✓ сдал",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val excess = round2(contributed - share)
        if (excess > 0) {
            Text(
                "Излишек ${money(excess)} зачтён в месячную оплату",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    if (showTopUp) {
        TopUpDialog(
            collectionName = collection.name,
            memberName = name,
            prefilled = deficit.coerceAtLeast(0.0),
            freeBalance = freeBalance,
            currencySymbol = currencySymbol,
            onCashTopUp = {
                showTopUp = false
                onCashTopUp(it)
            },
            onReclassify = {
                showTopUp = false
                onReclassify(it)
            },
            onDismiss = { showTopUp = false },
        )
    }
}

@Composable
private fun TopUpDialog(
    collectionName: String,
    memberName: String,
    prefilled: Double,
    freeBalance: Double,
    currencySymbol: String,
    onCashTopUp: (Double) -> Unit,
    onReclassify: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var amountText by remember { mutableStateOf(formatAmountInput(prefilled)) }
    val amount = amountText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 0.0
    val canReclassify =
        freeBalance > 0.005 && amount > 0 && amount <= freeBalance + 0.005
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доплата по сбору") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "«$collectionName» — $memberName",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Остаток до доли: ${money(prefilled)} $currencySymbol. " +
                        "Можно указать любую сумму — если внёс больше, излишек уйдёт в месячную оплату.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = filterAmount(it) },
                    label = { Text("Сумма") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (freeBalance > 0.005) {
                        "Можно зачесть из месячных взносов. Свободный остаток: " +
                            "${money(freeBalance)} $currencySymbol."
                    } else {
                        "Свободного остатка в месячных взносах нет — можно только внести деньги."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (freeBalance > 0.005) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                )
            }
        },
        confirmButton = {
            Row {
                if (freeBalance > 0.005) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onReclassify(amount)
                        },
                        enabled = canReclassify,
                    ) {
                        Text("Из месячных")
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onCashTopUp(amount)
                    },
                    enabled = amount > 0,
                ) {
                    Text("Внести")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

/** Формирует текст для поля суммы без дробного хвоста у целых значений. */
private fun formatAmountInput(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(value)

/**
 * Пакетное внесение: несколько записей «участник, сумма[, сбор]» добавляются в список
 * и сохраняются в таблицу одним запросом. Если выбран сбор — взнос идёт по нему
 * (комментарий «Сбор: X»), иначе это обычный месячный платёж.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchPayDialog(
    group: GroupData,
    onSave: (List<PaymentInput>) -> Unit,
    onDismiss: () -> Unit,
) {
    val collections = remember(group) { group.collections.map { it.name }.sortedBy { it.lowercase() } }
    var member by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var memberMenu by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    val entries = remember { mutableStateListOf<PaymentInput>() }

    // При выбранном сборе выбирать можно только его участников (пустой список = все активные).
    val availableMembers = remember(group, collection) {
        val names = if (collection == null) {
            group.activeMembers.map { it.name }
        } else {
            group.collections.firstOrNull { it.name == collection }?.participants
                .orEmpty()
                .ifEmpty { group.activeMembers.map { it.name } }
        }
        names.sortedBy { it.lowercase() }
    }
    LaunchedEffect(availableMembers) {
        if (member != null && member !in availableMembers) member = null
    }

    val total = entries.sumOf { it.amount }

    fun addEntry() {
        val m = member
        val a = amount.toDoubleOrNull()
        when {
            m == null -> error = "Выберите участника"
            a == null || a <= 0 -> error = "Укажите сумму больше нуля"
            else -> {
                val comment = collection?.let { "${SummaryCalculator.COLLECTION_TAG_PREFIX}$it" } ?: ""
                entries.add(PaymentInput(m, a, comment))
                amount = ""
                error = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Внести несколько платежей") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Участник", style = MaterialTheme.typography.labelLarge)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { memberMenu = true },
                        enabled = availableMembers.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            member ?: if (availableMembers.isEmpty()) "Нет участников" else "Выберите участника",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = memberMenu, onDismissRequest = { memberMenu = false }) {
                        availableMembers.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    member = name
                                    memberMenu = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = filterAmount(it) },
                    label = { Text("Сумма, ${currencySymbol(group.currency)}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Сбор (необязательно)", style = MaterialTheme.typography.labelLarge)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { collectionMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            collection ?: "Без сбора",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = collectionMenu, onDismissRequest = { collectionMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Без сбора") },
                            onClick = {
                                collection = null
                                collectionMenu = false
                            },
                        )
                        collections.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    collection = name
                                    collectionMenu = false
                                },
                            )
                        }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Button(
                    onClick = { addEntry() },
                    enabled = member != null && amount.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Добавить")
                }

                if (entries.isNotEmpty()) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Добавлено (${entries.size})",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Итого: ${money(total)} ${currencySymbol(group.currency)}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    entries.forEachIndexed { index, entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.member, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    entry.comment.ifBlank { "Месячный взнос" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Text(money(entry.amount), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { entries.removeAt(index) }) {
                                Icon(Icons.Default.Delete, "Удалить")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(entries.toList()) },
                enabled = entries.isNotEmpty(),
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun ExpensesTab(
    group: GroupData,
    summary: GroupSummary,
    busy: Boolean,
    canEdit: Boolean,
    onAddExpense: () -> Unit,
    onEditExpense: (Int) -> Unit,
    onDeleteExpense: (Int) -> Unit,
) {
    var expandedIndices by remember { mutableStateOf(setOf<Int>()) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canEdit) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onAddExpense, enabled = !busy) {
                        Text("+ Добавить")
                    }
                }
            }
        }
        if (group.expenses.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Пока нет расходов. Учтите траты: доля спишется с баланса каждого, кто попал в круг.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (canEdit) {
                            Button(
                                onClick = onAddExpense,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("+ Добавить первый расход")
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val balances = remember(summary) {
                            summary.rows.associate { it.member.name to it.balance }
                        }
                        group.expenses
                            .mapIndexed { i, e -> IndexedValue(i, e) }
                            .asReversed()
                            .sortedByDescending { it.value.date }
                            .forEachIndexed { i, (index, e) ->
                            val expanded = index in expandedIndices
                            Column(Modifier.fillMaxWidth()) {
                                if (expanded) {
                                    Text(
                                        e.description,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${e.date} · участников: ${e.participants.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    e.participants.sorted().forEach { name ->
                                        val bal = balances[name] ?: 0.0
                                        val paid = bal >= e.averageShare - 0.005
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 4.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (paid) Icons.Default.Check else Icons.Default.Close,
                                                contentDescription = null,
                                                tint = if (paid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(name, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "Итого: ${money(e.fairTotal)} ${currencySymbol(group.currency)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                "доля/чел: ${money(e.averageShare)}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (canEdit) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = "Изменить расход",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { onEditExpense(index) },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Удалить расход",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { onDeleteExpense(index) },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.ExpandLess,
                                                contentDescription = "Свернуть",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { expandedIndices = expandedIndices - index },
                                            )
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(e.description, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "доля: ${money(e.averageShare)} · итого: ${money(e.fairTotal)} ${currencySymbol(group.currency)}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        if (canEdit) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = "Изменить расход",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { onEditExpense(index) },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Удалить расход",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { onDeleteExpense(index) },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.ExpandMore,
                                                contentDescription = "Развернуть",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable { expandedIndices = expandedIndices + index },
                                            )
                                        }
                                    }
                                }
                            }
                            if (i < group.expenses.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersTab(
    group: GroupData,
    summary: GroupSummary,
    canEdit: Boolean,
    onRemoveMember: (String) -> Unit,
    onOpenMember: (String) -> Unit,
    onAddMember: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canEdit) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onAddMember) {
                        Icon(Icons.Default.Add, "Добавить участника")
                        Spacer(Modifier.width(4.dp))
                        Text("Добавить")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (group.activeMembers.isEmpty()) {
                        Text("Нет активных участников", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        group.activeMembers.sortedBy { it.name.lowercase() }.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenMember(m.name) },
                            ) {
                                val ms = summary.rows.find { it.member.name == m.name }
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Внесено ${money(ms?.totalPaid ?: 0.0)} · доля расходов ${money(ms?.expenseShare ?: 0.0)}" +
                                            if (m.birthdayShort.isNotBlank()) " · ДР ${m.birthdayShort}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    if ((ms?.balance ?: 0.0) >= 0) {
                                        "+${money(ms?.balance ?: 0.0)}"
                                    } else {
                                        "-${money(-(ms?.balance ?: 0.0))}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (canEdit) {
                                    IconButton(onClick = { onRemoveMember(m.name) }) {
                                        Icon(
                                            Icons.Outlined.PersonRemove,
                                            contentDescription = "Исключить ${m.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    val removed = group.members.filter { !it.active }
                    if (removed.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        removed.sortedBy { it.name.lowercase() }.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenMember(m.name) },
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                    Text(
                                        "Исключён · возврат ${money(m.refund ?: 0.0)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPlanPanel(
    group: GroupData,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (List<com.groupfund.app.data.sheets.MonthPlan>) -> Unit,
) {
    data class EditableMonth(
        val label: String,
        val required: Boolean,
        val amount: String,
    )

    var editing by remember(group.title) {
        mutableStateOf(
            group.months.map {
                EditableMonth(
                    label = it.label(),
                    required = it.required,
                    amount = if (it.required) it.amount.toString() else "",
                )
            },
        )
    }
    var planError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Месячные взносы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Закрыть")
            }
        }
        Text(
            "Снимите галочку, чтобы убрать платёж за месяц; в поле сумма — размер взноса. " +
                "Освобождение отдельных участников настраивается в их карточке.",
            style = MaterialTheme.typography.bodySmall,
        )

        editing.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = m.required,
                    onCheckedChange = { checked ->
                        editing = editing.map {
                            if (it.label == m.label) it.copy(required = checked, amount = if (checked) it.amount else "")
                            else it
                        }
                        planError = null
                    },
                )
                Text(
                    monthShortLabel(MonthPlan.of(m.label)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = m.amount,
                    onValueChange = { v ->
                        editing = editing.map {
                            if (it.label == m.label) it.copy(amount = v.filter { c -> c.isDigit() }) else it
                        }
                        planError = null
                    },
                    label = { Text("Сумма") },
                    enabled = m.required,
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
                IconButton(
                    onClick = {
                        editing = editing.filterNot { it.label == m.label }
                        planError = null
                    },
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.Delete, "Удалить месяц")
                }
            }
        }

        TextButton(
            onClick = {
                val lastLabel = editing.lastOrNull()?.label
                val lastMonth = lastLabel?.let { MonthPlan.of(it) }
                    ?: java.time.YearMonth.now().minusMonths(1)
                editing = editing + EditableMonth(
                    label = lastMonth.plusMonths(1).toString(),
                    required = true,
                    amount = group.baseAmount.toString(),
                )
                planError = null
            },
            enabled = !busy,
        ) {
            Icon(Icons.Default.Add, "Добавить месяц")
            Spacer(Modifier.width(4.dp))
            Text("Добавить месяц")
        }

        planError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                val months = editing.map {
                    MonthPlan(
                        month = MonthPlan.of(it.label),
                        required = it.required,
                        amount = if (it.required) it.amount.toIntOrNull() ?: 0 else 0,
                    )
                }
                when {
                    months.isEmpty() -> planError = "Добавьте хотя бы один месяц"
                    months.none { it.required } -> planError = "Отметьте хотя бы один обязательный месяц"
                    monthLabelsDuplicated(months) -> planError = "Месяцы не должны повторяться"
                    editing.any { it.required && it.amount.isBlank() } -> planError = "Укажите сумму для каждого обязательного месяца"
                    else -> onSave(months)
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Сохраняю…" else "Сохранить")
        }
    }
}

private fun monthLabelsDuplicated(months: List<MonthPlan>): Boolean {
    val labels = months.map { it.label() }
    return labels.size != labels.toSet().size
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberPanel(
    group: GroupData,
    busy: Boolean,
    onClose: () -> Unit,
    onAdd: (name: String, birthday: String) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var birthdayMillis by remember { mutableStateOf<Long?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    val nowMillis = remember { System.currentTimeMillis() }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = birthdayMillis,
        yearRange = 1920..java.time.LocalDate.now().year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in MIN_BIRTHDAY_MILLIS..nowMillis

            override fun isSelectableYear(year: Int): Boolean = year in 1920..java.time.LocalDate.now().year
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Новый участник",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Закрыть")
            }
        }
        Text(
            "Взносы начисляются с текущего месяца. Прошлые месяцы, расходы и сборы на новичка не распространяются.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                addError = null
            },
            label = { Text("Имя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = birthdayMillis?.let(::cardDateShortLabel) ?: "",
            onValueChange = {},
            label = { Text("День рождения") },
            placeholder = { Text("не указывать") },
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Cake, "Выбрать дату")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPicker = true },
        )
        addError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val normalized = nameInput.trim()
                when {
                    normalized.isBlank() -> addError = "Укажите имя"
                    group.members.any { it.name.equals(normalized, ignoreCase = true) } ->
                        addError = "Участник с именем «$normalized» уже есть"
                    else -> onAdd(normalized, birthdayMillis?.let(::cardFullDateString) ?: "")
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Добавляю…" else "Добавить")
        }
    }

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        birthdayMillis = pickerState.selectedDateMillis
                        showPicker = false
                    },
                ) { Text("ОК") }
            },
            dismissButton = {
                TextButton(onClick = {
                    birthdayMillis = null
                    showPicker = false
                }) { Text("Сбросить") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberCard(
    group: GroupData,
    name: String,
    canEdit: Boolean,
    busy: Boolean,
    currency: String,
    balance: Double,
    onClose: () -> Unit,
    onSave: (oldName: String, newName: String, birthday: String) -> Unit,
    onRemove: (String) -> Unit,
    onRequestPayment: (String) -> Unit,
    onRequestRefund: (String) -> Unit,
    onSetOffMonths: (String, List<String>) -> Unit,
) {
    val member = group.members.find { it.name == name } ?: return

    var nameInput by remember(member.name) { mutableStateOf(member.name) }
    var birthdayMillis by remember(member.birthday) { mutableStateOf(birthdayToMillis(member.birthday)) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showOffMonths by remember { mutableStateOf(false) }
    var offSelection by remember(member.name) { mutableStateOf(member.offMonths.toSet()) }
    val nowMillis = remember { System.currentTimeMillis() }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = birthdayMillis,
        yearRange = 1920..java.time.LocalDate.now().year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in MIN_BIRTHDAY_MILLIS..nowMillis

            override fun isSelectableYear(year: Int): Boolean = year in 1920..java.time.LocalDate.now().year
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Карточка участника",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Закрыть")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Person, null)
            Text(
                member.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Статус: ${if (member.active) "Активен" else "Исключён"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "В группе с ${member.joinDate.ifEmpty { "—" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (member.birthdayShort.isNotBlank()) {
                    Text(
                        "День рождения: ${member.birthdayShort}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
val collectionShare = group.collections
                    .filter { it.sharedWithBudget && member.name in it.participants }
                    .sumOf { c -> c.sharePerPerson }
                if (collectionShare > 0) {
                    Text(
                        "Доля по сборам: ${money(collectionShare)} ${currencySymbol(currency)} " +
                            "(входит в «долю расходов»)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (member.offMonths.isNotEmpty()) {
                    Text(
                        "Без взноса: ${member.offMonths.joinToString { monthShortLabel(MonthPlan.of(it)) }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!member.active) {
                    Text(
                        "Возврат: ${money(member.refund ?: 0.0)} ${currencySymbol(currency)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (canEdit && member.active) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            saveError = null
                        },
                        label = { Text("Имя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = birthdayMillis?.let(::cardDateShortLabel) ?: "",
                        onValueChange = {},
                        label = { Text("День рождения") },
                        placeholder = { Text("не указан") },
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showPicker = true }) {
                                Icon(Icons.Default.Cake, "Выбрать дату")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPicker = true },
                    )
                    saveError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (saveError == null && nameInput.trim() != member.name) {
                        Text(
                            "При переименовании история платежей и расходов перенесётся на новое имя.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Button(
                onClick = { onRequestPayment(member.name) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+ Внести деньги")
            }

            if (canEdit && balance > 0) {
                Button(
                    onClick = { onRequestRefund(member.name) },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("− Возврат средств")
                }
            }

            Button(
                onClick = {
                    offSelection = member.offMonths.toSet()
                    showOffMonths = true
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Месяцы без оплаты" +
                        (if (member.offMonths.isNotEmpty()) " (${member.offMonths.size})" else ""),
                )
            }

            Button(
                onClick = {
                    val normalized = nameInput.trim()
                    when {
                        normalized.isBlank() -> saveError = "Укажите имя"
                        normalized != member.name &&
                            group.members.any { it.name.equals(normalized, ignoreCase = true) } ->
                            saveError = "Участник с именем «$normalized» уже есть"
                        else -> onSave(member.name, normalized, birthdayMillis?.let(::cardFullDateString) ?: "")
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Сохраняю…" else "Сохранить")
            }

            TextButton(
                onClick = { onRemove(member.name) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Исключить участника", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { birthdayMillis = it }
                        showPicker = false
                    },
                ) {
                    Text("ОК")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Отмена")
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showOffMonths) {
        val req = group.months.filter { it.required }
        AlertDialog(
            onDismissRequest = { showOffMonths = false },
            title = { Text("Месяцы без оплаты") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "В выбранные месяцы участник освобождён от взноса " +
                            "(например, на время отъезда).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        req.forEach { mp ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        offSelection = if (mp.label() in offSelection) {
                                            offSelection - mp.label()
                                        } else {
                                            offSelection + mp.label()
                                        }
                                    },
                            ) {
                                Checkbox(
                                    checked = mp.label() in offSelection,
                                    onCheckedChange = { checked ->
                                        offSelection = if (checked) offSelection + mp.label()
                                        else offSelection - mp.label()
                                    },
                                )
                                Text(monthShortLabel(mp.month))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetOffMonths(member.name, offSelection.toList())
                        showOffMonths = false
                    },
                    enabled = !busy,
                ) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOffMonths = false }) { Text("Отмена") }
            },
        )
    }
}

/** Миллис (UTC) из хранимой даты "гггг-мм-дд" (или "дд.мм"). */
private fun birthdayToMillis(birthday: String): Long? {
    val m = Regex("""^\d{4}[-.]\d{2}[-.]\d{2}$""").find(birthday) ?: return null
    val parts = m.value.split('-', '.')
    return runCatching {
        java.time.LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
}

/** "ДД.ММ" из UTC-миллис выбранной даты. */
private fun cardDateShortLabel(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "%02d.%02d".format(d.dayOfMonth, d.monthValue)
}

/** Полная дата "гггг-мм-дд" из UTC-миллис DatePicker. */
private fun cardFullDateString(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
}

/** 01.01.1920 UTC — самая ранняя выбираемая дата рождения. */
private val MIN_BIRTHDAY_MILLIS: Long =
    java.time.LocalDate.of(1920, 1, 1)
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

@Composable
private fun StandaloneCollectionsBlock(
    collections: List<CollectionData>,
    currency: String,
    expandedNames: Set<String>,
    onToggle: (String) -> Unit,
) {
    val standalone = collections.filter {
        !it.sharedWithBudget &&
            it.participants.any { p -> (it.contributed(p)) < it.sharePerPerson - 0.005 }
    }
    if (standalone.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
Text(
                "Отдельные сборы (вне общего бюджета)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            standalone.forEach { c ->
                StandaloneCollectionRow(
                    c = c,
                    currency = currency,
                    expanded = c.name in expandedNames,
                    onToggle = { onToggle(c.name) },
                )
            }
        }
    }
}

@Composable
private fun StandaloneCollectionRow(
    c: CollectionData,
    currency: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val collected = c.participants.sumOf { c.countedContribution(it) }
    val need = c.participants.size * c.sharePerPerson
    val fraction = if (need > 0) (collected / need).coerceIn(0.0, 1.0) else 0.0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                				Box(
					modifier = Modifier
						.size(40.dp)
						.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Text(
						"%",
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.Bold,
					)
				}
				Spacer(modifier = Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Сдано ${money(collected)} из ${money(need)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                c.participants.forEach { p ->
                    val paid = (c.contributed(p)) >= c.sharePerPerson - 0.005
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (paid) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (paid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(p, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            money(if (paid) c.sharePerPerson else c.contributed(p)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { fraction.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Сдано ${c.participants.count { (c.contributed(it)) >= c.sharePerPerson - 0.005 }} из ${c.participants.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CompactSummary(summary: GroupSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell("Участник", NAME_CELL_W, bold = true, header = true)
                Cell("Баланс", CELL_W, bold = true, header = true)
                Cell("Оплачено до", SUM_PAID_UNTIL_W, bold = true, header = true)
            }
            summary.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cell(r.member.name, NAME_CELL_W)
                    Cell(
                        if (r.balance >= 0) "+${money(r.balance)}" else "-${money(-r.balance)}",
                        CELL_W,
                        background = if (r.balance < -0.005) TILE_DEBT else null,
                        color = if (r.balance < -0.005) TILE_TEXT else Color.Unspecified,
                    )
                    Cell(
                        r.paidThrough?.let {
                            "до ${monthShortLabel(it)} (${r.fullyPaidRequiredCount}/${summary.requiredMonths.size})"
                        } ?: "—",
                        SUM_PAID_UNTIL_W,
                    )
                }
            }
        }
    }
}

private val SUM_PAID_UNTIL_W = 140.dp

private val CELL_W = 64.dp
private val MONTH_CELL_W = 56.dp
private val NAME_CELL_W = 140.dp

// Цвета «плитки» — те же, что и на листе «Сводка».
private val TILE_GOOD = Color(0xFFC6EFCE)
private val TILE_WARN = Color(0xFFFFEB9C)
private val TILE_NEUTRAL = Color(0xFFD9D9D9)
private val TILE_DEBT = Color(0xFFFFC7CE)
private val TILE_TEXT = Color(0xFF1F1F1F)

/** Цвет ячейки месяца: сдан — зелёный, не сдан — жёлтый, текущий месяц не сдан — красный, без обязанности — серый. */
private fun monthTile(mp: MonthPlan, r: com.groupfund.app.data.sheets.MemberSummary, monthIdx: Int): Color =
    when {
        monthIdx !in r.requiredMonthIndices -> TILE_NEUTRAL
        r.perMonth[monthIdx] >= mp.amount - 0.005 -> TILE_GOOD
        mp.month == java.time.YearMonth.now() -> TILE_DEBT
        else -> TILE_WARN
    }

@Composable
private fun PaymentTable(summary: GroupSummary) {
    val hScroll = rememberScrollState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(hScroll),
        ) {
            val months = summary.months
            val columns = summary.requiredIndices
            val totalsBase = months.size
            // шапка: Участник | мес(только обязательные)… | Внесено | Сборы | Расходы | Баланс
            Row {
                Cell("Участник", NAME_CELL_W, bold = true, header = true)
                columns.forEach { c -> Cell(monthShortLabel(months[c].month), MONTH_CELL_W, header = true) }
                Cell("Внесено", CELL_W, bold = true, header = true)
                Cell("Сборы", CELL_W, bold = true, header = true)
                Cell("Расходы", CELL_W, bold = true, header = true)
                Cell("Баланс", CELL_W, bold = true, header = true)
                Cell("Оплачено до", 96.dp, bold = true, header = true)
            }
            summary.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cell(r.member.name, NAME_CELL_W, bold = false)
                    columns.forEach { c ->
                        Cell(
                            if (r.perMonth[c] > 0) money(r.perMonth[c]) else "",
                            MONTH_CELL_W,
                            background = monthTile(months[c], r, c),
                            color = TILE_TEXT,
                        )
                    }
                    Cell(money(r.totalPaid), CELL_W)
                    Cell(money(r.collectionShare), CELL_W)
                    Cell(money(r.expenseShare), CELL_W)
                    Cell(
                        money(r.balance),
                        CELL_W,
                        background = if (r.balance < -0.005) TILE_DEBT else null,
                        color = if (r.balance < -0.005) TILE_TEXT else Color.Unspecified,
                    )
                    Cell(
                        r.paidThrough?.let {
                            "до ${monthShortLabel(it)} (${r.fullyPaidRequiredCount}/${summary.requiredMonths.size})"
                        } ?: "—",
                        96.dp,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell("Итого", NAME_CELL_W, bold = true)
                columns.forEach { c ->
                    Cell(money(summary.totals[c]), MONTH_CELL_W, bold = true)
                }
                Cell(money(summary.totals[totalsBase]), CELL_W, bold = true)
                Cell(money(summary.totals[totalsBase + 1]), CELL_W, bold = true)
                Cell(money(summary.totals[totalsBase + 2]), CELL_W, bold = true)
                Cell(money(summary.totals[totalsBase + 3]), CELL_W, bold = true)
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false,
    header: Boolean = false,
    background: Color? = null,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold || header) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(background ?: Color.Transparent)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .width(width),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EditPaymentDialog(
    group: GroupData,
    index: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (member: String, amount: Double, comment: String) -> Unit,
) {
    val payment = group.payments.getOrNull(index) ?: return
    val members = group.activeMembers.sortedBy { it.name.lowercase() }
    var memberIdx by remember(index) { mutableIntStateOf(members.indexOfFirst { it.name == payment.member }) }
    var amount by remember(index) { mutableStateOf(money(payment.amount)) }
    var comment by remember(index) { mutableStateOf(payment.comment) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить платёж") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (members.isEmpty()) {
                    Text("Нет активных участников")
                } else {
                    Text(
                        "Кому (${members.size})",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        members.forEachIndexed { i, m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { memberIdx = i },
                            ) {
                                RadioButton(
                                    selected = memberIdx == i,
                                    onClick = { memberIdx = i },
                                )
                                Text(m.name)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = filterAmount(it) },
                    label = { Text("Сумма, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val value = amount.toDoubleOrNull()
                    val member = members.getOrNull(memberIdx)
                    when {
                        member == null -> error = "Выберите участника"
                        value == null || value <= 0 -> error = "Укажите сумму"
                        else -> onSave(member.name, value, comment.trim())
                    }
                },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun EditExpenseDialog(
    group: GroupData,
    index: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (description: String, amount: Double, participants: List<String>) -> Unit,
) {
    val expense = group.expenses.getOrNull(index) ?: return
    val active = group.activeMembers.map { it.name }
    var description by remember(index) { mutableStateOf(expense.description) }
    var amount by remember(index) { mutableStateOf(money(expense.amount)) }
    var participants by remember(index) { mutableStateOf(expense.participants.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить расход") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("За что (описание)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = filterAmount(it) },
                    label = { Text("Сумма, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "На кого распределить (круг фиксируется сейчас):",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (active.isEmpty()) {
                    Text("Нет активных участников")
                } else {
                    Text(
                        "Всего участников: ${active.size}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        active.sorted().forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        participants =
                                            if (name in participants) participants - name
                                            else participants + name
                                    },
                            ) {
                                Checkbox(
                                    checked = name in participants,
                                    onCheckedChange = {
                                        participants =
                                            if (it) participants + name
                                            else participants - name
                                    },
                                )
                                Text(name)
                            }
                        }
                    }
                }
                Text(
                    if (participants.isEmpty()) {
                        "Никто не выбран — расход учтётся на всех активных"
                    } else {
                        "Доля на каждого: ${money((amount.toDoubleOrNull() ?: 0.0) / participants.size)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val value = amount.toDoubleOrNull()
                    val circle = participants.ifEmpty {
                        group.activeMembers.map { it.name }.toSet()
                    }
                    when {
                        description.isBlank() -> error = "Укажите, за что расход"
                        value == null || value <= 0 -> error = "Укажите сумму"
                        else -> onSave(description.trim(), value, circle.toList())
                    }
                },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}