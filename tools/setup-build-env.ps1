<#
.SYNOPSIS
    OneStep Workbench 一键搭建编译环境 + 编译出 APK（Windows）

.DESCRIPTION
    这台电脑上还没有 Java / Android SDK / Gradle，所以第一次编译需要先把它们装好。
    本脚本做四件事：
      1. 下载并解压 JDK 17（Temurin，约 190MB）
      2. 下载 Android 命令行工具，并安装 platforms;android-35 与 build-tools;35.0.0（约 200MB）
      3. 下载并解压 Gradle 8.9（约 130MB）
      4. 编译出 app-debug.apk，并跑一遍单元测试（布局引擎不变量）

    所有东西都装在 -Root 指定的文件夹里（默认 D:\DeepSeek Harness\AndroidDev）：
      * 不写系统目录、不改 PATH、不改注册表、不需要管理员权限
      * 想彻底卸载，直接删掉那个文件夹即可

    装好之后，以后改完代码只要跑：
      powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-build-env.ps1 -SkipDownload

.PARAMETER Root
    工具链安装位置，默认 D:\DeepSeek Harness\AndroidDev

.PARAMETER Install
    编译成功后顺带用 adb 安装到手机（需要手机已连上且已开 USB 调试）

.PARAMETER SkipDownload
    跳过下载，直接用已装好的工具链重新编译（改完代码后用这个）

.PARAMETER Clean
    编译前先执行一次 clean

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-build-env.ps1
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\setup-build-env.ps1 -SkipDownload -Install
#>
[CmdletBinding()]
param(
    [string]$Root = 'D:\DeepSeek Harness\AndroidDev',
    [switch]$Install,
    [switch]$SkipDownload,
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'   # 关掉进度条：下载能快好几倍

# Windows PowerShell 5.1 默认只启用 TLS 1.0，直连 dl.google.com / GitHub 会报
# "基础连接已经关闭: 接收时发生错误"（看着像断网，其实是 TLS 版本被拒）。必须显式打开 TLS 1.2。
try {
    [Net.ServicePointManager]::SecurityProtocol = `
        [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls11 -bor [Net.SecurityProtocolType]::Tls
} catch { }

# ---------- 固定的下载地址 ----------
$JdkUrl     = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
$SdkToolsUrl= 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$GradleVer  = '8.9'
$GradleUrl  = "https://services.gradle.org/distributions/gradle-$GradleVer-bin.zip"

$JdkDir     = Join-Path $Root 'jdk-17'
$SdkDir     = Join-Path $Root 'sdk'
$GradleDir  = Join-Path $Root "gradle-$GradleVer"
$DlDir      = Join-Path $Root 'downloads'

$ScriptDir  = $PSScriptRoot
$ProjDir    = Split-Path $ScriptDir -Parent          # onestep-workbench\
$LogFile    = Join-Path $ScriptDir 'build-log.txt'
$GradleLog  = Join-Path $ScriptDir 'gradle-log.txt'

function Say  { param([string]$m) Write-Host $m }
function Ok   { param([string]$m) Write-Host "[OK]   $m"   -ForegroundColor Green }
function Warn { param([string]$m) Write-Host "[WARN] $m"   -ForegroundColor Yellow }
function Bad  { param([string]$m) Write-Host "[FAIL] $m"   -ForegroundColor Red }
function Step { param([string]$m) Write-Host "`n=== $m ===" -ForegroundColor Cyan }

function Get-FreeGB {
    param([string]$Path)
    try {
        $qual = Split-Path $Path -Qualifier
        $d = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$qual'" -ErrorAction Stop
        if ($d) { return [math]::Round($d.FreeSpace / 1GB, 1) }
    } catch { }
    return $null
}

function Get-Remote {
    param([string]$Url, [string]$Dest)
    if (Test-Path $Dest) {
        $bytes = (Get-Item $Dest).Length
        # 小于 100KB 一律当成上次下载残留的半截文件，删掉重下（否则会一直"跳过下载"然后编译失败）
        if ($bytes -lt 100KB) {
            Warn "发现残缺的下载文件（$([math]::Round($bytes/1KB,1)) KB），将重新下载：$([System.IO.Path]::GetFileName($Dest))"
            Remove-Item $Dest -Force -ErrorAction SilentlyContinue
        } else {
            $sz = [math]::Round($bytes / 1MB, 1)
            Ok "已存在，跳过下载：$([System.IO.Path]::GetFileName($Dest))（$sz MB）"
            return $true
        }
    }
    Say "  下载 $Url"
    $t0 = Get-Date
    $okDl = $false
    try {
        # -UseBasicParsing：省内存，且新版 PowerShell 里也够用
        Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing -MaximumRedirection 10 -TimeoutSec 1800
        $okDl = $true
    } catch {
        Warn "PowerShell 自带的下载方式失败：$($_.Exception.Message)"
        Say "  改用系统自带的 curl.exe 重试（能穿一些 PowerShell 过不去的 TLS/代理配置）..."
        $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
        if (Test-Path $curl) {
            & $curl -L --fail --retry 3 --retry-delay 2 --connect-timeout 30 -o $Dest $Url
            if ($LASTEXITCODE -eq 0 -and (Test-Path $Dest) -and ((Get-Item $Dest).Length -gt 100KB)) { $okDl = $true }
        } else {
            Say "  找不到 $curl"
        }
        if (-not $okDl) {
            if (Test-Path $Dest) { Remove-Item $Dest -Force -ErrorAction SilentlyContinue }
            Bad "下载失败：$Url"
            Say "  也可以手动用浏览器下载，然后把这个文件放进：$DlDir"
            Say "  需要的文件："
            Say "    JDK 17  : $JdkUrl"
            Say "    SDK 工具: $SdkToolsUrl"
            Say "    Gradle  : $GradleUrl"
            return $false
        }
    }
    $mb = [math]::Round((Get-Item $Dest).Length / 1MB, 1)
    $sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 0)
    Ok "下载完成：$([System.IO.Path]::GetFileName($Dest))（$mb MB，用时 $sec 秒）"
    return $true
}

function Expand-ZipFast {
    param([string]$Zip, [string]$Dest)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force -ErrorAction SilentlyContinue }
    [void](New-Item -ItemType Directory -Path $Dest -Force)
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dest)
}

# ============================================================================
# 从这一行开始，全过程都写进 build-log.txt。
# 之前踩过的坑：日志只在"编译"那一步才创建，于是前面步骤（联网/JDK/SDK/Gradle）
# 一旦出错，脚本退出时磁盘上根本没有日志文件，只能靠回想——必须从头就记。
$script:TranscriptOn = $false
try { Start-Transcript -Path $LogFile -Force | Out-Null; $script:TranscriptOn = $true } catch { }

trap {
    Bad "脚本遇到未处理的错误：$($_.Exception.Message)"
    Say "  完整现场已经写在日志里：$LogFile"
    if ($script:TranscriptOn) { try { Stop-Transcript | Out-Null } catch { } }
    exit 9
}

Say ""
Say "=============================================================="
Say "  OneStep Workbench - 一键编译环境"
Say "=============================================================="
Say "  项目目录  : $ProjDir"
Say "  工具链目录: $Root"
if (-not (Test-Path $ProjDir)) { Bad "找不到项目目录：$ProjDir（脚本应该待在 onestep-workbench\tools\ 里）"; exit 2 }
if (-not (Test-Path (Join-Path $ProjDir 'settings.gradle.kts'))) { Bad "项目目录里没有 settings.gradle.kts，路径不对：$ProjDir"; exit 2 }

# 清掉上一次异常运行留下的垃圾目录：名字里带分号的一律是垃圾（正规工具链目录不会有分号）。
# 老版本脚本在这里被 PowerShell 的逗号优先级坑过，会把 "sdk platforms;android-35 build-tools;35.0.0"
# 这种怪名字当成 sdk_root 建出来一个空目录。
if (Test-Path $Root) {
    Get-ChildItem $Root -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like '*;*' } |
        ForEach-Object {
            Warn "清理上次异常运行留下的目录：$($_.Name)"
            Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
        }
}

$free = Get-FreeGB -Path $Root
if ($free -ne $null) {
    Say "  磁盘剩余  : $free GB"
    if ($free -lt 6) { Warn "剩余空间不足 6GB，装工具链可能失败（需要约 3GB，编译还要额外空间）" }
}

# ---------- 联网自检 ----------
Step "0/5 联网自检"
$netOk = $false
try {
    $null = Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/repository2-3.xml' -Method Head -UseBasicParsing -TimeoutSec 30
    $netOk = $true
    Ok "能连上 dl.google.com"
} catch {
    try {
        $null = Invoke-WebRequest -Uri 'https://services.gradle.org' -Method Head -UseBasicParsing -TimeoutSec 30
        $netOk = $true
        Ok "能连上 services.gradle.org"
    } catch {
        Bad "连不上网（或连接被拦截）：$($_.Exception.Message)"
        Say "  编译必须联网下载依赖。请检查网络/代理/VPN 后重跑。"
        Say "  如果你的网络需要代理，先设置环境变量再跑："
        Say "    `$env:HTTP_PROXY='http://127.0.0.1:端口'; `$env:HTTPS_PROXY='http://127.0.0.1:端口'"
        exit 3
    }
}

# ---------- JDK ----------
Step "1/5 JDK 17"
$javac = Join-Path $JdkDir 'bin\javac.exe'
if (Test-Path $javac) {
    Ok "已装好：$JdkDir"
} elseif ($SkipDownload) {
    Bad "-SkipDownload 指定了跳过下载，但 $JdkDir 里没有 JDK"
    exit 4
} else {
    [void](New-Item -ItemType Directory -Path $DlDir -Force)
    $zip = Join-Path $DlDir 'jdk17.zip'
    if (-not (Get-Remote -Url $JdkUrl -Dest $zip)) { exit 4 }
    Say "  解压中（约 190MB，要等一会儿）..."
    $tmp = Join-Path $Root '_jdk_tmp'
    Expand-ZipFast -Zip $zip -Dest $tmp
    # zip 里顶层是一个 jdk-17.x.y+z 目录，把它挪成固定的 jdk-17
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) { Bad "JDK 解压后结构异常"; exit 4 }
    if (Test-Path $JdkDir) { Remove-Item $JdkDir -Recurse -Force }
    Move-Item $inner.FullName $JdkDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $javac)) { Bad "JDK 装完但找不到 $javac"; exit 4 }
    Ok "JDK 就绪：$JdkDir"
}
$env:JAVA_HOME = $JdkDir
$env:Path = "$(Join-Path $JdkDir 'bin');" + $env:Path
$jv = & $javac -version 2>&1
Ok "javac 版本：$jv（需要 17 或更高）"

# ---------- Android SDK ----------
Step "2/5 Android SDK（platforms;android-35 + build-tools;35.0.0）"
$sdkmanager = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
if ((Test-Path $sdkmanager) -and (Test-Path (Join-Path $SdkDir 'platforms\android-35'))) {
    Ok "已装好：$SdkDir"
} elseif ($SkipDownload) {
    Bad "-SkipDownload 指定了跳过下载，但 $SdkDir 里的 SDK 不完整"
    exit 5
} else {
    [void](New-Item -ItemType Directory -Path $DlDir -Force)
    if (-not (Test-Path $sdkmanager)) {
        $zip = Join-Path $DlDir 'cmdline-tools.zip'
        if (-not (Get-Remote -Url $SdkToolsUrl -Dest $zip)) { exit 5 }
        Say "  解压中..."
        $tmp = Join-Path $SdkDir '_tools_tmp'
        Expand-ZipFast -Zip $zip -Dest $tmp
        $inner = Join-Path $tmp 'cmdline-tools'
        if (-not (Test-Path $inner)) { Bad "命令行工具解压后结构异常（没找到 cmdline-tools 目录）"; exit 5 }
        $latestParent = Join-Path $SdkDir 'cmdline-tools'
        [void](New-Item -ItemType Directory -Path $latestParent -Force)
        $latest = Join-Path $latestParent 'latest'
        if (Test-Path $latest) { Remove-Item $latest -Recurse -Force }
        Move-Item $inner $latest
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path $sdkmanager)) { Bad "sdkmanager 没就位：$sdkmanager"; exit 5 }
    Ok "命令行工具就绪"

    # sdkmanager 用固定的 JAVA_HOME，避免它去找系统里不存在的 java
    $env:JAVA_HOME = $JdkDir

    # ------------------------------------------------------------------
    # 注意：这里刻意不调 sdkmanager.bat，而是直接用 java 跑同一个类。
    # 原因（真机踩坑，有实测记录）：PowerShell 把参数交给 .bat 时会经过 cmd.exe 重新切分，
    # 而 cmd 把 = 、空格都当分隔符，于是 "--sdk_root=D:\DeepSeek Harness\AndroidDev\sdk"、"platforms;android-35"
    # 这种参数会散架或粘成一个字符串；一旦粘成一个，sdkmanager 就把整串当成 --sdk_root 的值，
    # 建出一个怪名字的空目录、一个包都没装，而且退出码是 0，看着像成功。
    # 直接调 java.exe 不经过 cmd，参数逐字传递，这类问题从根上消失。
    $sdkMgrHome = Join-Path $SdkDir 'cmdline-tools\latest'
    $sdkMgrCp   = Join-Path $sdkMgrHome 'lib\sdkmanager-classpath.jar'
    $javaExe    = Join-Path $JdkDir 'bin\java.exe'
    $useJava    = (Test-Path $javaExe) -and (Test-Path $sdkMgrCp)

    Say "  接受 SDK 许可协议（自动输入 y）..."
    $sdkMgrLog = Join-Path $ScriptDir 'sdkmanager-log.txt'
    $yesses = ("y`r`n" * 60)
    if ($useJava) {
        $licOut = $yesses | & $javaExe "-Dcom.android.sdklib.toolsdir=$sdkMgrHome" -classpath $sdkMgrCp com.android.sdklib.tool.sdkmanager.SdkManagerCli "--sdk_root=$SdkDir" --licenses 2>&1 | Tee-Object -FilePath $sdkMgrLog | Out-String
    } else {
        $licOut = $yesses | & $sdkmanager "--sdk_root=$SdkDir" --licenses 2>&1 | Tee-Object -FilePath $sdkMgrLog | Out-String
    }
    if ($licOut -match 'All SDK package licenses accepted') { Ok "许可已接受" }
    else { Warn "许可输出没看到 'All SDK package licenses accepted'（上面那几行就是安装器的原话），继续尝试" }

    Say "  安装 platforms;android-35 与 build-tools;35.0.0（约 200MB，要等一会儿）..."
    Say "  安装器会实时打印进度。它要是问 Accept? (y/N)，脚本已经自动喂好 y，你不用管。"
    $yesses = ("y`r`n" * 60)
    if ($useJava) {
        $yesses | & $javaExe "-Dcom.android.sdklib.toolsdir=$sdkMgrHome" -classpath $sdkMgrCp com.android.sdklib.tool.sdkmanager.SdkManagerCli "--sdk_root=$SdkDir" "platforms;android-35" "build-tools;35.0.0" 2>&1 | Tee-Object -FilePath $sdkMgrLog -Append | Out-Null
    } else {
        $yesses | & $sdkmanager "--sdk_root=$SdkDir" "platforms;android-35" "build-tools;35.0.0" 2>&1 | Tee-Object -FilePath $sdkMgrLog -Append | Out-Null
    }
    $insCode = $LASTEXITCODE
    if (-not (Test-Path (Join-Path $SdkDir 'platforms\android-35'))) {
        Bad "android-35 平台没装上（安装器退出码 $insCode）。上面是 sdkmanager 的原始输出。"
        Say "  也可以手动补装（把下面这行整段复制到 PowerShell 里回车）："
        if ($useJava) {
            Say "    & `"$javaExe`" `"-Dcom.android.sdklib.toolsdir=$sdkMgrHome`" -classpath `"$sdkMgrCp`" com.android.sdklib.tool.sdkmanager.SdkManagerCli `"--sdk_root=$SdkDir`" `"platforms;android-35`" `"build-tools;35.0.0`""
        } else {
            Say "    & `"$sdkmanager`" `"--sdk_root=$SdkDir`" `"platforms;android-35`" `"build-tools;35.0.0`""
        }
        exit 5
    }
    Ok "SDK 就绪：$SdkDir"
}
$env:ANDROID_HOME = $SdkDir
$env:ANDROID_SDK_ROOT = $SdkDir

# 让 AGP 找到 SDK（写 local.properties，AGP 官方支持的定位方式之一）
$localProps = Join-Path $ProjDir 'local.properties'
$sdkForProps = $SdkDir -replace '\\', '/'
"# 由 setup-build-env.ps1 自动生成，指向本机 Android SDK`nsdk.dir=$sdkForProps" |
    Out-File -FilePath $localProps -Encoding ascii -Force
Ok "已写 $localProps（sdk.dir=$sdkForProps）"

# ---------- Gradle ----------
Step "3/5 Gradle $GradleVer"
$gradleBat = Join-Path $GradleDir 'bin\gradle.bat'
if (Test-Path $gradleBat) {
    Ok "已装好：$GradleDir"
} elseif ($SkipDownload) {
    Bad "-SkipDownload 指定了跳过下载，但 $GradleDir 里没有 Gradle"
    exit 6
} else {
    [void](New-Item -ItemType Directory -Path $DlDir -Force)
    $zip = Join-Path $DlDir "gradle-$GradleVer-bin.zip"
    if (-not (Get-Remote -Url $GradleUrl -Dest $zip)) { exit 6 }
    Say "  解压中..."
    $tmp = Join-Path $Root '_gradle_tmp'
    Expand-ZipFast -Zip $zip -Dest $tmp
    $inner = Join-Path $tmp "gradle-$GradleVer"
    if (-not (Test-Path $inner)) { Bad "Gradle 解压后结构异常"; exit 6 }
    if (Test-Path $GradleDir) { Remove-Item $GradleDir -Recurse -Force }
    Move-Item $inner $GradleDir
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $gradleBat)) { Bad "Gradle 没就位：$gradleBat"; exit 6 }
    Ok "Gradle 就绪：$GradleDir"
}

# ---------- 编译 ----------
Step "4/5 编译 APK（第一次会下载 AGP/Compose 等依赖，比较慢，请耐心等）"
Say "  全过程日志（任何步骤出错都在里面）：$LogFile"
Say "  gradle 原始输出：$GradleLog"
$gradleArgs = @('--no-daemon', '--console=plain', '--stacktrace')
if ($Clean) { $gradleArgs += 'clean' }
$gradleArgs += 'assembleDebug'

Push-Location $ProjDir
try {
    $buildOut = & $gradleBat @gradleArgs 2>&1 | Tee-Object -FilePath $GradleLog
    $buildCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$apk = Join-Path $ProjDir 'app\build\outputs\apk\debug\app-debug.apk'
if ($buildCode -eq 0 -and (Test-Path $apk)) {
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    Ok "编译成功：$apk（$mb MB）"
} else {
    Bad "编译失败（gradle 退出码 $buildCode）"
    Say ""
    Say "------ 日志最后 60 行 ------"
    Get-Content $GradleLog -Tail 60 -ErrorAction SilentlyContinue | ForEach-Object { Say $_ }
    Say "------ 结束 ------"
    Say ""
    Say "把上面这段（或者整个 $LogFile 文件）发给我，我来修。"
    Say "常见原因：网络下载依赖失败（重跑一次往往就好了）、或代码里有编译错误。"
    exit 7
}

# ---------- 单元测试 ----------
Step "5/5 单元测试（布局引擎不变量）"
$testArgs = @('--no-daemon', '--console=plain', 'testDebugUnitTest')
Push-Location $ProjDir
try {
    $testOut = & $gradleBat @testArgs 2>&1 | Tee-Object -FilePath (Join-Path $ScriptDir 'test-log.txt')
    $testCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($testCode -eq 0) { Ok "单元测试全部通过" }
else { Warn "单元测试有失败项（不影响安装，但说明布局计算有问题）→ 日志：$(Join-Path $ScriptDir 'test-log.txt')" }

# ---------- 安装（可选） ----------
if ($Install) {
    Step "安装到手机"
    $adb = $null
    foreach ($c in @((Join-Path $SdkDir 'platform-tools\adb.exe'),
                     'D:\Software\platform-tools\adb.exe',
                     'D:\Android\Sdk\platform-tools\adb.exe',
                     "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")) {
        if (Test-Path $c) { $adb = $c; break }
    }
    if (-not $adb) { $cmd = Get-Command adb -ErrorAction SilentlyContinue; if ($cmd) { $adb = $cmd.Source } }
    if (-not $adb) {
        Warn "找不到 adb。可以手动装：把 APK 拷到手机，用文件管理器点它安装（需允许安装未知来源）。"
    } else {
        Say "  用 $adb"
        & $adb install -r $apk
        if ($LASTEXITCODE -eq 0) { Ok "已安装到手机" } else { Warn "安装失败（手机连上了吗？USB 调试开了吗？）" }
    }
}

# ---------- 收尾 ----------
Say ""
Say "=============================================================="
Say "  完成"
Say "=============================================================="
Say "  APK : $apk"
Say ""
Say "  下一步："
Say "   1) 手机上装 Shizuku（应用商店或 GitHub 搜 Shizuku），用无线调试启动它"
Say "   2) 安装这个 APK（$apk）"
Say "   3) 打开 App，先在【自检】页确认 Shizuku 已授权"
Say "   4) 到【场景】页选 App 和布局，回【运行】页启动"
Say ""
Say "  改完代码重新编译（不用再下载工具链）："
Say "    powershell -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -SkipDownload"
Say ""
Say "  卸载工具链：删掉 $Root 整个文件夹即可"
Say ""
if ($script:TranscriptOn) { try { Stop-Transcript | Out-Null } catch { } }
