package com.groupfund.app.data.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

sealed interface TokenResult {
    data class Success(val token: String) : TokenResult
    data class ConsentRequired(val intent: Intent) : TokenResult
    data class Error(val message: String, val cause: Throwable? = null) : TokenResult
}

class OAuthConsentRequiredException(val intent: Intent) : Exception("Требуется согласие на доступ к Google Таблицам")

/**
 * Работа с Google-аккаунтом без капризного кэша GoogleSignIn:
 *
 *  - выбор аккаунта — системный AccountPicker (всегда показывает список и
 *    возвращает email напрямую из AccountManager);
 *  - OAuth-токен — GoogleAuthUtil.getToken(account, oauth2:...scope);
 *  - согласие на доступ к таблицам/файлам Google покажет сам через
 *    UserRecoverableAuthException, а мы перезапустим его intent.
 */
class GoogleAuthManager(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("groupfund_auth", Context.MODE_PRIVATE)
    private val emailKey = "google_email"

    private val accountManager: AccountManager by lazy { AccountManager.get(appContext) }

    /** Google-аккаунты, доступные на устройстве (синхронно, видимые приложению). */
    fun deviceGoogleAccounts(): List<Account> =
        accountManager.getAccountsByType("com.google").toList()

    /**
     * Асинхронное получение списка аккаунтов. У `addOnAccountsUpdatedListener(...,
     * updateImmediately = true)` есть важное свойство: при первом вызове система сама
     * показывает надстройку «X хочет получить доступ к вашим аккаунтам» и после согласия
     * начинает отдавать аккаунты. Именно эта грант-карточка слетает при переустановке
     * приложения, из-за чего getAccountsByType возвращает пусто.
     */
    suspend fun deviceGoogleAccountsSuspend(): List<Account> =
        suspendCancellableCoroutine { cont ->
            lateinit var listener: OnAccountsUpdateListener
            val mainHandler = Handler(Looper.getMainLooper())
            val fallbackPost = Runnable {
                accountManager.removeOnAccountsUpdatedListener(listener)
                cont.resumeSafe { deviceGoogleAccounts().sortedBy { it.name } }
            }

            listener = OnAccountsUpdateListener { accounts ->
                accountManager.removeOnAccountsUpdatedListener(listener)
                mainHandler.removeCallbacks(fallbackPost)
                cont.resumeSafe {
                    accounts
                        .filter { it != null && it.type == "com.google" }
                        .mapNotNull { it }
                        .sortedBy { it.name }
                }
            }

            cont.invokeOnCancellation { cause ->
                accountManager.removeOnAccountsUpdatedListener(listener)
                mainHandler.removeCallbacks(fallbackPost)
            }

            mainHandler.post {
                try {
                    accountManager.addOnAccountsUpdatedListener(listener, mainHandler, true)
                    // Если системный промпт не показывается — падаем на синхронном варианте.
                    mainHandler.postDelayed(fallbackPost, 4000)
                } catch (e: Exception) {
                    cont.resumeSafe { deviceGoogleAccounts().sortedBy { it.name } }
                }
            }
        }

    private fun <T> kotlinx.coroutines.CancellableContinuation<T>.resumeSafe(block: () -> T) {
        if (isActive) resume(block(), null)
    }

    /** Email, сохранённый при последнем успешном входе. */
    fun storedEmail(): String? = prefs.getString(emailKey, null)

    fun rememberSignIn(email: String) {
        prefs.edit().putString(emailKey, email).apply()
    }

    fun isSignedIn(): Boolean = storedEmail() != null

    fun signOut() {
        prefs.edit().remove(emailKey).apply()
    }

    suspend fun accessToken(): TokenResult = withContext(Dispatchers.IO) { requestToken() }

    /**
     * Сбрасывает закэшированный GoogleAuthUtil токен. Нужно при 401: getToken без
     * инвалидации вернул бы тот же протухший токен, и повтор снова упал бы.
     */
    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.invalidateToken(appContext, token) }
        Unit
    }

    private fun requestToken(): TokenResult {
        val email = storedEmail()
            ?: return TokenResult.Error("Пользователь не вошёл в Google. Нажмите «Войти».")
        val account = deviceAccount(email) ?: Account(email, "com.google")
        return try {
            val token = GoogleAuthUtil.getToken(
                appContext,
                account,
                "oauth2:$SHEETS_SCOPE $DRIVE_FILE_SCOPE"
            )
            TokenResult.Success(token)
        } catch (e: UserRecoverableAuthException) {
            e.intent?.let { return TokenResult.ConsentRequired(it) }
            TokenResult.Error(e.message ?: "Требуется согласие на доступ к таблицам", e)
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: e.javaClass.simpleName, e)
        }
    }

    /** Ищет аккаунт на устройстве по email (а не строит из строки). */
    private fun deviceAccount(email: String): Account? =
        AccountManager.get(appContext)
            .getAccountsByType("com.google")
            .firstOrNull { it.name.equals(email, ignoreCase = true) }
}