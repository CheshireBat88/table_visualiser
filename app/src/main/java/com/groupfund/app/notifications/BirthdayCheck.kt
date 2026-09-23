package com.groupfund.app.notifications

import android.content.Context
import com.groupfund.app.data.auth.GoogleAuthManager
import com.groupfund.app.data.registry.GroupRegistry
import com.groupfund.app.data.sheets.SheetsRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit

/**
 * Проверка дней рождений: уведомление за 3 дня, завтра и в сам день.
 * Вызывается и воркером, и при открытии приложения.
 * Anti-spam — ключи "$groupId|$name|$др|$год|$шаг" в GroupRegistry.
 */
object BirthdayCheck {

    private val leads = intArrayOf(3, 1, 0)

    suspend fun run(context: Context): Boolean {
        val auth = GoogleAuthManager(context)
        if (!auth.isSignedIn()) return false

        val repo = SheetsRepository(auth)
        val registry = GroupRegistry(context)

        val notified = registry.birthdayNotifiedKeys().toMutableSet()
        val newKeys = mutableListOf<String>()
        val today = LocalDate.now()

        val groups = try {
            registry.groups.first().toList()
        } catch (e: Exception) {
            emptyList()
        }

        for (g in groups) {
            val data = repo.loadGroup(g.spreadsheetId).getOrNull() ?: continue
            for (m in data.activeMembers) {
                if (m.birthday.isBlank()) continue
                val bd = parseBirthday(m.birthday) ?: continue
                val occurrence = nextBirthdayOccurrence(today, bd)
                val days = ChronoUnit.DAYS.between(today, occurrence).toInt()
                val year = occurrence.year

                for (lead in leads) {
                    val key = "${g.id}|${m.name}|${m.birthday}|$year|$lead"
                    if (key in notified || days != lead) continue

                    val label = when (lead) {
                        3 -> "через 3 дня"
                        1 -> "завтра"
                        else -> "сегодня"
                    }
                    BirthdayNotifier.notifyBirthday(context, data.title, m.name, label)
                    newKeys += key
                }
            }
        }

        registry.markBirthdayNotified(newKeys)
        return true
    }

    /** Принимает "гггг-мм-дд" (календарь) и "ДД.ММ" (старые записи). */
    private fun parseBirthday(raw: String): MonthDay? {
        return try {
            val parts = raw.split('-', '.')
            val list = parts.filter { it.isNotEmpty() }.map { it.toInt() }
            if (list.size < 2) return null
            val (day, month) = if (list.size == 2) list[0] to list[1] else list[2] to list[1]
            val dayI = day.coerceIn(1, 31)
            val monthI = month.coerceIn(1, 12)
            // MonthDay.of сам отбросит несуществующие даты (например, 31 февраля).
            MonthDay.of(monthI, dayI)
        } catch (e: Exception) {
            null
        }
    }

    /** Ближайшее наступление ДР, включая сегодняшний день. */
    private fun nextBirthdayOccurrence(today: LocalDate, bd: MonthDay): LocalDate {
        var year = today.year
        var candidate: LocalDate? = null
        while (candidate == null) {
            candidate = try {
                LocalDate.of(year, bd.month, bd.dayOfMonth)
            } catch (e: Exception) {
                LocalDate.of(year, bd.month, 28)
            }
            if (candidate.isBefore(today)) {
                year++
                candidate = null
            }
        }
        return candidate
    }
}