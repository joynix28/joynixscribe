package com.botillon.flowscribe.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object DeviceCompat {

    fun manufacturer(): String = Build.MANUFACTURER.orEmpty().trim()

    fun isXiaomiFamily(): Boolean {
        val m = manufacturer().lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            m.contains("blackshark")
    }

    fun isOppoFamily(): Boolean {
        val m = manufacturer().lowercase()
        return m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
    }

    fun isHuaweiFamily(): Boolean {
        val m = manufacturer().lowercase()
        return m.contains("huawei") || m.contains("honor")
    }

    fun isSamsung(): Boolean = manufacturer().lowercase().contains("samsung")

    fun isVivo(): Boolean {
        val m = manufacturer().lowercase()
        return m.contains("vivo") || m.contains("iqoo")
    }

    fun autostartIntent(context: Context): Intent? {
        val m = manufacturer().lowercase()
        val candidates = mutableListOf<Pair<String, String>>()
        when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> candidates += listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.securitycenter" to "com.miui.appmanager.ApplicationsDetailsActivity",
            )
            m.contains("oppo") || m.contains("realme") -> candidates += listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            m.contains("vivo") || m.contains("iqoo") -> candidates += listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            m.contains("huawei") || m.contains("honor") -> candidates += listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )
            m.contains("oneplus") -> candidates += listOf(
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            )
            m.contains("samsung") -> candidates += listOf(
                "com.samsung.android.lool" to "com.samsung.android.lool.MainActivity",
                "com.samsung.android.sm" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            )
            m.contains("asus") -> candidates += listOf(
                "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            )
            m.contains("meizu") -> candidates += listOf(
                "com.meizu.safe" to "com.meizu.safe.security.AutoStartActivity",
            )
            m.contains("letv") || m.contains("leeco") -> candidates += listOf(
                "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
            )
        }
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls)
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return null
    }

    fun autostartFallbackIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun popupPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun batteryWhitelistIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun guidanceLines(context: Context): List<String> {
        val lines = mutableListOf<String>()
        if (isXiaomiFamily()) {
            lines += "HyperOS / MIUI : active « Démarrage automatique », « Fenêtres surgissantes » et « Aucune restriction de batterie » pour JoynixScribe."
            lines += "Sur HyperOS 3/4 : Réglages → Apps → JoynixScribe → Économie de batterie → « Aucune restriction »."
        }
        if (isOppoFamily()) {
            lines += "ColorOS / Realme UI / OxygenOS : autorise le démarrage automatique et désactive l'optimisation de batterie."
        }
        if (isHuaweiFamily()) {
            lines += "EMUI / HarmonyOS : ajoute JoynixScribe aux applications protégées et désactive la gestion automatique."
        }
        if (isSamsung()) {
            lines += "One UI : désactive « Mettre en veille les apps inutilisées » et ajoute JoynixScribe aux apps non surveillées."
        }
        if (isVivo()) {
            lines += "FuntouchOS : autorise le démarrage automatique et l'exécution en arrière-plan."
        }
        if (lines.isEmpty()) {
            lines += "Android standard : vérifie que la batterie n'est pas optimisée pour JoynixScribe."
        }
        lines += "Android 15+ : lance la bulle depuis l'application (jamais au démarrage, à cause des restrictions microphone)."
        return lines
    }
}