<#
release.ps1 - one-shot release for miniworld: version bump -> build -> install -> snapshot -> commit -> tag.
Prints only a short summary (never the full gradle log).

Usage:
  pwsh -File tools\release.ps1 -Version 1.2 -SnapshotName 1.2-lianxiu
  pwsh -File tools\release.ps1 -Version 1.2 -SnapshotName 1.2-lianxiu -DryRun
  pwsh -File tools\release.ps1 -Version 1.2 -SnapshotName 1.2-lianxiu -CommitMessage "release: 1.2"

Notes:
  * versionCode is auto-incremented from app/build.gradle.kts; versionName is set to -Version.
  * With -SkipBump the version fields must already be set to -Version (needed when the version
    record must quote the final APK bytes/hash, so the docs are written before the release run).
  * Snapshot = all git-tracked files (excluding snapshots/) + <APK renamed to 须弥芥子-<Version>.apk>.
  * The snapshot APK needs `git add -f` because the copied .gitignore blocks *.apk.
  * Source is ASCII-only on purpose (safe under any PowerShell encoding).
#>
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [Parameter(Mandatory = $true)][string]$SnapshotName,
  [string]$CommitMessage = "",
  [switch]$DryRun,
  [switch]$SkipInstall,
  [switch]$SkipBump
)

$ErrorActionPreference = 'Stop'
function Say($m) { Write-Host $m }

$repo = Split-Path -Parent $PSScriptRoot
$git = 'D:\DeepSeek Harness\DSH-GitWorkspace\PortableGit\cmd\git.exe'
if (-not (Test-Path $git)) { $git = 'git' }
$gradle = 'D:\DeepSeek Harness\AndroidDev\gradle-8.9\bin\gradle.bat'
$adb = 'D:\Software\platform-tools\adb.exe'
$env:JAVA_HOME = 'D:\DeepSeek Harness\AndroidDev\jdk-17'
$env:GRADLE_USER_HOME = 'D:\DeepSeek Harness\.gradle'
$env:ANDROID_USER_HOME = 'D:\DeepSeek Harness\.android'

# 须弥芥子-<Version>.apk  (built from code points to keep this file ASCII-only)
$apkName = "$([char]0x987B)$([char]0x5F25)$([char]0x82A5)$([char]0x5B50)-$Version.apk"
$gradleFile = Join-Path $repo 'app\build.gradle.kts'
$apkSrc = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
$snapDir = Join-Path $repo "snapshots\$SnapshotName"
if (-not $CommitMessage) { $CommitMessage = "release: $Version (snapshot $SnapshotName)" }

# ---- 0. plan + prechecks -------------------------------------------------
$cur = [IO.File]::ReadAllText($gradleFile)
$mCode = [regex]::Match($cur, 'versionCode\s*=\s*(\d+)')
$mName = [regex]::Match($cur, 'versionName\s*=\s*"([^"]+)"')
if (-not $mCode.Success -or -not $mName.Success) { throw 'versionCode/versionName not found in app/build.gradle.kts' }
$oldCode = [int]$mCode.Groups[1].Value
$newCode = $oldCode + 1
Say ("plan: versionName {0} -> {1} | versionCode {2} -> {3} | apk {4} | snapshot snapshots\{5}" -f `
     $mName.Groups[1].Value, $Version, $oldCode, $newCode, $apkName, $SnapshotName)
if (& $git -C $repo tag -l $Version) { throw "tag $Version already exists" }
if (Test-Path $snapDir) { throw "snapshot dir already exists: $snapDir" }
if ($DryRun) { Say 'DRY RUN: no write, no build, no commit'; exit 0 }

# ---- 1. bump version ----------------------------------------------------
if ($SkipBump) {
  if ($mName.Groups[1].Value -ne $Version) { throw "SkipBump: versionName is $($mName.Groups[1].Value), expected $Version" }
  Say "1/6 version bump skipped (already $Version / code $oldCode)"
} else {
  $new = [regex]::Replace($cur, 'versionCode\s*=\s*\d+', "versionCode = $newCode")
  $new = [regex]::Replace($new, 'versionName\s*=\s*"[^"]+"', "versionName = `"$Version`"")
  [IO.File]::WriteAllText($gradleFile, $new, (New-Object Text.UTF8Encoding($false)))
  $chk = [IO.File]::ReadAllText($gradleFile)
  if ($chk -notmatch [regex]::Escape("versionName = `"$Version`"")) { throw 'version bump verify failed' }
  Say '1/6 version bumped'
}

# ---- 2. build + unit tests ---------------------------------------------
# NOTE: gradle writes warnings to stderr (e.g. the Kotlin daemon falling back to in-process
# compilation under a sandboxed profile). Under $ErrorActionPreference='Stop' PowerShell 5.1
# turns a native command's stderr into a terminating error, which would abort the release even
# though the build succeeded -- so relax the preference for the call and judge by 'BUILD SUCCESSFUL'.
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$log = & $gradle -p $repo --no-daemon --console=plain assembleDebug testDebugUnitTest 2>&1
$ErrorActionPreference = $prevEap
if (-not ($log | Select-String -Pattern 'BUILD SUCCESSFUL' -Quiet)) {
  ($log | Select-Object -Last 25) | ForEach-Object { Say $_ }
  throw 'build failed'
}
$tests = (@($log) | Select-String -Pattern 'tests? completed|BUILD SUCCESSFUL' | Select-Object -Last 2) -join ' ; '
Say "2/6 build ok: $tests"

# ---- 3. apk info -------------------------------------------------------
$apk = Get-Item $apkSrc
$sha = (Get-FileHash $apkSrc -Algorithm SHA256).Hash.Substring(0, 16)
Say ("3/6 apk {0} bytes sha256:{1}" -f $apk.Length, $sha)

# ---- 4. install + verify ----------------------------------------------
if ($SkipInstall) {
  Say '4/6 install skipped'
} else {
  $prevEap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $inst = (& $adb install -r $apkSrc 2>&1) -join ' '
  $ver = (& $adb shell 'dumpsys package com.miniworld | grep -m1 versionName').Trim()
  $code = (& $adb shell 'dumpsys package com.miniworld | grep -m1 versionCode').Trim()
  $ErrorActionPreference = $prevEap
  Say ("4/6 install: {0} | {1} | {2}" -f $inst, $ver, $code)
  if ($ver -notmatch [regex]::Escape($Version)) { Say "WARN: installed versionName does not contain $Version" }
}

# ---- 5. snapshot -------------------------------------------------------
New-Item -ItemType Directory -Path $snapDir -Force | Out-Null
$files = @(& $git -C $repo ls-files | Where-Object { $_ -notlike 'snapshots/*' })
foreach ($f in $files) {
  $t = Join-Path $snapDir $f
  New-Item -ItemType Directory -Path (Split-Path $t -Parent) -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $repo $f) -Destination $t -Force
}
Copy-Item -LiteralPath $apkSrc -Destination (Join-Path $snapDir $apkName) -Force
$sf = Get-ChildItem $snapDir -Recurse -File
Say ("5/6 snapshot {0} files {1} bytes" -f $sf.Count, ($sf | Measure-Object Length -Sum).Sum)

# ---- 6. commit + tag ---------------------------------------------------
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $git -C $repo add -A
& $git -C $repo add -f "snapshots/$SnapshotName/$apkName"
& $git -C $repo commit -q -m $CommitMessage
& $git -C $repo tag -a $Version -m "release $Version"
$hash = (& $git -C $repo log --oneline -1)
$apks = @(& $git -C $repo ls-files 'snapshots/*' | Select-String -Pattern '\.apk$').Count
$dirty = @(& $git -C $repo status --short).Count
$ErrorActionPreference = $prevEap
Say ("6/6 commit {0} | tag {1} | snapshot apks tracked={2} | dirty={3}" -f $hash, $Version, $apks, $dirty)
Say 'DONE - push by hand: Push-To-GitHub.cmd  +  git push origin --tags'
