package com.groupfund.app

import android.Manifest
import android.content.Intent
import com.groupfund.app.BuildConfig
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.groupfund.app.notifications.BirthdayCheck
import com.groupfund.app.notifications.BirthdayNotifier
import com.groupfund.app.ui.MainScreen
import com.groupfund.app.ui.MainViewModel
import com.groupfund.app.ui.create.CreateGroupScreen
import com.groupfund.app.ui.detail.GroupDetailScreen
import com.groupfund.app.ui.theme.GroupFundTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** SpreadsheetId из deep-link `groupfund://invite/<id>` или `https://cheshirebat88.github.io/invite/<id>`. */
    private val inviteId = mutableStateOf<String?>(null)

    /** Ссылка передачи (`/transfer/<id>?code=…` в https-форме) из intent, если пришли по ней. */
    private val transferUri = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inviteId.value = parseInviteUri(intent?.data)
        transferUri.value = parseTransferUri(intent?.data)
        CrashLogger.install(this)

        BirthdayNotifier.schedule(this)
        requestNotificationPermissionIfNeeded()
        launchBirthdayCheckOnResume()

        setContent {
            GroupFundTheme {
                AppNav(
                    deepLinkInviteId = inviteId.value,
                    deepLinkTransferUri = transferUri.value,
                )
                LaunchedEffect(Unit) {
                    CrashLogger.lastCrash(applicationContext)?.let { report ->
                        CrashLogger.clear(applicationContext)
                        val summary = report.lineSequence().take(3).joinToString("\n")
                        Log.e("GroupFundCrash", "Прошлый запуск упал:\n$report")
                        if (BuildConfig.DEBUG) {
                            Toast.makeText(
                                this@MainActivity,
                                "Прошлый запуск упал:\n$summary",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /** Приложение уже открыто и сканер достучался до него повторно. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteId.value = parseInviteUri(intent.data)
        transferUri.value = parseTransferUri(intent.data)
    }

    private fun parseInviteUri(data: Uri?): String? {
        if (data == null) return null
        return when (data.scheme) {
            "groupfund" ->
                if (data.host == "invite") data.lastPathSegment
                else null
            "https" ->
                if (data.host != "cheshirebat88.github.io") null
                else data.path
                    ?.takeIf { it.startsWith("/invite/") }
                    ?.removePrefix("/invite/")
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Transfer-ссылка из intent (`groupfund://transfer/<id>?code=…` или https) в https-виде. */
    private fun parseTransferUri(data: Uri?): String? {
        if (data == null) return null
        val id: String? = when (data.scheme) {
            "groupfund" ->
                if (data.host == "transfer") data.lastPathSegment
                else null
            "https", "http" ->
                if (data.host != "cheshirebat88.github.io") null
                else data.path
                    ?.takeIf { it.startsWith("/transfer/") }
                    ?.removePrefix("/transfer/")
                    ?.trimEnd('/')
            else -> null
        }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val code = data.getQueryParameter("code") ?: return null
        if (code.isEmpty()) return null
        return "https://cheshirebat88.github.io/transfer/$id?code=$code"
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** При каждом открытии приложения (даже кратко) проверяем ДР сразу же. */
    private fun launchBirthdayCheckOnResume() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(8000)
                runCatching { BirthdayCheck.run(this@MainActivity) }
            }
        }
    }
}

@Composable
private fun AppNav(
    deepLinkInviteId: String? = null,
    deepLinkTransferUri: String? = null,
) {
    val nav = rememberNavController()
    val mainVm: MainViewModel = viewModel()

    LaunchedEffect(deepLinkInviteId) {
        if (deepLinkInviteId != null) mainVm.setPendingInvite(deepLinkInviteId)
    }

    LaunchedEffect(deepLinkTransferUri) {
        if (deepLinkTransferUri != null) mainVm.setPendingTransferUri(deepLinkTransferUri)
    }

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            MainScreen(
                onCreateGroup = { nav.navigate("create") },
                onOpenGroup = { id -> nav.navigate("group/$id") },
                viewModel = mainVm,
            )
        }

        composable("create") {
            CreateGroupScreen(
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            route = "group/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
        ) {
            GroupDetailScreen(
                onBack = { nav.popBackStack() },
                onClone = { nav.navigate("create") },
            )
        }
    }
}