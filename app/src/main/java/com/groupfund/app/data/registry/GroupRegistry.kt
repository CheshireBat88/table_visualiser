package com.groupfund.app.data.registry

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

private val Context.groupDataStore by preferencesDataStore(
    name = "group_registry",
)

/**
 * Локальный реестр групп на устройстве (сохраняется между запусками).
 * Список групп хранится в виде JSON-строки в DataStore и разбит по аккаунтам:
 * у каждого email-аккаунта собственный список, включённые через [bindAccount]
 * группы показываются только ему.
 */
class GroupRegistry(private val context: Context) {

    private val gson = Gson()

    /** До поаккаунтного разделения список лежал в одном ключе — под этим именем. */
    private val legacyGroupsKey = stringPreferencesKey("groups")

    private fun groupsKey(account: String): Preferences.Key<String> = stringPreferencesKey("groups_$account")

    private val notifiedKey = stringPreferencesKey("notified_birthdays")
    private val memberSelectionKey = stringPreferencesKey("member_selection")

    /** Активный аккаунт (email): список групп читается/пишется только для него. */
    private val currentAccount = MutableStateFlow<String?>(null)

    /** Привязывает реестр к аккаунту: меняется при входе и смене аккаунта. */
    fun bindAccount(account: String?) {
        currentAccount.value = account
    }

    /**
     * Переносит до-поаккаунтовые данные в личный список текущего аккаунта:
     * старый единый список принадлежит последнему активному аккаунту.
     */
    suspend fun migrateLegacy(account: String) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val legacy = prefs[legacyGroupsKey] ?: return@edit
            if (prefs[groupsKey(account)] == null) {
                prefs[groupsKey(account)] = legacy
                prefs.remove(legacyGroupsKey)
            }
        }
    }

    /** Актуальный список групп активного аккаунта (для UI). */
    val groups: Flow<List<GroupEntry>> =
        combine(currentAccount, context.groupDataStore.data) { account, prefs ->
            account?.takeIf { it.isNotBlank() }?.let { g -> prefs[groupsKey(g)]?.let(::deserialize) } ?: emptyList()
        }

    /** Список групп конкретного аккаунта (для фоновых проверок вне UI). */
    suspend fun groupsFor(account: String): List<GroupEntry> {
        if (account.isBlank()) return emptyList()
        val prefs = context.groupDataStore.data.first()
        return prefs[groupsKey(account)]?.let(::deserialize) ?: emptyList()
    }

    /** Добавляет группу; если она уже есть (по spreadsheetId) — не дублирует. */
    suspend fun addGroup(account: String, entry: GroupEntry) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: emptyList()
            if (current.any { it.spreadsheetId == entry.spreadsheetId }) return@edit
            prefs[key] = serialize(current + entry)
        }
    }

    suspend fun removeGroup(account: String, id: String) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(current.filterNot { it.id == id })
        }
    }

    suspend fun groupById(account: String, id: String): GroupEntry =
        groupsFor(account).first { it.id == id }

    suspend fun findBySpreadsheetId(account: String, spreadsheetId: String): GroupEntry? =
        groupsFor(account).firstOrNull { it.spreadsheetId == spreadsheetId }

    /** Переименовывает группу локально («как отображается у пользователя»), не трогая таблицу. */
    suspend fun renameGroup(account: String, id: String, newTitle: String) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(
                current.map { entry ->
                    if (entry.id == id) entry.copy(localTitle = newTitle.trim().takeIf { it.isNotBlank() })
                    else entry
                },
            )
        }
    }

    /**
     * Создатель переименовал сам файл на Drive: настоящее имя таблицы меняется,
     * локальное переопределение (localTitle) убираем, чтобы отображалось новое имя.
     */
    suspend fun renameGroup(account: String, id: String, newTitle: String, sheetRenamed: Boolean) {
        val name = newTitle.trim().takeIf { it.isNotBlank() } ?: return
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(
                current.map { entry ->
                    if (entry.id == id) {
                        if (sheetRenamed) entry.copy(title = name, localTitle = null)
                        else entry.copy(localTitle = name)
                    } else entry
                },
            )
        }
    }

    /** Помечает/снимает пометку «группа недоступна» (удалена или ограничен доступ). */
    suspend fun setUnavailable(account: String, id: String, unavailable: Boolean) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(
                current.map { entry -> if (entry.id == id) entry.copy(unavailable = unavailable) else entry },
            )
        }
    }

    /** Отмечает, что стилизация «Сводки» в таблице уже применена один раз. */
    suspend fun markSummaryStyled(account: String, id: String, styled: Boolean) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(
                current.map { entry -> if (entry.id == id) entry.copy(summaryStyled = styled) else entry },
            )
        }
    }

    /**
     * Меняет запись группы по старому spreadsheetId (копия при передаче, авто-переключение).
     * Если группы с таким id нет — просто ничего не меняет (пустая операция).
     */
    suspend fun replaceSpreadsheet(account: String, oldSpreadsheetId: String, transform: (GroupEntry) -> GroupEntry) {
        if (account.isBlank()) return
        context.groupDataStore.edit { prefs ->
            val key = groupsKey(account)
            val current = prefs[key]?.let(::deserialize) ?: return@edit
            prefs[key] = serialize(
                current.map { entry ->
                    if (entry.spreadsheetId == oldSpreadsheetId) transform(entry) else entry
                },
            )
        }
    }

    // ---------- Выбор наблюдателя в группе ----------

    /**
     * Имя участника, которого этот аккаунт выбрал как «это я» в конкретной группе.
     * Хранится локально и независимо для каждой группы (email + spreadsheetId).
     */
    suspend fun selectedMember(email: String, spreadsheetId: String): String? =
        selectionMap(context.groupDataStore.data.first())[selectionKey(email, spreadsheetId)]

    suspend fun saveSelectedMember(email: String, spreadsheetId: String, member: String) {
        context.groupDataStore.edit { prefs ->
            prefs[memberSelectionKey] =
                gson.toJson(selectionMap(prefs) + (selectionKey(email, spreadsheetId) to member))
        }
    }

    suspend fun clearSelectedMember(email: String, spreadsheetId: String) {
        context.groupDataStore.edit { prefs ->
            prefs[memberSelectionKey] = gson.toJson(selectionMap(prefs) - selectionKey(email, spreadsheetId))
        }
    }

    private fun selectionKey(email: String, spreadsheetId: String): String = "$email|$spreadsheetId"

    private fun selectionMap(prefs: Preferences): Map<String, String> {
        val raw = prefs[memberSelectionKey] ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, String>>(raw, object : TypeToken<Map<String, String>>() {}.type)
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ---------- Уведомления о днях рождения ----------

    /** Ключи "$groupId|$name|$мм.дд|$год|$шаг", по которым уже отправили уведомление. */
    suspend fun birthdayNotifiedKeys(): Set<String> {
        val prefs = context.groupDataStore.data.first()
        return prefs[notifiedKey]?.let(::deserializeKeys) ?: emptySet()
    }

    suspend fun markBirthdayNotified(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.groupDataStore.edit { prefs ->
            val current = prefs[notifiedKey]?.let(::deserializeKeys) ?: emptySet()
            prefs[notifiedKey] = gson.toJson(current + keys)
        }
    }

    private fun deserializeKeys(raw: String): Set<String> =
        try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(raw, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }

    private fun serialize(list: List<GroupEntry>): String = gson.toJson(list)

    private fun deserialize(raw: String): List<GroupEntry> =
        try {
            val type = object : TypeToken<List<GroupEntry>>() {}.type
            gson.fromJson<List<GroupEntry>>(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
}