package com.groupfund.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groupfund.app.BuildConfig
import com.groupfund.app.R
import com.groupfund.app.data.registry.GroupEntry

/** Группа с уже «разведённым» по дублям отображаемым названием. */
private data class DisplayGroup(val entry: GroupEntry, val label: String)

/** К названию дубликата добавляет « (1)», « (2)» и т.д., первую копию не трогает. */
private fun withUniqueLabels(groups: List<GroupEntry>): List<DisplayGroup> {
    val base = { e: GroupEntry -> (e.localTitle ?: e.title).trim() }
    val counts = groups.map(base).groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return groups.map { e ->
        val name = base(e)
        val idx = seen[name] ?: 0
        seen[name] = idx + 1
        val label = if ((counts[name] ?: 1) > 1 && idx > 0) "$name ($idx)" else name
        DisplayGroup(e, label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onCreateGroup: () -> Unit,
    onOpenGroup: (String) -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showAccountDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GroupEntry?>(null) }
    var pendingRename by remember { mutableStateOf<GroupEntry?>(null) }
    var showUnavailableHint by remember { mutableStateOf(false) }

    LaunchedEffect(state.inviteJoined) {
        state.inviteJoined?.let { id ->
            onOpenGroup(id)
            viewModel.consumeInviteJoined()
        }
    }

    val context = LocalContext.current

    LaunchedEffect(showImportDialog) {
        if (showImportDialog) viewModel.discoverFromDrive()
    }

    val accountsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refreshAvailableAccounts()
    }

    val openSignIn: () -> Unit = { showAccountDialog = true }

    if (showAccountDialog) {
        LaunchedEffect(Unit) {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                accountsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                viewModel.refreshAvailableAccounts()
            }
        }
        AccountChooserDialog(
            accounts = state.accounts,
            loading = state.accountsLoading,
            onRefresh = viewModel::refreshAvailableAccounts,
            onSelect = { email ->
                viewModel.selectAccount(email)
                showAccountDialog = false
            },
            onDismiss = { showAccountDialog = false },
        )
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Без выданного согласия повтор бессмысленен и зациклил бы экран согласия.
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryPendingInvite()
        else viewModel.onConsentDenied()
    }

    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let {
            viewModel.consumeConsentIntent()
            consentLauncher.launch(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Default.Info, "О приложении")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.isSignedIn) {
                ExtendedFloatingActionButton(
                    onClick = onCreateGroup,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Новая группа") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !state.isSignedIn -> SignInBlock(onSignInClick = openSignIn)
                else -> GroupsContent(
                    state = state,
                    viewModel = viewModel,
                    onSignInClick = openSignIn,
                    onOpenGroup = onOpenGroup,
                    onDeleteRequest = { pendingDelete = it },
                    onRenameRequest = { pendingRename = it },
                    onUnavailableClick = { showUnavailableHint = true },
                    onImportDrive = { showImportDialog = true },
                )
            }

            if (showImportDialog) {
                ImportGroupsDialog(
                    discovering = state.discovering,
                    discovered = state.discovered,
                    onRetry = viewModel::discoverFromDrive,
                    onImport = { selected ->
                        viewModel.importDiscoveredGroups(selected)
                        showImportDialog = false
                    },
                    onDismiss = { showImportDialog = false },
                )
            }

            // Подтверждение удаления группы из списка
            pendingDelete?.let { entry ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("Удалить группу?") },
                    text = {
                        Text(
                            if (entry.role == "creator") {
                                "«${entry.title}» будет удалена из приложения " +
                                    "и из вашего Google Диска. Таблица удалится безвозвратно."
                            } else {
                                "«${entry.title}» будет удалена из вашего списка групп."
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteGroup(entry)
                                pendingDelete = null
                            },
                        ) {
                            Text("Удалить")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) {
                            Text("Отмена")
                        }
                    },
                )
            }

            if (showUnavailableHint) {
                AlertDialog(
                    onDismissRequest = { showUnavailableHint = false },
                    title = { Text("Группа недоступна") },
                    text = { Text("Группа удалена или создатель ограничил к ней доступ.") },
                    confirmButton = {
                        TextButton(onClick = { showUnavailableHint = false }) {
                            Text("Понятно")
                        }
                    },
                )
            }

            val renameTarget = pendingRename
            if (renameTarget != null) {
                RenameGroupDialog(
                    entry = renameTarget,
                    onDismiss = { pendingRename = null },
                    onRename = { newTitle ->
                        viewModel.renameGroup(renameTarget.id, newTitle)
                        pendingRename = null
                    },
                )
            }

            if (showInfoDialog) {
                AboutDialog(onDismiss = { showInfoDialog = false })
            }
        }
    }
}

@Composable
private fun RenameGroupDialog(
    entry: GroupEntry,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(entry.id) { mutableStateOf(entry.localTitle ?: entry.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать группу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Название изменится только у вас в списке. Таблица на Диске останется прежней.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name.trim()) },
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val releasesUrl = "https://github.com/CheshireBat88/table_visualiser/releases"
    val email = "ch3shirsky@gmail.com"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("О приложении") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("GroupFund", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Приложение ведёт учёт общих расходов в Google Таблицах.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(releasesUrl)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Проверить обновления на GitHub")
                }
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Написать разработчику")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
private fun SignInBlock(onSignInClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Войдите в Google-аккаунт,\nчтобы создавать и видеть группы",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignInClick) {
            Text("Войти через Google")
        }
    }
}

@Composable
private fun GroupsContent(
    state: MainUiState,
    viewModel: MainViewModel,
    onSignInClick: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onDeleteRequest: (GroupEntry) -> Unit,
    onRenameRequest: (GroupEntry) -> Unit,
    onUnavailableClick: () -> Unit,
    onImportDrive: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.email.isNullOrBlank()) {
                "Аккаунт: не определён"
            } else {
                "Вы вошли как: ${state.email}"
            },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSignInClick) {
            Text("Сменить")
        }
        TextButton(onClick = { viewModel.signOut() }) {
            Text("Выйти")
        }
    }
    Spacer(Modifier.height(8.dp))

    if (state.groups.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Групп пока нет.\nНажмите «Новая группа»\nи соберите первую" +
                    (state.email?.let { "\nили поищите уже созданные на Диске" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onImportDrive) {
                Text("Поискать на Диске")
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Мои группы:",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onImportDrive) {
                Text("Из Диска")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(withUniqueLabels(state.groups), key = { it.entry.id }) { dg ->
                GroupCard(
                    entry = dg.entry,
                    label = dg.label,
                    unavailable = dg.entry.unavailable,
                    onClick = {
                        if (dg.entry.unavailable) onUnavailableClick()
                        else onOpenGroup(dg.entry.id)
                    },
                    onRename = { onRenameRequest(dg.entry) },
                    onDelete = { onDeleteRequest(dg.entry) },
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    entry: GroupEntry,
    label: String,
    unavailable: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (unavailable) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (unavailable) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Группа удалена или ограничен доступ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Text(
                        entry.spreadsheetUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (entry.role) {
                            "creator" -> "Создатель"
                            "observer" -> "Наблюдатель"
                            else -> "Участник"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Действия с группой")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Переименовать") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountChooserDialog(
    accounts: List<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var manualEmail by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вход в Google") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Аккаунты на устройстве:", style = MaterialTheme.typography.bodyMedium)
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .height(40.dp),
                        strokeWidth = 3.dp,
                    )
                    accounts.isEmpty() -> {
                        Text(
                            "Аккаунты не видны. Обычно система сама предлагает разрешить доступ; " +
                                "если этого не случилось — нажмите «Обновить» или введите email вручную.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRefresh) {
                            Text("Обновить список")
                        }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        accounts.forEach { email ->
                            Button(
                                onClick = { onSelect(email) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text(
                    "или введите email Google-аккаунта вручную:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = manualEmail,
                    onValueChange = { manualEmail = it.trim() },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSelect(manualEmail) },
                    enabled = manualEmail.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Войти с этим email")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun ImportGroupsDialog(
    discovering: Boolean,
    discovered: List<GroupEntry>,
    onRetry: () -> Unit,
    onImport: (List<GroupEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(discovered) {
        mutableStateOf(discovered.map { it.spreadsheetId }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поиск групп на Диске") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    discovering -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(40.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Ищем таблицы…")
                    }
                    discovered.isEmpty() -> {
                        Text(
                            "Ничего не найдено. Проверьте, что группы создавались " +
                                "этим же аккаунтом, и поищите снова.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRetry) {
                            Text("Искать снова")
                        }
                    }
                    else -> {
                        Text(
                            "Найдено: ${discovered.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        discovered.forEach { group ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = group.spreadsheetId in selection,
                                    onCheckedChange = { checked ->
                                        selection = if (checked) {
                                            selection + group.spreadsheetId
                                        } else {
                                            selection - group.spreadsheetId
                                        }
                                    },
                                )
                                Text(group.title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!discovering && discovered.isNotEmpty()) {
                TextButton(
                    enabled = selection.isNotEmpty(),
                    onClick = {
                        onImport(discovered.filter { it.spreadsheetId in selection })
                    },
                ) {
                    Text("Импортировать")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}