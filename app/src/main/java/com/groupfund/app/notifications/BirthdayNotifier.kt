package com.groupfund.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Канал уведомлений и планирование фоновой проверки дней рождений.
 */
object BirthdayNotifier {

    const val CHANNEL_ID = "birthdays"
    private const val WORK_NAME = "birthday_check"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Дни рождения",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Напоминания о днях рождения участников" },
        )
    }

    /** Ежедневная фоновая проверка. KEEP — не сбрасываем таймер при каждом запуске. */
    fun schedule(context: Context) {
        ensureChannel(context)
        val request = PeriodicWorkRequestBuilder<BirthdayWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun notifyBirthday(context: Context, groupTitle: String, memberName: String, whenLabel: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("🎂 У $memberName день рождения — $whenLabel")
            .setContentText(groupTitle)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$groupTitle\n🎂 У $memberName день рождения — $whenLabel"),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(
            (("$groupTitle|$memberName").hashCode() and Int.MAX_VALUE),
            notification,
        )
    }
}