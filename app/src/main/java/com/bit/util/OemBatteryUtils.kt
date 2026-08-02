package com.bit.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object OemBatteryUtils {

    fun isTranssionOrOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("infinix") ||
                manufacturer.contains("tecno") ||
                manufacturer.contains("itel") ||
                manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("vivo")
    }

    fun requestBatteryExemptionIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    DebugLog.w("OemBatteryUtils", "Could not launch battery optimization intent", e)
                }
            }
        }
    }

    fun openAutoStartSettings(context: Context) {
        val intents = listOf(
            Intent().setComponent(ComponentName("com.transsion.phonemaster", "com.transsion.phonemaster.ui.MainActivity")),
            Intent().setComponent(ComponentName("com.transsion.batterysettings", "com.transsion.batterysettings.LinklistManagerActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // Fallback: Generic app details screen
        try {
            val fallback = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(fallback)
        } catch (e: Exception) {
            DebugLog.w("OemBatteryUtils", "Could not launch app details settings", e)
        }
    }
}
