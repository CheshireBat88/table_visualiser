package com.groupfund.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ловит аварийные завершения и пишет стектрейс в файл + в logcat.
 * На следующем запуске MainActivity покажет причину последнего падения (toast),
 * чтобы баг можно было поймать без adb.
 */
object CrashLogger {

    private const val TAG = "GroupFundCrash"
    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 200_000L

    @Volatile
    private var installed = false

    fun install(context: Context) {
        // onCreate вызывается и при пересоздании Activity: без флага мы бы наматывали
        // цепочку обработчиков и держали ссылку на старую Activity.
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            write(app, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Последний отчёт о падении (обрезанный), если он есть. */
    fun lastCrash(context: Context): String? {
        val f = file(context)
        return if (f.exists() && f.length() > 0) f.readText().take(1200) else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val f = file(context)
            val text = "=== $stamp ===\n$sw\n\n"
            f.appendText(text)
            if (f.length() > MAX_BYTES) {
                // Оставляем хвост файла, чтобы он не разрастался бесконечно.
                val tail = f.readText().takeLast(MAX_BYTES.toInt())
                f.writeText(tail)
            }
            Log.e(TAG, "App crashed:\n$t", t)
        } catch (_: Exception) {
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}