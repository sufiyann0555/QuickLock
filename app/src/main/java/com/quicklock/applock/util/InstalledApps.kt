package com.quicklock.applock.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class AppEntry(val packageName: String, val label: String)

object InstalledApps {
    /** Launchable apps only (visible via the manifest <queries> block, no QUERY_ALL_PACKAGES needed). */
    @Suppress("DEPRECATION")
    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        return infos.asSequence()
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
