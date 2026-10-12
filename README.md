# 须弥芥子 · miniworld

面向 **小米澎湃 OS（HyperOS）** 的「锤子一步 / OriginOS 原子工作台」式多应用同屏工作台。
通过 **Shizuku** 借用 shell 身份驱动系统 **自由窗口（Freeform）**，把多个 App 铺成可编排的栅格，
**一键切换主位、一键收工**，面向「多个游戏同时挂机 + 看视频」的场景。

> **它不是"变出更多内存和 GPU"**，而是把系统本来就有、但被藏在多任务手势里的 Freeform 能力，
> 变成一块可编程的工作台。核心机制是 Android 10+ 的 **multi-resume**：
> 只要窗口在屏幕上可见，它的 Activity 就处于 `RESUMED` 而不是 `PAUSED`，游戏继续跑、视频继续播。

---

## 一、这份仓库里有什么

```
miniworld/
├─ README.md                      本文件：总览、构建、快速开始
├─ docs/
│  ├─ 01-可行性分析.md            能力边界、系统机制、必须真机验证的项
│  ├─ 02-架构与命令映射.md        模块划分 + 每条 shell 命令的用途与回退
│  ├─ 03-真机验证清单.md          按顺序打勾的验收步骤
│  ├─ 04-同类项目对照与规划修订.md 8 个同类项目的机制对照 + 假设修正过程
│  └─ 05-需求收敛与架构定稿.md    定稿：主屏 + 虚拟屏副槽 + 任务交换，M1–M5
├─ tools/
│  ├─ probe-hyperos.ps1           PC 端（Windows + adb）能力探测，装 App 之前先跑它
│  ├─ probe-hyperos.sh            同一套探测项，手机 Termux（走 Shizuku rish）或 PC Bash 都能跑
│  ├─ verify-layout.py            布局引擎交叉校验（不需要 JDK/SDK）
│  ├─ verify-probe-sh.py          probe-hyperos.sh 的静态检查 + 解析逻辑等价验证
│  ├─ build-apk.bat               ★双击这个：一键装工具链并编译出 APK
│  └─ setup-build-env.ps1         上面那个 .bat 真正调用的脚本（也可带参数单独跑）
├─ app/src/main/java/com/onestep/workbench/
│  ├─ shizuku/                    授权与 shell 执行
│  ├─ system/                     能力探测、自由窗口控制、任务查询、挂机保活
│  ├─ window/                     纯函数布局引擎（可单测）+ 窗口编排
│  ├─ service/                    前台服务 + 一步式悬浮 Dock
│  ├─ data/ model/ apps/          场景存储、数据模型、应用枚举
│  └─ ui/                         授权页 / 自检页 / 场景编辑页
└─ app/src/test/                  布局引擎单元测试（纯 JVM，无需真机）
```

## 二、目标形态与架构定稿

> 需求已收敛为：**不做分身/多开**（OS 已有），只要「多个 App 同时运行 + 保活 + 一键切到前台」。
> 定稿见 [docs/05-需求收敛与架构定稿.md](docs/05-需求收敛与架构定稿.md)，项目对照见 [docs/04](docs/04-同类项目对照与规划修订.md)。

**一个必须先接受的事实**：Android 的"运行"分两层——**进程层保活**（不被冻结，shell 命令就能做）
与 **Activity 层保活**（不被暂停、继续渲染）。一个全屏 App 会盖住其它窗口，被盖住的窗口就不可见、就被 `PAUSE`。
所以「一个全屏 + 其它继续跑」在普通窗口模型下**不存在**，只有三条通路：

| 通路 | 能否满足"多个 + 不占屏 + 继续跑" |
| --- | --- |
| **虚拟屏 VirtualDisplay** | ✅ 唯一同时满足三者：App 在另一块"显示设备"上，系统认为它可见 |
| 小窗 / 分屏 | ❌ 必须占屏，就做不到"全屏看一个" |
| 画中画 PiP | ❌ 系统全局只允许 1 个 PiP |

**定稿架构 = 主屏保持真实主屏（当前选中的 App 原生全屏）+ 副槽用 1~3 个虚拟屏承载后台 App +
一键切前台 = 任务在两者之间交换。** 参考实现是 Apache-2.0 的 [NeXtep](https://github.com/lujinxin/NeXtep)
（"三个实时小窗由 lifecycle-bound virtual display 支撑，点击即与主窗口交换任务"）。

> **⚠️ 真机探测已完成（2026-10，Xiaomi 25098PN5AC / Android 17 / HyperOS 4.0）**：
> 修复探测脚本的三处 bug 后重跑，**PASS=19 WARN=4 FAIL=0**，
> **结论 = 档位 A（系统自由窗口真同屏平铺）实测可用**——`am start --windowingMode 5` 起得来
> （`taskId=91`、`mode=freeform`）、`am task resize` 改得动（`mBounds=Rect(0,0-540,1200)`）、
> `am task focus` 前置得了（`Setting focus to task 91`）。
> **下一步 = 编译 APK 装到手机，试 `MAIN_3MINI` 预设**（docs/03 阶段 1–3）。
> 上面那张"虚拟屏是唯一通路"的表说的是**"不占屏"这一列**；而"多个 App 同时跑"用 A 档同屏平铺已经成立。
> 逐条实测证据见 [docs/03 阶段 0.7](docs/03-真机验证清单.md)，踩坑记录见 [docs/01 第 7 节](docs/01-可行性分析.md)。

| 档位 | 机制 | 用途 | 状态 |
| --- | --- | --- | --- |
| **A · 系统自由窗口平铺** | `--windowingMode 5`（Freeform）+ `am task resize`（4 个分开的数字）+ `am task focus` | 多个 App 真同屏、都可见、都继续跑 | **已实测通过，当前主方案** |
| **D · 虚拟屏后台槽** | **App 自建** `VirtualDisplay`；切换 = 跨屏搬任务 | 多个 App 同时跑 + **不占屏** | 可选增强；前置 = **M2-2 自建 VirtualDisplay** |
| **A+ · 小米原生小窗服务** | binder 调 `mi_freeform.startAppInFreeform` | 官方小窗手感、尺寸/DPI 精确 | **本机无该服务，作废**（P9 实测） |
| **B · 厂商小窗** | HyperOS 自带小窗 / 迷你小窗 | 单挂机位兜底 | 系统自带 |
| **C · 保活 + 前置切换** | deviceidle / appops / standby-bucket | 进程层保活，切回即续上 | 代码已就绪（P7 实测 PASS） |


App 启动时会跑**能力自检**（`system/SystemProbe.kt`），自动落到能用的最高档位；
每一档都保留手工覆盖开关，因为这类能力**必须真机验证**，不能纸上假设。

> 当前代码实现的是 **A 档 + C 档保活**。**D 档（虚拟屏）是 M2-2，属于可选增强**：
> A 档已经能同时满足"多个 App 持续运行"，D 档省掉的是"占屏"这一点。
> 但 D 档**不能再用 `overlay_display_devices` 走捷径**——实测建屏成功（新增 `displayId=7`）
> 却无法往它启动 App（`SecurityException at SafeActivityOptions.checkPermissions`），
> 必须由 App 自建 VirtualDisplay。

> **v2 修订**：按需求去掉了实时小窗预览——后台 App 只要求"活着"，不要求"看得见"。
> 这一刀砍掉了画面镜像、触摸注入、输入法转发、缩略图这四条最容易碎的链路；Dock 只留应用图标。
> 但代价要说清：**虚拟屏上的 App 仍然要渲染**，而且必须有个消费者把帧读走再丢掉，
> 否则缓冲区满了它会卡在 `eglSwapBuffers`（比被暂停更糟）。真正的省性能手段是
> **低分辨率虚拟屏 + 屏数量上限 + 池化复用**。

### M2-2 的 go/no-go：必须自建虚拟屏，**overlay 捷径已被实测否掉**

```powershell
.\tools\probe-hyperos.ps1 -VirtualDisplayTest
```

它用系统的 `overlay_display_devices` 造一块虚拟屏 → 试图把目标 App 放上去 → **自动清理设置**。

**实测结论（2026-10）**：建屏成功（新增 `displayId=7`），但 `am start --display 7` 被
`SecurityException at SafeActivityOptions.checkPermissions` 拒绝——
**系统持有的屏不接受 shell 的启动请求**。所以这条捷径对 D 档无效（详见
[docs/05 第 5 节](docs/05-需求收敛与架构定稿.md)），必须做 M2-2「App 自建 VirtualDisplay」。
**这不影响 A 档**：A 档不依赖虚拟屏。

### 当前状态 / 下一步

| 项 | 状态 |
| --- | --- |
| 真机能力探测（PC 端，`tools/probe-hyperos.ps1`） | **已完成**（三处脚本 bug 已修，`PASS=19 WARN=4 FAIL=0`） |
| 结论 | **档位 A 可用**：自由窗口起得来、尺寸改得动、任务前置得了 |
| 下一步 | **双击 `tools\build-apk.bat` 编译 APK → `adb install` → 试 `MAIN_3MINI` 预设**（见第三节"构建"与第四节"快速开始"） |
| 仍待验证 | P5 多窗口共存（`-SecondPackage` 重跑）、P6 失焦继续运行（人眼）、P18.4 锁屏存活 |
| 可选增强 | D 档（虚拟屏后台槽）→ 先做 M2-2 自建 VirtualDisplay PoC |



## 三、构建

### 3.1 一键脚本（本机没有 Java / Android SDK 时用这个）

这台机器原本**没有任何构建工具链**（没有 JDK、没有 Android SDK、没有 Gradle），所以准备了
`tools/setup-build-env.ps1`。它会自动把 JDK 17 + Android 命令行 SDK + Gradle 8.9 装进一个
普通文件夹（默认 `D:\DeepSeek Harness\AndroidDev`，不写系统目录、不需要管理员权限、不改 PATH），
然后直接编译出 APK 并跑一遍单元测试。

**双击 `tools\build-apk.bat` 即可。** 等价的命令行形式：

```powershell
cd miniworld
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-build-env.ps1
```

首次约需下载 500MB（JDK 190MB + SDK 平台与编译工具 200MB + Gradle 130MB），
加编译依赖共 5~20 分钟。**之后改完代码重新编译不需要再下载**：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\setup-build-env.ps1 -SkipDownload
```

| 产物 / 日志 | 位置 |
| --- | --- |
| APK | `app\build\outputs\apk\debug\app-debug.apk` |
| 全过程日志（**任何一步出错都在这里**，失败时发这个） | `tools\build-log.txt` |
| gradle 原始输出 | `tools\gradle-log.txt` |
| SDK 安装器原始输出 | `tools\sdkmanager-log.txt` |
| 单元测试日志 | `tools\test-log.txt` |

其他参数：`-Install` 编译完直接 `adb install`；`-Clean` 先 clean；`-Root D:\其他路径` 换安装位置。
想彻底卸载工具链：删掉 `D:\DeepSeek Harness\AndroidDev` 整个文件夹。

> 脚本内置的几处 Windows 适配，全部是真机上踩出来的：
> ① 显式打开 TLS 1.2（Windows PowerShell 5.1 默认只开 TLS 1.0，直连 `dl.google.com` 会报
> "基础连接已经关闭: 接收时发生错误"，看着像断网其实不是）；② 下载失败后改用系统自带 `curl.exe` 重试；
> ③ `build-log.txt` 用 `Start-Transcript` **从第一行就开始记**（旧版只在编译那一步才建日志，
> 前面步骤一挂，磁盘上连日志文件都没有，只能靠回想）；④ 装 SDK 时**不调 `sdkmanager.bat`，
> 而是用 `java.exe` 直接跑同一个类**——PowerShell 把带 `;` `=` 的参数交给 `.bat` 时会经 `cmd.exe`
> 重新切分，实测三个参数会被粘成一个
> `--sdk_root=D:\DeepSeek Harness\AndroidDev\sdk platforms;android-35 build-tools;35.0.0`，sdkmanager 拿整串当 sdk_root，
> 建出一个怪名字的空目录、一个包都没装，**退出码还是 0**，非常难查；
> ⑤ `.ps1` 必须保持 UTF-8 **BOM**（无 BOM 时 5.1 按 GBK 解析中文，报一堆假语法错误），
> `build-apk.bat` 每次启动都会检查并自动补回 BOM；
> ⑥ 安装器的输出必须**实时显示**：旧版把输出收进变量、装完才一起打印，结果安装器问
> `Accept? (y/N)` 时屏幕上一片空白，它等你按回车、你等它出结果，双双卡死十分钟；现在输出实时上屏，
> 并且脚本把 `y` 自动喂给安装器（原始记录留在 `sdkmanager-log.txt`）。

### 3.2 手动构建（本机已有 Android Studio / JDK 时）

要求：JDK 17、Android SDK（`platforms;android-35`、`build-tools;35.0.0`）、Android Studio Ladybug 或更新。

```powershell
cd miniworld

# 1) SDK 路径（注意键名是小写的 sdk.dir）
"sdk.dir=D:\\Android\\Sdk" | Out-File -Encoding ascii local.properties

# 2) 生成 gradle wrapper（仓库里不提交二进制 wrapper jar）
gradle wrapper --gradle-version 8.9

# 3) 编译
.\gradlew.bat :app:assembleDebug
# 产物：app\build\outputs\apk\debug\app-debug.apk

# 4) 不需要真机就能跑的校验：布局引擎的不变量测试
.\gradlew.bat :app:testDebugUnitTest
```

> 直接用 Android Studio 打开这个目录也可以：它会提示生成 wrapper / 同步 Gradle。
> 本仓库刻意不包含 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar` 这些二进制文件。
> `local.properties` 由脚本或上面第 1 步生成，**不要提交进版本库**（它是机器相关的）。


安装并授权：

```powershell
adb install -r app\\build\\outputs\\apk\\debug\\app-debug.apk
# Shizuku 本身需要先通过 adb 或无线调试启动（Shizuku 官方说明）
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

## 四、快速开始（建议顺序）

1. **先不装 App**，跑能力探测（只读，改设置的命令都要你显式开开关）。两条路选一条，探测项完全相同：

   | 跑在哪 | 命令 | 需要什么 |
   | --- | --- | --- |
   | PC（Windows） | `.\tools\probe-hyperos.ps1` | USB/无线调试 + adb |
   | 手机 Termux | `bash tools/probe-hyperos.sh -p 你的游戏包名` | Shizuku + **rish**（见下） |

   > ⚠️ Windows 默认禁止运行 `.ps1`，第一次跑会报「**在此系统上禁止运行脚本**」。两种解法：
   > ① 在当前窗口先执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`（只对当前窗口生效，
   > 关掉即恢复，不改系统设置、不需要管理员）；② 或每条命令前加
   > `powershell -NoProfile -ExecutionPolicy Bypass -File`。

   Termux 上**必须走 rish**：Termux 自己的 uid 改不了 `settings put global`、开不了别的 display、
   也看不到别人的任务。rish 是 Shizuku 官方脚本，让手机上的 shell 拿到 shell(uid=2000) 身份——
   这正是本 App 最终要用的同一条通路，所以用它跑出来的结论是真结论。
   准备方式：从 Shizuku 仓库取 `rish` 与 `rish_shizuku.dex` 放进 `$PATH`，确保 Shizuku 服务已启动。

   **M2 的 go/no-go 只要多一个开关**（临时建虚拟屏看 App 是否保持 `RESUMED`，退出自动还原）：

   ```bash
   # Termux
   pkg install gawk coreutils grep sed findutils    # Termux 基础环境不一定带 awk
   bash tools/probe-hyperos.sh --selftest           # 先验脚本自己的解析器（不碰手机）
   PROBE_MODE=rish bash tools/probe-hyperos.sh -p 你的挂机游戏包名 --vd
   ```

   > ⚠️ **参数直接写包名，不要加尖括号。** `<` 在 PowerShell 与 Bash 里都是特殊字符：
   > PowerShell 报「运算符"<"是为将来使用而保留的」，Bash 把它当输入重定向。
   > PC 端同理：`-TestPackage com.example.game`，**不要**写成 `-TestPackage <com.example.game>`。

2. 装 App → 打开 → 「授权 Shizuku」→ 点「一键自检」，对照 `docs/03-真机验证清单.md`。
3. 「场景」页新建场景：挑 2–4 个 App，选一个布局预设（上下 2、左右 2、主位 + 侧栏、四宫格）。
4. 点「开工」：App 逐个以小窗拉起、按布局铺位、浮出侧边 Dock。
5. 侧边浮出 Dock：**单击图标** = 该 App 换到主位（其余自动重排，不重启应用）；
   **长按图标** = 该 App 加入 / 移出挂机保活；**拖动 Dock 空白处** = 换位置；
   底部三个按钮 = **布局 / 保活 / 收工**。
6. 「收工」：所有窗口回全屏并撤销保活（还有「收工并关闭应用」的强版本）。

## 五、已知限制（务必先读）

- **第三方 App 自己暂停**：部分游戏在 `onWindowFocusChanged(false)` 时主动停止绘制甚至暂停逻辑。
  系统层面无法强制它继续，只能靠"窗口可见即 resumed"绕过绝大多数情况。
  小米官方的自由窗口适配文档第 6 条正是描述这个坑。
- **同一 App 多开**：本方案是"多个不同 App 同屏"，不是"同一个游戏开多号"。后者需要应用分身/多用户沙箱，不在此列。
- **功耗与发热**：N 个游戏同时渲染 = N 份 CPU/GPU 开销。3 个以上全尺寸窗口在手机上必然降频，请按"1 主 + 2 迷你"设计场景。
- **系统设置可能被重置**：`enable_freeform_support` 这类全局开关在 ROM 升级、恢复出厂后会被重置，
  且**修改后通常需要重启一次**才生效（AMS 在开机时读取该值）。App 提供「开机自检并提示」而不是静默改写。
- **ROM 差异**：所有 shell 命令都在 `docs/02-架构与命令映射.md` 里配了回退链；任何一条在澎湃 OS 4 上失效，
  自检页会明确标红，而不是静默失败。

## 六、参考

- 小米澎湃 OS 开发者文档《[全局自由窗口适配说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1593)》
  —— 官方确认「MIUI 的小窗是基于 Android 的多窗口 Freeform 方案实现的」，并指出多窗口下 App 应支持 multi-resume。
- Android 开源项目《[Multi-resume](https://source.android.google.cn/docs/core/display/multi_display/multi-resume)》
- Shizuku API：<https://github.com/RikkaApps/Shizuku-API>
- 同类免 root 实现思路参考：[m-secret/FlymeFreeform](https://github.com/m-secret/FlymeFreeform)（ColorOS 上的无障碍 + 悬浮窗 + Shizuku 组合）
- HyperOS 自由窗口的 shell 接口参考：[hyper_freeform_x](https://github.com/Xposed-Modules-Repo/cn.liuyi.hyper_freeform_x)
  （Xposed 模块，本项目的 `MIUI_FREEFORM` 档位可**可选**复用它广播的接口）

## 七、许可

本仓库为个人项目，代码可自由使用与修改；引用的第三方接口与商标归各自所有者。
