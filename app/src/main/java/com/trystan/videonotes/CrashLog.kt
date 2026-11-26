package com.trystan.videonotes

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File

/** Saves the reason for any crash so the app can show it on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(ctx).writeText(
                    "Video Notes ${BuildInfo.version(ctx)} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + e.stackTraceToString().take(6000)
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The last crash report, or null. Also catches crashes Java can't see (native code). */
    fun read(ctx: Context): String? {
        val f = file(ctx)
        if (f.exists()) return runCatching { f.readText() }.getOrNull()
        if (Build.VERSION.SDK_INT < 30) return null
        val prefs = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val seen = prefs.getLong("seen", 0L)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() }.getOrNull()
            ?: return null
        if (info.timestamp <= seen) return null
        prefs.edit().putLong("seen", info.timestamp).apply()
        val bad = info.reason == ApplicationExitInfo.REASON_CRASH ||
            info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            info.reason == ApplicationExitInfo.REASON_ANR
        if (!bad) return null
        val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        return "Video Notes ${BuildInfo.version(ctx)} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Exit reason ${info.reason}: ${info.description ?: ""}\n\n" + (trace?.take(6000) ?: "")
    }

    fun clear(ctx: Context) { file(ctx).delete() }
}

object BuildInfo {
    fun version(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
