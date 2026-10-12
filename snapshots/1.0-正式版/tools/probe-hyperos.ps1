<#
.SYNOPSIS
    澎湃 OS / HyperOS 自由窗口能力探测（PC 端，通过 adb）。
.NOTES
    本文件**必须**保存为带 BOM 的 UTF-8。
    Windows PowerShell 5.1 对「无 BOM 的 UTF-8」会按系统 ANSI 代码页（中文 Windows 上是 GBK）解析，
    中文变乱码并进一步导致解析器报出一堆假的语法错误。PowerShell 7 默认按 UTF-8 读，不受影响。
.DESCRIPTION
    在安装 App 之前，先回答一个问题：这台手机到底允许什么。
    默认只读，不改任何系统设置。加 -Apply 才会写入 enable_freeform_support。

    探测项编号与 docs/01-可行性分析.md、App 内「一键自检」一一对应。

.EXAMPLE
    .\probe-hyperos.ps1
.EXAMPLE
    .\probe-hyperos.ps1 -Serial 1a2b3c4d -Apply
.EXAMPLE
    .\probe-hyperos.ps1 -TestPackage com.android.settings -SecondPackage com.android.documentsui
#>
[CmdletBinding()]
param(
    # 设备序列号（多设备时必填）
    [string]$Serial = '',
    # 允许写入系统设置（enable_freeform_support / force_resizable_activities）
    [switch]$Apply,
    # 用于探测自由窗口启动的目标应用
    [string]$TestPackage = 'com.android.settings',
    # 用于探测"多个自由窗口能否共存"的第二个应用
    [string]$SecondPackage = 'com.android.calculator2',
    # 探测用的窗口尺寸
    [string]$TestBounds = '0,0,540,1200',
    # 原始输出落盘目录
    [string]$LogDir = '',
    # 虚拟屏实验（M2 的 go/no-go）：造一块虚拟屏，把 App 放上去，看它是否保持 RESUMED
    [switch]$VirtualDisplayTest,
    # 虚拟屏规格：宽x高/dpi（分辨率越低越省电；挂机场景反正不看画面）
    [string]$VdSpec = '1080x2400/440',
    # 只按关键字搜索已安装应用并打印结果，然后退出（用来找真实包名，对不懂开发的人最省事）
    [string]$ListPackages = ''
)

$ErrorActionPreference = 'Continue'
if (-not $LogDir) { $LogDir = Join-Path $PSScriptRoot 'probe-output' }

# ---------------------------------------------------------------- adb 定位

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @()
    if ($env:ANDROID_HOME)      { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT)  { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA)      { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    $candidates += 'C:\Android\Sdk\platform-tools\adb.exe'
    $candidates += 'D:\Android\Sdk\platform-tools\adb.exe'
    # 本机实际安装位置：不在 PATH 里时靠这条兜底，免得直接报"找不到 adb"
    $candidates += 'D:\Software\platform-tools\adb.exe'
    foreach ($c in $candidates) { if ($c -and (Test-Path -LiteralPath $c)) { return $c } }
    return $null
}

$adb = Find-Adb
if (-not $adb) {
    Write-Host '找不到 adb。请安装 Android SDK Platform-Tools，或把它加入 PATH。' -ForegroundColor Red
    exit 2
}

$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $all = @($deviceArgs) + $AdbArgs
    # 原生命令的 stderr 会被 PowerShell 包成 ErrorRecord，直接 Out-String 会把
    # "所在位置 行:N 字符:M / CategoryInfo / FullyQualifiedErrorId" 这些噪音写进报告，
    # 把真正的报错（例如 java.lang.NumberFormatException）淹掉。这里只取真正的文本。
    $out = & $adb @all 2>&1 | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { [string]$_ }
    }
    return ($out -join "`n").Trim()
}

function Invoke-Shell {
    param([string]$Command)
    return (Invoke-Adb @('shell', $Command))
}

# ---------------------------------------------------------------- 报告

$script:Results = New-Object System.Collections.ArrayList

function Add-Result {
    param([string]$Id, [string]$Title, [string]$Status, [string]$Detail, [string]$Raw = '')
    [void]$script:Results.Add([pscustomobject]@{
        Id = $Id; Title = $Title; Status = $Status; Detail = $Detail
    })
    $color = switch ($Status) {
        'PASS' { 'Green' } 'WARN' { 'Yellow' } 'FAIL' { 'Red' } 'SKIP' { 'DarkGray' } default { 'Gray' }
    }
    Write-Host ("[{0,-4}] {1,-28} {2}" -f $Status, $Id, $Title) -ForegroundColor $color
    if ($Detail) { Write-Host ("        └ {0}" -f $Detail) -ForegroundColor DarkGray }
    if ($Raw) {
        $firstLine = ($Raw -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 3) -join ' / '
        if ($firstLine) { Write-Host ("        │ {0}" -f $firstLine) -ForegroundColor DarkGray }
    }
}

function Save-Raw {
    param([string]$Name, [string]$Content)
    if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $safe = ($Name -replace '[^\w\-\.]', '_')
    $file = Join-Path $LogDir ("{0}-{1}.txt" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $safe)
    Set-Content -LiteralPath $file -Value $Content -Encoding UTF8
    return $file
}

# ---------------------------------------------------------------- 开跑

Write-Host ''
Write-Host '=== 澎湃 OS 自由窗口能力探测 ===' -ForegroundColor Cyan
Write-Host "adb: $adb"
Write-Host "参数: Apply=$Apply TestPackage=$TestPackage SecondPackage=$SecondPackage Bounds=$TestBounds"
Write-Host ''

$devices = Invoke-Adb @('devices')
$online = @($devices -split "`r?`n" | Where-Object { $_ -match '\tdevice$' })
if ($online.Count -eq 0) {
    Write-Host '没有已授权的设备。请连接手机并确认 USB 调试弹窗。' -ForegroundColor Red
    Write-Host $devices -ForegroundColor DarkGray
    exit 3
}
Write-Host ("在线设备: {0}" -f ($online -join ', ')) -ForegroundColor DarkGray
Write-Host ''

# --- 只搜包名模式（给不懂开发的人找包名用） --------------------------------

if ($ListPackages) {
    Write-Host "--- 搜索已安装应用：关键字 '$ListPackages' ---" -ForegroundColor Cyan
    $found = Invoke-Shell "pm list packages $ListPackages"
    if ($found) {
        $found -split "`r?`n" |
            Where-Object { $_.Trim() } |
            ForEach-Object { Write-Host ("  " + ($_.Trim() -replace '^package:', '')) }
        Write-Host ''
        Write-Host '把你要挂机的那个包名（例如 com.tencent.tmgp.sgame）记下来，填到 -TestPackage 后面。' -ForegroundColor Green
    } else {
        Write-Host '没有匹配的包。换个关键字再试，例如：tencent / netease / mihoyo / hypergryph / bilibili' -ForegroundColor Yellow
    }
    Write-Host ''
    exit 0
}

# --- 设备信息 -------------------------------------------------------------

$propNames = @(
    'ro.product.model', 'ro.product.brand', 'ro.build.version.release', 'ro.build.version.sdk',
    'ro.mi.os.version.name', 'ro.mi.os.version.incremental', 'ro.miui.ui.version.name',
    'ro.build.version.incremental', 'ro.build.characteristics'
)
$deviceInfo = @{}
foreach ($p in $propNames) { $deviceInfo[$p] = (Invoke-Shell "getprop $p") }
Write-Host '--- 设备信息 ---' -ForegroundColor Cyan
foreach ($k in $propNames) { Write-Host ("  {0,-34} = {1}" -f $k, $deviceInfo[$k]) }
Write-Host ''

# --- P0 命令面 ------------------------------------------------------------

$amHelp    = Invoke-Shell 'am help'
$amTaskOut = Invoke-Shell 'am task'
$features  = Invoke-Shell 'pm list features'
$null = Save-Raw 'am-help' $amHelp
$null = Save-Raw 'pm-list-features' $features

$hasWindowingMode = $amHelp -match '--windowingMode'
$hasBoundsOpt     = $amHelp -match '--bounds'
# 教训：am help 里子命令是缩进罗列的，根本不会出现 'task resize' 这种带父命令的写法；
# 真机上写的是 `resize <TASK_ID> <LEFT> <TOP> <RIGHT> <BOTTOM>`。按子命令名匹配才准。
$hasResizeTask    = $amHelp -match '(?m)^\s*resize\s*<TASK_ID>'
$hasResizeableOpt = $amHelp -match '(?m)^\s*resizeable\s*<TASK_ID>'
$hasMoveStack     = $amHelp -match '(?m)^\s*move-stack\s*<STACK_ID>'
$hasMoveTask      = $amHelp -match '(?m)^\s*move-task\s*<TASK_ID>'
$hasDisplayOpt    = $amHelp -match '--display'
$multiWindowOut   = Invoke-Shell 'am supports-multiwindow'
$hasMultiWindow   = $multiWindowOut -match '(?i)true'
# am help 里**没有** focus 子命令，但真机上 `am task focus <id>` 确实能跑
# （实测输出 "Setting focus to task 91"）→ 不靠 help 判断，交给 P4 实测。
$hasFocusTask     = $true

Add-Result 'P0.1' 'am start --windowingMode' $(if ($hasWindowingMode) { 'PASS' } else { 'FAIL' }) `
    $(if ($hasWindowingMode) { '支持以指定窗口模式启动' } else { '不支持 → 只能走 App 内 ActivityOptions 策略' })
Add-Result 'P0.2' 'am start --bounds' $(if ($hasBoundsOpt) { 'PASS' } else { 'WARN' }) `
    $(if ($hasBoundsOpt) { '可一步到位带尺寸启动，最省事' } else { '无此选项 → 启动后再 resize' })
Add-Result 'P0.3' 'am task resize' $(if ($hasResizeTask) { 'PASS' } else { 'FAIL' }) `
    $(if ($hasResizeTask) { '可以事后调整窗口大小。注意参数是 4 个分开的数字：am task resize ID LEFT TOP RIGHT BOTTOM' } else { '不可用 → 只能"启动时带尺寸"' })
Add-Result 'P0.4' 'am task focus' $(if ($hasFocusTask) { 'PASS' } else { 'WARN' }) `
    'am help 不列这个子命令，但真机可用；结论由 P4 实测决定'
Add-Result 'P0.5' 'am start --display' $(if ($hasDisplayOpt) { 'PASS' } else { 'FAIL' }) `
    $(if ($hasDisplayOpt) { '语法上支持指定 display 启动（但 shell 往系统持有的虚拟屏启动会被 SecurityException 拒，见 P18.3）' } else { '不支持指定屏启动' })
Add-Result 'P0.6' 'am display move-stack' $(if ($hasMoveStack) { 'PASS' } else { 'FAIL' }) `
    $(if ($hasMoveStack) { '可把整个 stack 搬到另一个屏（比"启动到虚拟屏"更可能被放行，因为没有 ActivityOptions 权限校验）' } else { '无此子命令' })
Add-Result 'P0.7' 'am stack move-task' $(if ($hasMoveTask) { 'PASS' } else { 'FAIL' }) `
    $(if ($hasMoveTask) { '可把单个 task 搬到指定 stack' } else { '无此子命令' })
Add-Result 'P0.8' 'am supports-multiwindow' $(if ($hasMultiWindow) { 'PASS' } else { 'FAIL' }) `
    "设备是否声明支持多窗口：$multiWindowOut"

# --- P1 Freeform 开关 -----------------------------------------------------

$freeformSetting  = Invoke-Shell 'settings get global enable_freeform_support'
$resizableSetting = Invoke-Shell 'settings get global force_resizable_activities'
$hasFreeformFeature = $features -match 'android\.software\.freeform_window_management'

$detail = "enable_freeform_support=$freeformSetting  force_resizable_activities=$resizableSetting  " +
          "FEATURE_FREEFORM_WINDOW_MANAGEMENT=$hasFreeformFeature"
if ($hasFreeformFeature) {
    Add-Result 'P1' 'Freeform 开关' 'PASS' "$detail（系统特性已声明，无需重启）"
} elseif ($freeformSetting -eq '1') {
    Add-Result 'P1' 'Freeform 开关' 'PASS' "$detail（设置已开）"
} else {
    Add-Result 'P1' 'Freeform 开关' 'WARN' "$detail（未开，且系统特性未声明）"
    if ($Apply) {
        Write-Host '  正在写入 enable_freeform_support=1 ...' -ForegroundColor Yellow
        $r1 = Invoke-Shell 'settings put global enable_freeform_support 1'
        $r2 = Invoke-Shell 'settings put global force_resizable_activities 1'
        Write-Host ("  enable_freeform_support -> {0}" -f (Invoke-Shell 'settings get global enable_freeform_support')) -ForegroundColor Yellow
        Write-Host ("  force_resizable_activities -> {0}" -f (Invoke-Shell 'settings get global force_resizable_activities')) -ForegroundColor Yellow
        Write-Host '  AMS 在开机时读取该值，请重启手机后重新运行本脚本。' -ForegroundColor Yellow
    } else {
        Write-Host '  提示：加 -Apply 参数可自动写入（改完需要重启手机）。' -ForegroundColor DarkGray
    }
}

# --- 任务解析 -------------------------------------------------------------

function Get-ActivityDump { return (Invoke-Shell 'dumpsys activity activities') }

function Get-Tasks {
    param([string]$Dump)
    $tasks = @()
    $current = $null
    foreach ($line in ($Dump -split "`r?`n")) {
        $m = [regex]::Match($line, 'Task\{[^}]*#(\d+)[^}]*\}')
        if ($m.Success) {
            if ($current) { $tasks += $current }
            $pkgMatch = [regex]::Match($line, 'A=\d+:([A-Za-z0-9_\.]+)')
            $modeMatch = [regex]::Match($line, '(windowingMode|mode)=(\w+)')
            $current = [pscustomobject]@{
                Id    = [int]$m.Groups[1].Value
                Line  = $line.Trim()
                Pkg   = $(if ($pkgMatch.Success) { $pkgMatch.Groups[1].Value } else { '' })
                Mode  = $(if ($modeMatch.Success) { $modeMatch.Groups[2].Value } else { '' })
            }
            continue
        }
        if ($current -and $line -match 'mBounds=Rect\(([^)]*)\)' -and -not $current.Bounds) {
            $current | Add-Member -NotePropertyName Bounds -NotePropertyValue $Matches[1] -Force
        }
    }
    if ($current) { $tasks += $current }
    return $tasks
}

function Find-TaskForPackage {
    param([string]$Dump, [string]$Package)
    $tasks = Get-Tasks $Dump
    return @($tasks | Where-Object { $_.Pkg -eq $Package } | Select-Object -First 1)
}

# `am start -n` 需要完整组件名（pkg/Activity），包名不能直接当组件用。
function Resolve-Component {
    param([string]$Package)
    $out = Invoke-Shell "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $Package"
    if ($out -match 'Error|Unknown|No activity|No result') {
        $out = Invoke-Shell "cmd package resolve-activity --brief $Package"
    }
    $line = ($out -split "`r?`n" | Where-Object { $_ -match '/' } | Select-Object -First 1)
    if (-not $line) { return $null }
    $first = ($line.Trim() -split '\s+')[0]
    if ($first -match '^[A-Za-z0-9_\.]+/[A-Za-z0-9_\.\$]+$') { return $first }
    return $null
}

# 把一个包的任务整段取出来（不同 ROM 的排版差异大，按"块"判断比按行可靠）
# 返回的是字符串数组，每项 = 一个任务的完整文本
function Get-TaskBlocks {
    param([string]$Dump)
    $blocks = @()
    $current = $null
    foreach ($line in ($Dump -split "`r?`n")) {
        if ($line -match 'Task\{[^}]*#\d+[^}]*\}') {
            if ($current) { $blocks += ($current -join "`n") }
            $current = @($line)
            continue
        }
        if ($current) { $current += $line }
    }
    if ($current) { $blocks += ($current -join "`n") }
    return ,$blocks
}

# 从 dumpsys display 里取出所有 displayId
function Get-DisplayIds {
    param([string]$Dump)
    # 实测教训：DisplayDeviceInfo{...} 那一行极长（含 supportedModes 等），内部还有嵌套花括号，
    # 用 'DisplayDeviceInfo\{([\s\S]{0,400}?)\}' 这类"块匹配"会提前截断，永远解析不出 displayId
    # （于是虚拟屏明明建出来了却报 FAIL）。正确写法是逐行找 'displayId <N>'。
    # 又一个坑：PowerShell 的 -match 默认**忽略大小写**，'mDisplayId=46309...' 里的
    # "DisplayId" 也会被命中；而那种 19 位长整型（display token）强转 [int] 会抛
    #   Cannot convert value "4630946949513469331" to type "System.Int32"
    # 这是**终止性**异常，会让 P18 的 try 块直接跳到 finally → P18.2/P18.3 静默消失、
    # 只剩 P18.1 和 P18.5。所以：-cmatch（区分大小写）+ 限定 1–3 位数字 + TryParse 兜底。
    $ids = @()
    foreach ($line in ($Dump -split "`r?`n")) {
        if ($line -cmatch '\bdisplayId[ =]+(\d{1,3})\b') {
            $n = 0
            if ([int]::TryParse($Matches[1], [ref]$n)) { $ids += $n }
        }
    }
    return ,@($ids | Sort-Object -Unique)
}

# --- P2 自由窗口启动 ------------------------------------------------------

$before = Get-ActivityDump
$pkgInstalled = @((Invoke-Shell "pm list packages $TestPackage") -split "`r?`n" |
    Where-Object { $_.Trim() -eq "package:$TestPackage" }).Count -gt 0
$testComponent = Resolve-Component $TestPackage
if (-not $pkgInstalled) {
    Add-Result 'P2.0' '目标 App 是否存在' 'FAIL' `
        "$TestPackage 没装（pm list packages 查不到）→ 先确认真实包名；包名不对时 P2/P3/P18 的结论全部不可信"
} elseif ($testComponent) {
    Add-Result 'P2.0' '解析启动组件' 'PASS' $testComponent
} else {
    $testComponent = "$TestPackage/"
    Add-Result 'P2.0' '解析启动组件' 'WARN' "解析不到 $TestPackage 的启动组件，退回包名（可能失败）"
}

$boundsArg = if ($hasBoundsOpt) { " --bounds $TestBounds" } else { "" }
Write-Host '  正在尝试以自由窗口启动测试应用 ...' -ForegroundColor DarkGray
$launchOut = Invoke-Shell "am start -n $testComponent --windowingMode 5$boundsArg"
if ($launchOut -match 'Error|Exception|does not exist') {
    # 有些 ROM 需要先启动过该应用；用 monkey 兜底拉起一次再试
    $null = Invoke-Shell "monkey -p $TestPackage -c android.intent.category.LAUNCHER 1"
    Start-Sleep -Milliseconds 800
    $launchOut = Invoke-Shell "am start -n $testComponent --windowingMode 5$boundsArg"
}
Start-Sleep -Milliseconds 1500
$after = Get-ActivityDump
$null = Save-Raw 'dumpsys-activity-activities' $after

$task = Find-TaskForPackage $after $TestPackage
$isFreeform = $false
if ($task) {
    $taskBlock = ($after -split "`r?`n" | Select-String -SimpleMatch "Task{" -Context 0,25) | Out-String
    $isFreeform = ($task.Mode -match 'freeform') -or ($task.Line -match 'freeform') -or
                  ($taskBlock -match "A=\d+:$([regex]::Escape($TestPackage))" -and $taskBlock -match 'freeform')
}
if ($task -and $isFreeform) {
    Add-Result 'P2' '自由窗口启动' 'PASS' "taskId=$($task.Id) mode=$($task.Mode) bounds=$($task.Bounds)"
} elseif ($task) {
    Add-Result 'P2' '自由窗口启动' 'FAIL' "窗口起来了但不是自由窗口：taskId=$($task.Id) mode=$($task.Mode)（ROM 可能忽略了 --windowingMode）"
} else {
    Add-Result 'P2' '自由窗口启动' 'FAIL' "任务列表里找不到 $TestPackage" $launchOut
}

# --- P3 调整窗口大小 ------------------------------------------------------

if ($task) {
    # 真机实测（Android 17）：参数必须是四个分开的数字。
    # 逗号串会抛 java.lang.NumberFormatException: For input string: "0,0,540,1200"
    $resizeArgs = ($TestBounds -split ',') -join ' '
    $resizeOut = Invoke-Shell "am task resize $($task.Id) $resizeArgs"
    Start-Sleep -Milliseconds 800
    $dump3 = Get-ActivityDump
    $task3 = Find-TaskForPackage $dump3 $TestPackage
    $null = Save-Raw 'dumpsys-after-resize' $dump3
    if ($task3 -and $task3.Bounds -and ($task3.Bounds -replace '\s', '') -match '540') {
        Add-Result 'P3' 'am task resize' 'PASS' "bounds=$($task3.Bounds)"
    } elseif ($resizeOut -match 'Error|Exception|not.*resiz') {
        Add-Result 'P3' 'am task resize' 'FAIL' "命令被拒" $resizeOut
    } else {
        Add-Result 'P3' 'am task resize' 'WARN' "命令没报错，但 bounds 未按预期变化（$TestBounds）→ 任务可能不在 freeform 栈" `
            ("bounds=" + $task3.Bounds)
    }

    # --- P4 前置指定任务 --------------------------------------------------
    $focusOut = Invoke-Shell "am task focus $($task.Id)"
    if ($focusOut -match 'Error|Exception|Unknown|Unknown command') {
        Add-Result 'P4' 'am task focus' 'WARN' '不可用 → 回退 am start --activity-reorder-to-front' $focusOut
    } else {
        Add-Result 'P4' 'am task focus' 'PASS' '命令执行成功' $focusOut
    }
} else {
    Add-Result 'P3' 'am task resize' 'SKIP' 'P2 未拿到 taskId'
    Add-Result 'P4' 'am task focus'  'SKIP' 'P2 未拿到 taskId'
}

# --- P5 多自由窗口共存 ----------------------------------------------------

$secondInstalled = @((Invoke-Shell "pm list packages $SecondPackage") -split "`r?`n" |
    Where-Object { $_.Trim() -eq "package:$SecondPackage" }).Count -gt 0

if (-not $secondInstalled) {
    Add-Result 'P5' '多自由窗口共存' 'SKIP' `
        "第二个应用（$SecondPackage）没装 → 用 -SecondPackage 指定一个已安装的应用重跑，否则这条测不出来"
} else {
    $secondComponent = Resolve-Component $SecondPackage
    if ($secondComponent) {
        $null = Invoke-Shell "am start -n $secondComponent --windowingMode 5$boundsArg"
    } else {
        $null = Invoke-Shell "monkey -p $SecondPackage -c android.intent.category.LAUNCHER 1"
    }
    Start-Sleep -Milliseconds 1500
    $dump5 = Get-ActivityDump
    $null = Save-Raw 'dumpsys-two-windows' $dump5
    $allTasks = Get-Tasks $dump5
    $freeformTasks = @($allTasks | Where-Object { ($_.Mode -match 'freeform') -or ($_.Line -match 'freeform') })
    if ($freeformTasks.Count -ge 2) {
        Add-Result 'P5' '多自由窗口共存' 'PASS' "$($freeformTasks.Count) 个自由窗口：$((($freeformTasks | ForEach-Object { "$($_.Pkg)#$($_.Id)" }) -join ', '))"
    } elseif ($freeformTasks.Count -eq 1) {
        Add-Result 'P5' '多自由窗口共存' 'WARN' '只检测到 1 个自由窗口 → ROM 可能限制为单小窗，方案降级为"1 小窗 + 快速切换"'
    } else {
        Add-Result 'P5' '多自由窗口共存' 'SKIP' "第二个应用（$SecondPackage）没能拉起自由窗口"
    }
}

# --- P6 multi-resume（需要人眼） ------------------------------------------

Add-Result 'P6' '失焦仍在运行' 'SKIP' '需要人眼确认：让两个窗口同屏，观察非焦点窗口的动画/计时是否继续'

# --- P7 保活命令 ----------------------------------------------------------

$idleList   = Invoke-Shell 'dumpsys deviceidle whitelist'
$appopsOut  = Invoke-Shell "cmd appops get $TestPackage RUN_ANY_IN_BACKGROUND"
$bucketOut  = Invoke-Shell "am get-standby-bucket $TestPackage"
$freezer    = Invoke-Shell 'settings get global cached_apps_freezer'

$p7ok = ($idleList -notmatch 'Unknown|Error') -and ($appopsOut -notmatch 'Unknown|Error') -and ($bucketOut -notmatch 'Unknown|Error')
Add-Result 'P7' '保活命令' $(if ($p7ok) { 'PASS' } else { 'WARN' }) `
    "deviceidle=$($idleList -split "`r?`n" | Select-Object -First 1) appops=$($appopsOut -split "`r?`n" | Select-Object -First 1) bucket=$($bucketOut -split "`r?`n" | Select-Object -First 1) freezer=$freezer"

# --- P8 厂商小窗广播（可选） ----------------------------------------------

$moduleOut = Invoke-Shell 'pm list packages name.liuyi'
if ($moduleOut -match 'liuyi') {
    Add-Result 'P8' '厂商小窗广播' 'SKIP' "检测到相关包：$moduleOut（可选通路，本项目不依赖它）"
} else {
    Add-Result 'P8' '厂商小窗广播' 'SKIP' '未安装 hyper_freeform_x，跳过（不影响主流程）'
}

# --- P9 小米私有小窗服务 mi_freeform --------------------------------------

$services = Invoke-Shell 'service list'
$null = Save-Raw 'service-list' $services
$hasMiFreeform = $services -match 'mi_freeform'
if ($hasMiFreeform) {
    $miDump = Invoke-Shell 'dumpsys mi_freeform'
    $null = Save-Raw 'dumpsys-mi-freeform' $miDump
    Add-Result 'P9' 'mi_freeform 服务' 'PASS' `
        '找到小米原生小窗服务。Mi-FreeForm 就是通过 binder 调它的 startAppInFreeform（尺寸/DPI 可控）；AIDL 需按 ROM 版本适配' `
        (($miDump -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1) -join '')
} else {
    Add-Result 'P9' 'mi_freeform 服务' 'SKIP' '没有 mi_freeform：本机不是小米系，或该版本改名了。不影响 A/D 档'
}

# --- P10 虚拟屏通路（D 档，首选档位） -------------------------------------

$hasDisplayService = $services -match '(^|\s)display\s' -or $services -match 'display:'
$identity = Invoke-Shell 'id'
$isShell = $identity -match 'uid=2000'
$vdNote = '虚拟屏必须用 shell(uid=2000) 身份创建：部分 ROM 的 DisplayManagerService 会校验 packageName 与调用者 uid，' +
          '以 root 身份创建会抛 "packageName must match the calling uid"（见 Ynkcc/VirtualDisplay 说明）。'
if ($hasDisplayService -and $isShell) {
    Add-Result 'P10' '虚拟屏通路' 'PASS' "$vdNote 当前身份：$identity"
} else {
    Add-Result 'P10' '虚拟屏通路' 'WARN' "$vdNote 当前身份：$identity ；display 服务=$hasDisplayService"
}

# --- P11 am 的 display 能力面（一键切前台的关键） -------------------------

# 教训：`am display` 不带子命令会直接抛
#   java.lang.IllegalArgumentException: Argument expected after "display"
# （BasicShellCommandHandler.getNextArgRequired），所以不能拿它的输出去判断能力面，只能读 am help。
$amDisplayOut = Invoke-Shell 'am display'
$null = Save-Raw 'am-display' $amDisplayOut
$p11ok = $hasDisplayOpt -and $hasMoveStack
Add-Result 'P11' 'am display 能力面' $(if ($p11ok) { 'PASS' } elseif ($hasDisplayOpt) { 'WARN' } else { 'FAIL' }) `
    "指定屏启动 --display=$hasDisplayOpt ；跨屏搬 stack=$hasMoveStack ；stack move-task=$hasMoveTask" $amDisplayOut

# --- P12 当前 display 列表（虚拟屏创建前后的基线） ------------------------

$displayDump = Invoke-Shell 'dumpsys display'
$null = Save-Raw 'dumpsys-display' $displayDump
$displayCount = ([regex]::Matches($displayDump, 'DisplayDeviceInfo\{')).Count
Add-Result 'P12' '当前 display 列表' 'PASS' `
    "检测到 $displayCount 个显示设备。虚拟屏创建成功后这个数字应该增加；它也是判断'屏建出来没有'的基线"

# --- P17 目标 App 是否自带前台服务（决定要不要为它建虚拟屏） --------------

$svcDump = Invoke-Shell "dumpsys activity services $TestPackage"
$null = Save-Raw 'dumpsys-services-testpkg' $svcDump
$hasFgService = $svcDump -match 'isForeground=true'
Add-Result 'P17' '目标 App 自带前台服务' $(if ($hasFgService) { 'PASS' } else { 'WARN' }) `
    $(if ($hasFgService) {
        "$TestPackage 有前台服务 → 轻量保活通常就够，不必为它建虚拟屏（省电省事）"
    } else {
        "$TestPackage 没有前台服务 → 切到后台后大概率被暂停/冻结，需要虚拟屏才能持续运行"
    })

# --- P18 虚拟屏实验（M2 的 go/no-go，默认不跑） --------------------------

if (-not $VirtualDisplayTest) {
    Add-Result 'P18' '虚拟屏实验' 'SKIP' `
        '未启用。加 -VirtualDisplayTest 运行：它会临时写入 overlay_display_devices 造一块虚拟屏，跑完自动清理'
} elseif (-not $pkgInstalled) {
    Add-Result 'P18' '虚拟屏实验' 'SKIP' `
        "目标 App（$TestPackage）没装，先确认真实包名再跑，否则 P18.3 会给出误导性结论"
} else {
    Write-Host ''
    Write-Host '--- P18 虚拟屏实验（会临时修改系统设置，结束时自动清理）---' -ForegroundColor Yellow

    $baseIds = Get-DisplayIds (Invoke-Shell 'dumpsys display')
    Add-Result 'P18.1' 'display 基线' 'PASS' ("现有 displayId: " + $(if ($baseIds.Count) { $baseIds -join ',' } else { '解析失败' }))

    $component = Resolve-Component $TestPackage
    if (-not $component) { $component = "$TestPackage/" }

    try {
        # 先正常启动一次，保证任务已存在，便于对比它在哪块屏上是 RESUMED
        $null = Invoke-Shell "am start -n $component"
        Start-Sleep -Milliseconds 1200

        $null = Invoke-Shell "settings put global overlay_display_devices $VdSpec"
        Start-Sleep -Seconds 3

        $vdDump = Invoke-Shell 'dumpsys display'
        $null = Save-Raw 'dumpsys-display-with-vd' $vdDump
        $vdIds = Get-DisplayIds $vdDump
        $newIds = @($vdIds | Where-Object { $baseIds -notcontains $_ })

        if ($newIds.Count -eq 0) {
            Add-Result 'P18.2' '虚拟屏是否创建' 'FAIL' `
                "写入 overlay_display_devices=$VdSpec 后没有出现新的 displayId（现有：$($vdIds -join ',')）。本机虚拟屏通路可能被 ROM 关闭"
        } else {
            $displayId = $newIds[0]
            Add-Result 'P18.2' '虚拟屏是否创建' 'PASS' "新增 displayId=$displayId（规格 $VdSpec）"

            # 实测坑：往"系统持有"的 overlay display 里启动 App，shell 会被拒：
            #   java.lang.SecurityException: Permission Denial: starting Intent {...} from null
            #   (pid=..., uid=2000) with launchDisplayId=N
            #   at com.android.server.wm.SafeActivityOptions.checkPermissions
            # 含义：overlay_display_devices 造出来的屏 ownerUid=system，不能当作 D 档容器用。
            # 这不代表虚拟屏路线死了——而是生产实现必须由**我们自己**创建并持有那块屏
            # （shell uid 为 owner），再往里启动；那属于 M2-2（自建 public virtual display）。
            $launchOut = Invoke-Shell "am start --display $displayId -n $component"
            Start-Sleep -Seconds 3
            $denied = $launchOut -match 'SecurityException|Permission Denial'

            $dump = Get-ActivityDump
            $null = Save-Raw 'dumpsys-activity-with-vd' $dump
            $block = (Get-TaskBlocks $dump | Where-Object { $_ -match [regex]::Escape($TestPackage) } | Select-Object -First 1)
            $blockText = if ($block) { $block } else { '' }

            $isResumed = $blockText -match 'state=RESUMED|mState=RESUMED'
            $isStopped = $blockText -match 'state=STOPPED|mState=STOPPED'
            $taskDisplay = ([regex]::Match($blockText, 'displayId[ =]+(\d{1,3})')).Groups[1].Value

            if ($denied) {
                Add-Result 'P18.3' '虚拟屏上的 App 状态' 'WARN' `
                    "屏建出来了（displayId=$displayId），但 shell 无权往这块系统持有的屏启动 App：" `
                    "SecurityException at SafeActivityOptions.checkPermissions。结论：overlay_display_devices 的屏不能作为 D 档容器，必须自建（ownerUid=2000）。"

                # 换一条不走 ActivityOptions 的路：把已有 stack 整体搬到虚拟屏
                $stackOut = Invoke-Shell 'am stack list'
                $null = Save-Raw 'am-stack-list' $stackOut
                $stackId = ([regex]::Match($stackOut, 'Stack id=(\d+)')).Groups[1].Value
                if ($stackId) {
                    $moveOut = Invoke-Shell "am display move-stack $stackId $displayId"
                    Add-Result 'P18.3b' "把已有 stack($stackId) 搬到虚拟屏" $(if ($moveOut -match 'Error|Exception|Unknown') { 'WARN' } else { 'PASS' }) `
                        '这条能过的话，D 档可改成「正常启动 → 搬到虚拟屏」，绕开 SafeActivityOptions 的权限校验' $moveOut
                } else {
                    Add-Result 'P18.3b' 'am stack list' 'WARN' `
                        '读不到 Stack id（新版本 Android 可能已无 stack 概念），跳过往虚拟屏搬 stack 的尝试' $stackOut
                }
            } elseif ($isResumed) {
                Add-Result 'P18.3' '虚拟屏上的 App 状态' 'PASS' `
                    "$TestPackage 在 displayId=$taskDisplay 上 state=RESUMED → 虚拟屏通路成立，架构定稿可推进"
            } elseif ($isStopped) {
                Add-Result 'P18.3' '虚拟屏上的 App 状态' 'FAIL' `
                    "$TestPackage state=STOPPED → 虚拟屏留不住 App，需求要退到 D2 微窗或 C 档"
            } else {
                $preview = $blockText
                if ($preview.Length -gt 200) { $preview = $preview.Substring(0, 200) }
                Add-Result 'P18.3' '虚拟屏上的 App 状态' 'WARN' `
                    "读不到明确的 state。先确认 P2.0（包名对不对）与 P18.2，再判断是不是虚拟屏的问题。原始片段：$preview"
            }

            Add-Result 'P18.4' '锁屏存活（手测）' 'SKIP' `
                "保持现状 → 锁屏 10 分钟 → 解锁看 $TestPackage 是否还在跑（对应 P13）"
        }
    } finally {
        # 无论成败都要把设置还原，不给用户手机留改动
        $null = Invoke-Shell 'settings delete global overlay_display_devices'
        Start-Sleep -Milliseconds 800
        $afterIds = Get-DisplayIds (Invoke-Shell 'dumpsys display')
        $verdict = if ($afterIds.Count -le $baseIds.Count) { 'PASS' } else { 'WARN' }
        Add-Result 'P18.5' '清理虚拟屏' $verdict `
            "已执行 settings delete global overlay_display_devices；现有 displayId: $($afterIds -join ',')"
    }
}

# --- 收尾 -----------------------------------------------------------------

Write-Host ''
Write-Host '--- 汇总 ---' -ForegroundColor Cyan
$pass = @($script:Results | Where-Object { $_.Status -eq 'PASS' }).Count
$warn = @($script:Results | Where-Object { $_.Status -eq 'WARN' }).Count
$fail = @($script:Results | Where-Object { $_.Status -eq 'FAIL' }).Count
Write-Host ("PASS={0}  WARN={1}  FAIL={2}" -f $pass, $warn, $fail)

# 结论只看**关键能力项**，不看 FAIL 个数：
# 上一版曾因为三个 bug（am task resize 参数写法、displayId 解析、am help 匹配方式）
# 把一台完全支持自由窗口的机器误判成"走不通"。这里只认 P2 / P3 / P4。
function Get-Status([string]$Id) {
    $r = $script:Results | Where-Object { $_.Id -eq $Id } | Select-Object -First 1
    if ($r) { $r.Status } else { 'SKIP' }
}
$s2 = Get-Status 'P2'; $s3 = Get-Status 'P3'; $s4 = Get-Status 'P4'
Write-Host ''
Write-Host ("关键三项：P2 自由窗口启动={0}   P3 事后改尺寸={1}   P4 一键前置={2}" -f $s2, $s3, $s4) -ForegroundColor Cyan

if ($s2 -eq 'PASS' -and $s3 -eq 'PASS' -and $s4 -eq 'PASS') {
    Write-Host '结论：【档位 A · 真同屏平铺】成立 —— 自由窗口起得来、尺寸改得动、任务前置得了。' -ForegroundColor Green
    Write-Host '      下一步：装 App 直接试 MAIN_3MINI 预设。虚拟屏（D 档"后台不占屏"）是可选增强，不代表 A 档不可用。' -ForegroundColor Green
} elseif ($s2 -eq 'PASS') {
    Write-Host '结论：自由窗口能起来，但"改尺寸/前置"有缺项 → 先看 P3/P4 的原始报错再定，别直接判"走不通"。' -ForegroundColor Yellow
    Write-Host '      退路：档位 B/C（HyperOS 自带小窗 + Shizuku 保活 + 一键切换）。' -ForegroundColor Yellow
} else {
    Write-Host '结论：自由窗口起不来 → 改用【档位 B/C】：HyperOS 自带小窗 + Shizuku 保活 + 一键切换。' -ForegroundColor Yellow
}

Write-Host ''
Write-Host "原始输出已保存到：$LogDir" -ForegroundColor DarkGray
$jsonPath = Join-Path $LogDir 'probe-result.json'
if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
$script:Results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
Write-Host "结构化结果：$jsonPath" -ForegroundColor DarkGray
Write-Host ''
Write-Host '提醒：本脚本默认只读。-Apply 会写入 enable_freeform_support，改完需重启手机。' -ForegroundColor DarkGray
