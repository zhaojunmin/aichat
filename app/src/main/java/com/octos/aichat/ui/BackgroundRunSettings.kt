package com.octos.aichat.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Takes the user to the phone maker's page that decides whether this app may keep
 * running in the background (Honor / Huawei 应用启动管理, Xiaomi / OPPO / vivo 自启动).
 * No public API can read or change those switches, so all we can do is open the page.
 *
 * The pages are private to each ROM and move between versions: the candidates are tried
 * in order and the first one that starts wins; the app's own details page is the last
 * resort (most ROMs link their battery / launch settings from there).
 */
object BackgroundRunSettings {

    fun open(context: Context) {
        candidates(context.packageName).firstOrNull { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false // exists but not exported to us on this ROM version
            }
        }
    }

    private fun candidates(pkg: String): List<Intent> = listOf(
        // Honor MagicOS (checked on MagicOS 10).
        Intent("hihonor.intent.action.HSM_STARTUPAPP_MANAGER"),
        // Huawei EMUI / HarmonyOS, newer then older.
        component(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        component(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
        // Xiaomi MIUI / HyperOS.
        component(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        // OPPO / OnePlus ColorOS, newer then older.
        component(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        component(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity",
        ),
        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // vivo / iQOO.
        component(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Any phone: this app's details page.
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg".toUri()),
    )

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
}
