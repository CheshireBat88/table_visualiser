package com.groupfund.app.data.registry

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.groupDataStore by preferencesDataStore(
    name = "group_registry",
)

/**
 * Локальный реестр групп на устройстве (сохраняется между запусками).
 * Список групп хранится в виде JSON-строки в DataStore.
 */
class GroupRegistry(private val context: Context) {

    private val gson = Gson()
    private val groupsKey = stringPreferencesKey("groups")
    private val notifiedKey = stringPreferencesKey("notified_birthdays")
    private val memberSelectionKey = stringPreferencesKey("member_selection")

    val groups: Flow<List<GroupEntry>> = context.groupDataStore.data.map { prefs ->
        prefs[groupsKey]?.let { deserialize(it) } ?: emptyList()
    }

    /** Добавляет группу; если она уже есть (по spreadsheetId) — не дублирует. */
    suspend fun addGroup(entry: GroupEntry) {
        context.groupDataStore.edit { prefs ->
            val current = prefs[groupsKey]?.let { deserialize(it) } ?: emptyList()
            if (current.any { it.spreadsheetId == entry.spreadsheetId }) return@edit
            prefs[groupsKey] = serialize(current + entry)
        }
    }

    suspend fun removeGroup(id: String) {
        context.groupDataStore.edit { prefs ->
            val current = prefs[groupsKey]?.let { deserialize(it) } ?: emptyList()
            prefs[groupsKey] = serialize(current.filterNot { it.id == id })
        }
    }

    suspend fun groupById(id: String): GroupEntry =
        groups.first().first { it.id == id }

    suspend fun findBySpreadsheetId(spreadsheetId: String): GroupEntry? =
        groups.first().firstOrNull { it.spreadsheetId == spreadsheetId }

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