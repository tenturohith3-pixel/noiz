package com.noiz.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes any uncaught exception to a file the user can open without adb.
 *
 * The file lands at `Android/data/com.noiz.app/files/last-crash.txt`, which the
 * device's own Files app can read on Android 11+. Without this, a crash loop on a
 * sideloaded build was completely undiagnosable — no adb, no logcat, no evidence.
 *
 * The previous handler still runs afterwards, so the system crash dialog and
 * restart behaviour are unchanged.
 */
object CrashReporter {

    private const val TAG = "NOIZ-CRASH"
    private const val FILE_NAME = "last-crash.txt"

    fun install(appContext: Context) {
        val filesDir: File? = runCatching { appContext.getExternalFilesDir(null) }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // Full stack into logcat for emulator/adb users…
                Log.e(TAG, "Uncaught on ${thread.name}", throwable)
                // …and a durable copy the user can open on-device.
                filesDir?.let { dir ->
                    File(dir, FILE_NAME).writeText(
                        buildString {
                            appendLine(
                                "time: " + SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.US,
                                ).format(Date()),
                            )
                            appendLine("thread: " + thread.name)
                            appendLine("app: " + BuildConfig.VERSION_NAME)
                            appendLine(Log.getStackTraceString(throwable))
                        },
                    )
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
