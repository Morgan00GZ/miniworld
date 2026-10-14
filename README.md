# 须弥芥子 · miniworld

**把一个 Android 应用藏到屏幕外继续跑，随时一键唤出成浮窗，用完再收回。**

<sub>Android 11+（minSdk 30）· 实测机型 Xiaomi HyperOS 4 / Android 17（1220×2656）· 依赖 [Shizuku](https://shizuku.rikka.app/) · 无需 root · 未声明任何联网权限</sub>

> *Hide an Android app off-screen so it keeps running in the background, then pop it back as a floating window with one tap.*

> 自用工具，仅供学习与个人使用。它不是游戏外挂：**不注入、不修改目标应用、不 hook 系统**，只使用系统公开的窗口 / 任务 / shell 命令。挂机行为可能违反某些游戏的用户协议，风险请自行判断。

---

## 1. 它解决什么问题

手游挂机（或任何需要长时间在线的应用）有两个麻烦：

1. 它得一直占着屏幕，人就没办法用手机；
2. 一退到后台，就可能被系统省电策略**冻结或杀掉**，掉线。

须弥芥子的做法是借用 Android 的**自由窗口（freeform）**：把目标应用用自由窗口拉起来，再把它缩成 **2×2 像素**、整块挪到**真实屏幕之外**。

- 屏幕上一点都看不见（连系统给自由窗口画的那圈装饰也一起在屏幕外）；
- 但系统判定窗口「可见」→ 进程保持 TOP、不被冻结，画面继续渲染。
  （Android 的可见性看窗口状态，不看坐标——这是整个方案成立的前提。）

桌面常驻一条 48dp 宽的半透明悬浮图标条（Dock）：

- 点应用图标 → 目标应用从屏幕外**弹成浮窗**（约可用宽的 3/4、16:9），可以直接看和操作；
- 再点一下 → 收回屏幕外的小点；
- 「关闭」→ 结束该应用，并把它在「最近任务」里的卡片**一起摘掉**。

## 2. 功能

| 位置 | 行为 |
|---|---|
| 首页 | 「启动 / 关闭 / 更换」三个按钮；状态（运行中 / 未运行）；三行权限体检（Shizuku / 悬浮窗 / 通知），点哪行跳哪行系统设置 |
| Dock | 目标应用图标（点一下浮出、再点收回）、「主页」按钮、长按 = 保活复查 |
| 后台看门狗 | 亮屏每 2 s、熄屏每 20 s 校正窗口位置；转屏后自动重排；焦点被别的全屏任务抢走时把它还给用户真正在看的任务；漏拍自检 + 10 分钟闹钟兜底 |
| 保活 | 一键下发 5 条 shell 保活命令（见 §5.3），「关闭」时逐条撤销 |
| 场景 | 记录目标应用与启动组件；**支持小米手机分身（XSpace，userId 999）**里的那一份——同包名靠用户号区分 |

**明确不做**：应用多开、虚拟屏、游戏加速器、多窗口平铺。整个应用只有一个界面。

## 3. 依赖了手机 OS 的哪些能力

| 能力 | 实际用的东西 | 为什么必须有 |
|---|---|---|
| 自由窗口 | `am start --windowingMode 5`、`am task resize <id> l t r b` | 唯一能在本机跑通的「小窗保活」路径（本机 `am start --bounds` 不支持） |
| 任务 / 窗口状态 | 解析 `dumpsys activity activities` 的 `* Task{...}` 行 | 取窗口矩形、`visible`、`visibleRequested`、`mode=freeform`、`U=0/999`；判断窗口是否被系统搬走 |
| shell 执行通道 | **Shizuku**：`Shizuku.newProcess("sh","-c",cmd)`（uid 2000） | 上面这些 `am` / `cmd` / `dumpsys` 都要 shell 权限；Shizuku 提供「一次授权、无需 root」的通道 |
| 悬浮 Dock | `SYSTEM_ALERT_WINDOW` + 前台服务（`specialUse`） | 常驻一条可点的悬浮图标条；同时让编排服务不被回收 |
| 屏幕开关 | `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` + `PowerManager.isInteractive`（**动态注册，不需要权限**） | 熄屏降频、亮屏恢复 2 s 节拍 |
| 兜底唤醒 | `AlarmManager.setAndAllowWhileIdle`（**不需要权限**） | ROM 把整个进程挂起时，唯一还能从进程外把自己叫醒的通道 |
| 开机自启 | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` | 重启后恢复 Dock（挂机场景仍需手动「启动」） |

**不依赖**：root、无障碍服务、Xposed/LSPosed、虚拟屏、任何系统 hook。

## 4. 需要赋予的权限

| 权限 | 类型 | 为什么需要 |
|---|---|---|
| `moe.shizuku.manager.permission.API_V23` | Shizuku | 以 shell(uid=2000) 执行 `am` / `cmd` / `dumpsys` |
| `SYSTEM_ALERT_WINDOW` | 特殊（需手动开） | 画常驻悬浮 Dock。**硬门槛**：没它就是一块什么都点不动的界面 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 普通 | 承载 Dock 与窗口编排的前台服务（`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = 「保持多窗口工作台的悬浮 Dock 与窗口编排常驻」） |
| `POST_NOTIFICATIONS` | 运行时（13+） | 前台服务通知可见，用户能确认「正在挂机」 |
| `QUERY_ALL_PACKAGES` | 普通（敏感） | 「更换」里列出可挂机的应用；只在本地读包名与图标 |
| `RECEIVE_BOOT_COMPLETED` | 普通 | 开机后恢复 Dock |
| `VIBRATE` | 普通 | Dock 长按触感反馈 |

> **没有 `INTERNET` 权限**：应用不联网、不上传任何数据。

## 5. 针对本机调过的参数与对应代码

下列参数都是在一台 **Xiaomi HyperOS 4 / Android 17、1220×2656、375dp 宽** 的真机上试出来的；换机型时大概率要重调（见 §7）。

### 5.1 参数表

| 参数 | 当前值 | 位置 | 为什么是这个值 |
|---|---|---|---|
| 隐形小点位置 | 真实屏幕右下再外移 `+16 / +34` px | `app/.../window/WorkbenchController.kt:775`（`DOT_OUT_X/Y` 在 `:896-897`） | 让**小点 + 系统给自由窗口画的装饰**一起落到屏幕外；留 16/34 px 余量，装饰才不外露 |
| 小点尺寸 | 2×2 px | 同上 | 只要窗口「可见」即可保活，占用越小越好 |
| 看门狗节拍 | 亮屏 2 s / 熄屏 20 s | `app/.../window/TickPolicy.kt:16,19` | 每拍要起一个 `sh` 跑一条 `dumpsys`（实测 ~0.01 s、72 KB）；熄屏时用户不看屏幕，降到 20 s；熄屏兜底最长也就晚 20 s |
| 动过窗口后的快档保持 | 10 s | `TickPolicy.kt:26` | `resize` 在本机要「先小后大」两步才落位，确认必须跟得上 |
| 开工首拍延迟 | 6 s | `TickPolicy.kt:29` | 给系统把窗口拉起来的时间 |
| 心跳日志间隔 | 5 min（按时间算，不按拍数） | `TickPolicy.kt:32` | 两种节拍下都是 5 分钟一条，日志好读 |
| 漏拍判据 | `两拍 + 5 s` | `TickPolicy.kt:58` | 真机复现过**整进程被挂起 13.5 分钟**，需要一条能自证的判据 |
| 闹钟兜底 | 10 min | `TickPolicy.kt:41` | 进程内巡检线程会跟进程一起被冻住，只能靠系统闹钟从外部唤醒 |
| 两步摆位 | 缩到 3/5 → 200 ms（校正）/ 350 ms（浮出）→ 目标 | `WorkbenchController.kt:409-410,710-711` | 单次 `resize` 在转屏后经常不生效 |
| 浮窗尺寸 | 可用宽的 3/4，16:9，居中 | `WorkbenchController.kt:788`（`overlayBounds`） | 横屏游戏是 16:9，窗口同比例画面才铺满、触控映射才对 |
| Dock 宽度 / 图标 | 48dp / 40dp，空闲 4 s 淡化到 `alpha=0.35`，200 ms 动画 | `app/.../service/DockView.kt:52-61,263` | 这台机器只有 375dp 宽，早先 70dp 的 Dock 会吃掉右边 1/5 |
| 系统栏内缩兜底 | 状态栏 26dp / 导航栏 16dp | `app/.../system/ScreenMetrics.kt:61-62` | 服务在后台取不到窗口上下文，只能用系统资源 + 兜底值 |

### 5.2 关键代码段

**节拍策略**（`window/TickPolicy.kt`，纯函数、可单测）：

```kotlin
const val TICK_MS = 2_000L            // 亮屏
const val TICK_MS_SCREEN_OFF = 20_000L // 熄屏
const val FAST_HOLD_MS = 10_000L       // 刚动过窗口 → 保持快档
const val ALARM_INTERVAL_MS = 10 * 60 * 1000L

fun intervalMs(screenOn: Boolean, fastUntil: Long, now: Long): Long =
    if (!screenOn && now >= fastUntil) TICK_MS_SCREEN_OFF else TICK_MS

fun stallThresholdMs(intervalMs: Long): Long = intervalMs * 2 + 5_000L
fun isStalled(now: Long, lastTickAt: Long, intervalMs: Long): Boolean =
    now - lastTickAt > stallThresholdMs(intervalMs)
```

**屏幕外小点**（`window/WorkbenchController.kt:775`）：

```kotlin
private fun dotBounds(): Bounds {
    val s = ScreenMetrics.read(appContext)          // 真实屏幕，不是挖掉系统栏的可用区
    val left = s.width + DOT_OUT_X                  // DOT_OUT_X = 16
    val top = s.height + DOT_OUT_Y                  // DOT_OUT_Y = 34
    return Bounds(left, top, left + 2, top + 2)     // 2×2，整块在屏幕外
}
```

**保活命令**（`system/KeepAlive.kt:31-48`，全部按用户号下发，可逐条撤销）：

```kotlin
"cmd deviceidle whitelist +$pkg"                                            // 电池优化白名单（全机，无 --user）
"am set-inactive --user $userId $pkg false"                                 // 标记为活跃
"am set-standby-bucket --user $userId $pkg active"                          // 待机桶 → active
"cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND allow"
"cmd appops set --user $userId $pkg RUN_IN_BACKGROUND allow"
```

> 只做这 5 条。唤醒锁、关闭整机省电、改 MIUI 自启动这类影响面大的开关**故意不做**：用户要的是「这个应用掉线」，不是「整机省电策略关掉」。

**启动策略链**（`system/FreeformController.kt:137`）：从零特权到高特权逐级尝试，第一个成功即停，并记下成功的那条：

1. App 内 `startActivity` + `ActivityOptions.setLaunchBounds()`（+ 反射 `setLaunchWindowingMode`，被 hidden-API 拦下就降级）
2. `am start --user <U> -n <组件> --windowingMode 5 [--bounds l t r b]`
3. 全屏 `am start` → `am task resize <id> l t r b`
4. 厂商小窗广播（可选，装了对应模块才有）

**命令能力探测**（`FreeformController.kt:70`）：不猜「这个 ROM 支不支持 `--bounds`」，而是读一次 `am help` 的输出再决定走哪条策略（`TaskInspector` 与 `focus` 的可用性另由实测决定，因为 Android 17 的 `am help` 根本不列 `focus`）。

**关闭一个应用**（`FreeformController.kt:350`）：只发 `force-stop` 不够——最近任务里的卡片还在，点它能冷启动回来。所以：

```kotlin
ShellRunner.exec("am force-stop --user $userId $packageName")  // 用户号必写：否则主系统与分身两份一起被杀
ShellRunner.exec("am stack remove $id")                        // 摘掉后台卡片（`am task remove` 不存在）
```

**用户号必须一路带着**（`FreeformController.kt:119-135`）：不写 `--user` 时，装了手机分身的应用会弹出「选哪一个」的系统选择框，表现为启动失败。所有命令一律显式 `--user 0` 或 `--user 999`。

### 5.3 其他被真机逼出来的实现细节

- **落盘日志 `diag.log`**（`system/Diag.kt`）：这台机器把 logcat 的 main 缓冲区抽干了，读不到任何应用日志，所以关键事件自己写到 `Android/data/com.miniworld/files/diag.log`；1.12 起只记「失败 + 每 5 分钟一条心跳」。
- **`resize` 必须复查**（`FreeformController.kt:275`）：只看退出码会被 ROM 骗过去，所以改完 sleep 250 ms 再回读 `dumpsys` 的矩形比对（容差 24 px）。
- **地址串不能带逗号**：`am task resize` 要 4 个空格分隔的数字，逗号串必抛 `NumberFormatException`。
- **焦点交还只发一条命令**（`WorkbenchController.kt:129`）：窗口被系统顶成 `topDisplayFocusedRootTask` 时，只对「用户此刻真正在看的那个任务」发一条 `am task focus`，**不动任何几何**；单应用小点模式下如果窗口在系统眼里仍可见，一拍都不抢前台——否则手势导航的侧滑返回会被 2×2 小点吃掉。

## 6. 构建与运行

```bash
# 需要：JDK 17、Android SDK（compileSdk 35）、Gradle 8.9+
./gradlew assembleDebug testDebugUnitTest        # APK → app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 上仓库自带 `tools/build-apk.bat` 与 `tools/setup-build-env.ps1`。单测共 **29 个用例 / 4 个纯 JVM 测试文件**（`AppTargetsTest`、`LayoutEngineTest`、`TickPolicyTest`、`TaskInspectorTest`），不需要设备。

跑起来需要三步：

1. 装 [Shizuku](https://shizuku.rikka.app/) 并用 adb / 无线调试启动它的服务；
2. 打开须弥芥子，首页三行权限全绿（Shizuku / 悬浮窗 / 通知）；
3. 「更换」选一个应用 → 「启动」。图标条出现后点图标即浮出、再点收回；「关闭」结束它并摘卡片。

## 7. fork 之后怎么适配自己的手机

代码里**没有硬编码任何目标应用**，没有 root / 无障碍依赖；要改的基本只有「设备相关常量」。推荐流程：

**第 0 步：先测命令面，别先改代码**

```bash
adb shell 'am help' | grep -E 'windowingMode|--bounds|resize <TASK_ID>|focus'
adb shell 'am task resize <id> 0 0 100 100'    # 真跑一次，看是成功还是 Unknown command
adb shell dumpsys activity activities | head -40   # 确认能读到 * Task{...} 与 bounds
```

`tools/probe-hyperos.ps1`（Windows）/ `tools/probe-hyperos.sh` 会把这一圈探测跑完。

**第 1 步：只改这些常量**（一次改一个，改完跑 `./gradlew testDebugUnitTest`）

| 想改什么 | 改哪里 | 什么时候需要改 |
|---|---|---|
| 小点外移量 | `WorkbenchController.kt` 的 `DOT_OUT_X/Y`（16/34） | 屏幕上还能看到一条边/一圈装饰 → 加大；完全看不见后可以减小 |
| 节拍 | `TickPolicy.kt` 的 `TICK_MS` / `TICK_MS_SCREEN_OFF` | 想更省电（熄屏 30 s）或更跟手（熄屏 10 s） |
| 浮窗比例 | `WorkbenchController.kt` 的 `overlayBounds()`（3/4 与 9/16） | 你的目标应用不是 16:9 横屏；或想要更大的浮窗 |
| Dock 宽度 / 图标 / 淡化 | `DockView.kt` 的 `WIDTH_DP = 48f`、`iconSize = 40f`、`idleAlpha = 0.35f` | Dock 挡屏幕或太小点不着 |
| 系统栏兜底 | `ScreenMetrics.kt` 的 26dp / 16dp | 你的 ROM 读不到 `status_bar_height`，导致窗口被状态栏压住 |
| 包名 | `app/build.gradle.kts` 的 `applicationId` | 想和别人的安装包共存 |

**第 2 步：三个「可选开关」**

- **你的 ROM 不给自由窗口画装饰** → 小点可以放回屏幕内右下角（把 `dotBounds()` 改成 `Bounds(w-2, h-2, w, h)`），可读性更好；
- **你的机器 logcat 正常** → 可以把 `Diag.log` 换成普通 `Log`，不必落盘；
- **你没有小米手机分身** → `userId` 恒为 0，999 相关分支可以完全忽略（不用删）。

**第 3 步：验收（在真机上一项项过）**

1. 小点在屏幕上真的看不见（含转屏）；
2. 挂机 10 分钟后目标进程仍是 `curProcState=2`（TOP）、`isFrozen=false`；
3. 侧滑返回（手势导航）正常落在你正在用的应用上，不会被小点吃掉；
4. 上滑回桌面 / 打开别的竖屏应用后，窗口最多 2 s 内被扔回屏幕外；
5. 点 Dock 图标能浮出、再点能收回；「关闭」后最近任务里没有卡片。

**第 4 步：vibecoding 的推荐姿势**

把本 README 的 §5.1 参数表 + §7 的两张表原样丢给 AI，然后要求：

> 「只改设备相关常量，不改架构；每改一处跑一次 `./gradlew testDebugUnitTest`；改完告诉我该在真机上验证哪几条。」

架构稳定、常量集中、纯函数有单测——这正是为了让 AI 改得动而刻意保持的形状。

## 8. 已知限制与注意事项

- **ROM 省电策略可能把整个进程挂起**（本机实测过 13.5 分钟零执行）。应用侧有三层自愈：单拍异常仍旧续拍、60 s 巡检补拍、10 分钟闹钟兜底；但**请务必在系统设置里把本应用设为「无限制」省电并允许自启动**，否则兜底也可能一起被冻住。
- **服务未导出**（`exported=false`，安全考虑）：只能通过界面启停，不能从 adb 直接拉起；自动化测试请用 `input tap`，并确保屏幕亮、且本应用窗口在最前。
- **悬浮窗权限与 Shizuku 是硬门槛**，两者缺一应用基本不可用。
- **本机实测（1.12）**：后台亮屏连续 26 分钟零漏拍，心跳 5 分钟一条（抖动 <200 ms）。
- `diag.log` 上限 512 KB，超了整个文件重来（只记关键事件，正常不会到）。
- 「关闭」走 `force-stop` + `am stack remove`；摘卡片依赖 ROM 把任务 id 当 root task id（现代 Android 成立）。
- 非本机机型 / 非小米 ROM 可以跑，但 §5.1 的参数都需要按 §7 重调。

## 9. 目录结构

```
app/src/main/java/com/miniworld/
├── MainActivity.kt / App.kt        # 唯一界面与入口
├── apps/AppCatalog.kt              # 可挂机应用清单
├── data/SceneStore.kt              # 场景持久化（含 userId）
├── model/                          # Bounds / Slot / LiveWindow / AppTargets
├── service/
│   ├── WorkbenchService.kt         # 前台服务（唯一入口：START / STOP 动作）
│   ├── DockView.kt                 # 悬浮图标条（自绘 View）
│   └── BootReceiver.kt             # 开机恢复
├── shizuku/                        # ShellRunner / ShizukuGateway（shell 通道）
├── system/
│   ├── FreeformController.kt       # 自由窗口：启动策略链 / resize / focus / 关闭
│   ├── TaskInspector.kt            # 解析 dumpsys，拿任务与窗口状态
│   ├── KeepAlive.kt                # 5 条保活命令 + 只读审计
│   ├── ScreenMetrics.kt            # 屏幕尺寸与系统栏内缩
│   └── Diag.kt                     # 落盘日志
├── ui/WorkbenchRoot.kt             # Compose 首页
└── window/
    ├── WorkbenchController.kt      # 编排器 + 看门狗（本项目的心脏）
    ├── LayoutEngine.kt             # 纯函数布局
    └── TickPolicy.kt               # 纯函数节拍策略

app/src/test/…                      # 29 个 JVM 单测
docs/                               # 项目文档 / 开发文档 / 版本记录 / 真机验证清单
snapshots/                          # 每个版本的快照（含 APK）
tools/                              # 构建、探测、验证、发布脚本
```

## 10. 开发 / 验证工具

| 文件 | 用途 |
|---|---|
| `tools/probe-hyperos.ps1` / `.sh` | 一次性探测本机 `am` 命令面与窗口能力 |
| `tools/verify-layout.py` / `verify-taskinspector.py` / `verify-probe-sh.py` | 离线核对纯函数输出与 `dumpsys` 解析 |
| `tools/build-apk.bat` / `setup-build-env.ps1` | Windows 上开箱构建 |
| `tools/release.ps1` / `.cmd` | 一把跑完：版本号 → 编译+单测 → 装机核对 → 快照 → commit → tag |
| `tools/doc-sync.ps1` / `.cmd` | 按锚点表批量改文档（`路径 \|\|\| 原文 \|\|\| 新文`，支持 `-Check` 只报不改） |

## 11. 版本与快照

每个可用的版本都在 `snapshots/<版本>-<一句话>/` 下留了一份完整快照（源码 + `须弥芥子-<版本>.apk`），并打了同名 git tag。详细改动见 [docs/版本记录.md](docs/版本记录.md)。

| 版本 | 一句话 | 快照目录 |
|---|---|---|
| 1.0 | 首个可用正式版 | `snapshots/1.0-正式版` |
| 1.01 | 项目改名为 miniworld | `snapshots/1.01-改名版` |
| 1.02 | 修复手机分身误伤与收工遗留 | `snapshots/1.02-分身与收工修复` |
| 1.1 | 小点移到屏幕外 + 修复侧滑返回失效 | `snapshots/1.1-屏幕外小点与侧滑修复` |
| 1.11 | 看门狗降耗 + 日志降噪 | `snapshots/1.11-看门狗降耗与日志降噪` |
| 1.12 | 熄屏降压 + 看门狗自检与闹钟兜底 | `snapshots/1.12-熄屏降压与看门狗自检` |

> 早期还有一个被否决的试验包（3.5），因此正式版号从 3.6 之后重新按 1.x 排列。

## 12. 免责声明

本项目按「现状」提供，仅供学习、研究与个人使用；作者不对任何数据丢失、账号风险或违反第三方应用服务条款的后果负责。请遵守你所在地区的法律法规与相关应用的用户协议。

## 13. License

[MIT](LICENSE) © 2026 Morgan00GZ —— 随便用、随便改，出事别找我（详见 [LICENSE](LICENSE)）。
