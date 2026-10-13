package com.miniworld.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.miniworld.apps.AppCatalog
import com.miniworld.data.SceneStore
import com.miniworld.model.Slot
import com.miniworld.service.WorkbenchService
import com.miniworld.shizuku.ShizukuGateway
import com.miniworld.shizuku.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 列表里的一项：主系统应用的 userId = 0；小米手机分身（XSpace）里的应用是 999。 */
private data class PickOption(
    val packageName: String,
    val label: String,
    val userId: Int
)

/**
 * 须弥芥子 · 唯一的界面（3.2）。
 *
 * 「启动」= 把选定的 App 挂到后台并缩成隐形小点；「关闭」= 收工并撤销保活；
 * 「更换」= 换应用（含手机分身的应用，标「（分身）」）。
 * 页面下方显示三项权限/连接状态，缺哪项点哪项即可去开。
 */
@Composable
fun WorkbenchRoot(activity: Activity, store: SceneStore, catalog: AppCatalog) {

    var scene by remember { mutableStateOf(store.activeScene()) }
    var options by remember { mutableStateOf<List<PickOption>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(activity)) }
    var notifyOk by remember { mutableStateOf(NotificationManagerCompat.from(activity).areNotificationsEnabled()) }
    val shizuku by ShizukuGateway.state.collectAsState()

    LaunchedEffect(Unit) {
        ShizukuGateway.refresh(activity.application)
        options = loadOptions(catalog)
    }

    // 轮询：运行状态与两项系统权限（从设置页回来后能自动刷新）
    LaunchedEffect(Unit) {
        while (true) {
            // 只有主界面在前台时才刷新，避免无谓的功耗；2 秒一次足够
            if (activity.hasWindowFocus()) {
                running = WorkbenchService.controllerOrNull()?.currentState()?.running == true
                overlayOk = Settings.canDrawOverlays(activity)
                notifyOk = NotificationManagerCompat.from(activity).areNotificationsEnabled()
            }
            delay(2000)
        }
    }

    val current = scene?.slots?.firstOrNull()
    val currentLabel = options.firstOrNull {
        it.packageName == current?.packageName && it.userId == current.userId
    }?.label
        ?: current?.label
        ?: "未选择"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text("须弥芥子", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(24.dp))
        Text("当前挂机应用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(currentLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (running) "状态：运行中" else "状态：未运行",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (running) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val target = scene
                if (target == null || target.slots.isEmpty()) {
                    pickerOpen = true
                } else {
                    WorkbenchService.send(activity, WorkbenchService.ACTION_START_SCENE, target.id)
                    activity.moveTaskToBack(true)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) { Text("启动", fontSize = 20.sp) }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                WorkbenchService.send(activity, WorkbenchService.ACTION_STOP, kill = true)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) { Text("关闭", fontSize = 16.sp) }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { pickerOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) { Text("更换", fontSize = 16.sp) }

        Spacer(Modifier.height(20.dp))
        Text("权限 / 连接", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        StatusLine(
            ok = shizuku.ready,
            text = if (shizuku.ready) "Shizuku：${shizuku.uidLabel}" else "Shizuku：未授权",
            hint = "点这里授权",
            onClick = { if (!shizuku.ready) ShizukuGateway.requestPermission() }
        )
        StatusLine(
            ok = overlayOk,
            text = if (overlayOk) "悬浮窗权限：已允许" else "悬浮窗权限：未允许",
            hint = "点这里去开启",
            onClick = {
                if (!overlayOk) {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
            }
        )
        StatusLine(
            ok = notifyOk,
            text = if (notifyOk) "通知权限：已允许" else "通知权限：未允许",
            hint = "点这里去开启",
            onClick = {
                if (!notifyOk) {
                    activity.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                    )
                }
            }
        )

        if (pickerOpen) {
            AlertDialog(
                onDismissRequest = { pickerOpen = false },
                confirmButton = {
                    TextButton(onClick = { pickerOpen = false }) { Text("关闭") }
                },
                title = { Text("换成哪个应用？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        options.forEach { option ->
                            Text(
                                option.label,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val base = scene ?: store.newScene("挂机")
                                        val updated = base.copy(
                                            slots = listOf(
                                                Slot(
                                                    index = 0,
                                                    packageName = option.packageName,
                                                    component = null,
                                                    label = option.label,
                                                    userId = option.userId
                                                )
                                            )
                                        )
                                        store.upsert(updated)
                                        store.setActiveScene(updated.id)
                                        scene = updated
                                        pickerOpen = false
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun StatusLine(
    ok: Boolean,
    text: String,
    hint: String,
    onClick: () -> Unit
) {
    Text(
        (if (ok) "✓ " else "✗ ") + text + if (ok) "" else " —— $hint",
        fontSize = 12.sp,
        color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    )
}

/** 主系统的可启动应用 + 所有其它用户（手机分身）里的可启动应用。 */
private suspend fun loadOptions(catalog: AppCatalog): List<PickOption> = withContext(Dispatchers.IO) {
    val mine = catalog.selectable().map {
        PickOption(it.packageName, it.label, 0)
    }

    val otherUsers = Regex("""UserInfo\{(\d+):""")
        .findAll(ShellRunner.exec("pm list users").stdout)
        .map { it.groupValues[1].toIntOrNull() ?: 0 }
        .filter { it != 0 }
        .toList()

    val clones = ArrayList<PickOption>()
    for (userId in otherUsers) {
        val listing = ShellRunner.exec(
            "cmd package query-activities --brief --user $userId " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        ).stdout
        listing.lineSequence().forEach { line ->
            val match = Regex("""^\s*([A-Za-z0-9_.]+)/(\S+)\s*$""").find(line) ?: return@forEach
            val pkg = match.groupValues[1]
            val known = mine.firstOrNull { it.packageName == pkg }?.label
            clones += PickOption(pkg, (known ?: pkg) + "（分身）", userId)
        }
    }

    (mine + clones).sortedBy { it.label }
}
