package com.onestep.workbench.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

data class InstalledApp(
    val packageName: String,
    val component: String,
    val label: String,
    val icon: Bitmap?,
    val isSystem: Boolean
)

/**
 * 可启动应用枚举。图标在这里一次性解码成 Bitmap，
 * 场景编辑页与悬浮 Dock 都直接复用，避免每次绘制都走一遍资源解析。
 */
class AppCatalog(private val context: Context) {

    private var cache: List<InstalledApp>? = null

    @Suppress("DEPRECATION")
    fun loadApps(force: Boolean = false): List<InstalledApp> {
        cache?.let { if (!force) return it }

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(launcherIntent, 0) }.getOrDefault(emptyList())

        val apps = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val appInfo = activity.applicationInfo ?: return@mapNotNull null

            InstalledApp(
                packageName = activity.packageName,
                component = "${activity.packageName}/${activity.name}",
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(activity.packageName),
                icon = runCatching { info.loadIcon(pm).toBitmap(ICON_SIZE, ICON_SIZE) }.getOrNull(),
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label }

        cache = apps
        return apps
    }

    fun find(packageName: String, force: Boolean = false): InstalledApp? =
        loadApps(force).firstOrNull { it.packageName == packageName }

    /**
     * 我们自己也是"可启动应用"之一，把自身从候选里剔除，
     * 免得用户把工作台塞进工作台里（递归开自己很危险）。
     */
    fun selectable(): List<InstalledApp> =
        loadApps().filterNot { it.packageName == context.packageName }

    private companion object {
        const val ICON_SIZE = 128
    }
}
