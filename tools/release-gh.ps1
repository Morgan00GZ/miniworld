#requires -Version 5.1
<#
.SYNOPSIS
  在 GitHub 上为某个已定版的 tag 发布 Release，并把该版本的 APK 作为附件上传。

.DESCRIPTION
  版本号来源优先级：-Version / -Tag / 最新 git tag。
  Release 说明默认取 docs\版本记录.md 里对应版本那一节（"## <版本> ..." 到下一条 "## " 之间），
  并在前面补一段安装说明与 APK 的字节数 / sha256 前 16 位。
  APK 默认取 snapshots\<版本>-*\*.apk。
  令牌来源优先级：-Token / 环境变量 GITHUB_TOKEN / 系统凭据管理器里给 git 推送用的 GitHub 令牌。

.EXAMPLE
  .\tools\release-gh.cmd
  发布最新 tag 对应的版本。

.EXAMPLE
  .\tools\release-gh.cmd 1.12 -DryRun
  只打印将要做的事，不联网、不改远端。

.EXAMPLE
  .\tools\release-gh.cmd 1.12 -Force
  该 tag 已经有 Release 时：覆盖说明并重传同名附件。
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)][string]$Version = '',
  [string]$Tag = '',
  [string]$Owner = 'Morgan00GZ',
  [string]$Repo = 'miniworld',
  [string]$Apk = '',
  [string]$NotesFile = '',
  [string]$AssetName = '',
  [string]$Token = '',
  [switch]$Draft,
  [switch]$Prerelease,
  [switch]$NoAsset,
  [switch]$Force,
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$ToolsDir = $PSScriptRoot
$Root = Split-Path $ToolsDir -Parent
if (-not (Test-Path (Join-Path $Root 'app'))) { throw "找不到工程根目录（应包含 app\）：$Root" }

$Git = Join-Path (Split-Path $Root -Parent) 'PortableGit\cmd\git.exe'
if (-not (Test-Path $Git)) { $Git = 'git' }

$TmpDir = Join-Path $env:TEMP 'miniworld-release'
New-Item -ItemType Directory -Force -Path $TmpDir | Out-Null

function Step($n, $text) { Write-Host ("  [{0}] {1}" -f $n, $text) }
function Info($text) { Write-Host ("        " + $text) -ForegroundColor DarkGray }
function Warn($text) { Write-Host ("        ! " + $text) -ForegroundColor Yellow }
function Fail($text) { Write-Host ("  [X] " + $text) -ForegroundColor Red; exit 1 }

# ------------------------------------------------------------------ 1. 版本 / tag
if (-not $Version) {
  if ($Tag) { $Version = $Tag }
  else {
    $latest = (& $Git -C $Root describe --tags --abbrev=0 2>$null | Select-Object -First 1)
    if (-not $latest) { Fail '推不出最新 tag，请显式指定版本，例如：release-gh.cmd 1.12' }
    $Version = ("$latest").Trim()
  }
}
$Version = $Version.Trim() -replace '^[vV]', ''
if (-not $Tag) { $Tag = $Version }
$Tag = $Tag.Trim() -replace '^[vV]', ''

$headSha = (& $Git -C $Root rev-parse --short HEAD 2>$null | Select-Object -First 1)
$tagSha = (& $Git -C $Root rev-parse --short "refs/tags/$Tag^{commit}" 2>$null | Select-Object -First 1)
if (-not $tagSha) { Fail "本地没有 tag「$Tag」，先跑 tools\release.cmd 定版（会自动打 tag），或手动 git tag $Tag" }

Step '1/5' "版本 $Version / tag $Tag / tag 提交 $tagSha / 当前 HEAD $headSha"
if ($headSha -and $tagSha -and ($headSha -ne $tagSha)) { Warn "当前 HEAD（$headSha）不是 tag 指向的提交（$tagSha）；Release 仍会指向 tag" }

# ------------------------------------------------------------------ 2. 说明
if (-not $NotesFile) { $NotesFile = Join-Path $Root 'docs\版本记录.md' }
$notes = ''
$notesSrc = '（未找到版本记录，说明留空）'
if (Test-Path $NotesFile) {
  $lines = [System.IO.File]::ReadAllLines($NotesFile, [System.Text.Encoding]::UTF8)
  $pat = '^##\s+' + [regex]::Escape($Version) + '(\s|$)'
  $start = -1
  for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match $pat) { $start = $i; break } }
  if ($start -ge 0) {
    $end = $lines.Count
    for ($j = $start + 1; $j -lt $lines.Count; $j++) { if ($lines[$j] -match '^##\s') { $end = $j; break } }
    $notes = ($lines[$start..($end - 1)] -join "`r`n").Trim()
    $notesSrc = ("docs\版本记录.md 第 {0}-{1} 行（{2} 字）" -f ($start + 1), $end, $notes.Length)
    $heading = ($lines[$start] -replace '^##\s+', '') -replace '\s*[（(][^）)]*[）)]\s*$', ''
  } else {
    Warn "docs\版本记录.md 里没有「$Version」这一节，Release 说明只放安装信息"
  }
}
if (-not $heading) { $heading = "须弥芥子 $Version" }

# ------------------------------------------------------------------ 3. APK
$apkPath = ''
if (-not $NoAsset) {
  if ($Apk) {
    if (-not (Test-Path $Apk)) { Fail "指定的 APK 不存在：$Apk" }
    $apkPath = (Resolve-Path $Apk).Path
  } else {
    $snapRoot = Join-Path $Root 'snapshots'
    if (Test-Path $snapRoot) {
      $snapPat = '^{0}(-|_|$)' -f [regex]::Escape($Version)
      $snap = Get-ChildItem $snapRoot -Directory |
        Where-Object { $_.Name -match $snapPat } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
      if ($snap) {
        $f = Get-ChildItem $snap.FullName -Filter *.apk -File -ErrorAction SilentlyContinue |
          Sort-Object Length -Descending | Select-Object -First 1
        if ($f) { $apkPath = $f.FullName }
      }
    }
    if (-not $apkPath) {
      $outApk = Join-Path $Root 'app\build\outputs\apk\release'
      if (Test-Path $outApk) {
        $f = Get-ChildItem $outApk -Filter *.apk -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($f) { $apkPath = $f.FullName; Warn "快照里没有 APK，改用构建产物：$($f.Name)" }
      }
    }
    if (-not $apkPath) { Fail "找不到 $Version 的 APK（快照目录 snapshots\$Version-*\ 里没有 .apk），可用 -Apk 指定，或加 -NoAsset 只发说明" }
  }
}

$assetName = ''
if ($apkPath) {
  if (-not $AssetName) { $AssetName = "miniworld-$Version.apk" }
  $assetName = $AssetName
  $origApkName = Split-Path $apkPath -Leaf
  $sizeBytes = (Get-Item $apkPath).Length
  $sha = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash
  $hash16 = $sha.Substring(0, 16)
  $sizeText = '{0:N0}' -f $sizeBytes
  Step '2/5' "APK $apkPath"
  Info "上传名 $assetName（原始文件名 $origApkName）/ $sizeText 字节 / sha256 前 16 位 $hash16"
} else {
  Step '2/5' '不带 APK 附件（-NoAsset）'
}

Step '3/5' "Release 说明：$notesSrc"

$bodyParts = New-Object System.Collections.ArrayList
[void]$bodyParts.Add("**须弥芥子 $Version** —— 单个 App 后台隐形保活 + 桌面浮窗一点弹出/收回的 Android 小工具。")
[void]$bodyParts.Add('')
if ($assetName) {
  [void]$bodyParts.Add("安装包：``$assetName``（原始文件名 ``$origApkName``，$sizeText 字节，sha256 前 16 位 ``$hash16``）")
} else {
  [void]$bodyParts.Add('本次发布不含安装包。')
}
[void]$bodyParts.Add('')
[void]$bodyParts.Add('安装：把 APK 拷到手机安装即可（覆盖安装，`applicationId` 一直是 `com.miniworld`，权限不用重给）。首次启动后要在 Shizuku 里授权本应用，并给「悬浮窗」「通知」两项权限，然后点首页的「启动」。')
if ($notes) {
  [void]$bodyParts.Add('')
  [void]$bodyParts.Add('---')
  [void]$bodyParts.Add('')
  [void]$bodyParts.Add($notes)
}
$bodyText = ($bodyParts -join "`r`n").Trim()

# ------------------------------------------------------------------ 4. 令牌
function Get-GitHubToken {
  if ($Token) { return $Token }
  if ($env:GITHUB_TOKEN) { return $env:GITHUB_TOKEN }
  $saved = $env:GIT_TERMINAL_PROMPT
  $env:GIT_TERMINAL_PROMPT = '0'
  try {
    $input_ = "protocol=https`nhost=github.com`n`n"
    $out = ($input_ | & $Git credential fill 2>$null)
    $pw = ($out | Where-Object { $_ -like 'password=*' } | Select-Object -First 1)
    if ($pw) { return ($pw -replace '^password=', '') }
  } catch { } finally {
    if ($null -eq $saved) { Remove-Item Env:\GIT_TERMINAL_PROMPT -ErrorAction SilentlyContinue }
    else { $env:GIT_TERMINAL_PROMPT = $saved }
  }
  return ''
}

if ($DryRun) {
  Step '4/5' '（DryRun）不联网：将调用 GitHub API 创建 Release'
  Info "POST https://api.github.com/repos/$Owner/$Repo/releases"
  Info ("name = {0}" -f $heading)
  Info ("tag_name = {0} / draft = {1} / prerelease = {2}" -f $Tag, [bool]$Draft, [bool]$Prerelease)
  if ($assetName) {
    Info "上传 https://uploads.github.com/repos/$Owner/$Repo/releases/<id>/assets?name=$assetName"
  }
  Step '5/5' '（DryRun）结束，未改动任何远端内容'
  exit 0
}

$githubToken = Get-GitHubToken
if (-not $githubToken) {
  Fail @'
拿不到 GitHub 令牌。三种给法任选一种：
         1) 在本脚本上加 -Token ghp_xxxx（令牌需勾 repo 权限）
         2) 先 set GITHUB_TOKEN=ghp_xxxx 再运行
         3) 让 git 记住一次 GitHub 凭据（Push-To-GitHub.cmd 推送成功后通常已记住）
'@
}

$ApiBase = "https://api.github.com/repos/$Owner/$Repo"
$Headers = @{
  Authorization = "token $githubToken"
  Accept        = 'application/vnd.github+json'
  'User-Agent'  = 'miniworld-release-tool'
}

function Invoke-Api {
  param([string]$Method, [string]$Path, $Body = $null, [switch]$Allow404)
  $uri = "$ApiBase$Path"
  try {
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 8
      $bf = Join-Path $TmpDir 'request.json'
      [System.IO.File]::WriteAllText($bf, $json, (New-Object System.Text.UTF8Encoding($false)))
      return Invoke-RestMethod -Uri $uri -Method $Method -Headers $Headers -ContentType 'application/json; charset=utf-8' -InFile $bf
    }
    return Invoke-RestMethod -Uri $uri -Method $Method -Headers $Headers
  } catch {
    $code = 0
    try { $code = [int]$_.Exception.Response.StatusCode } catch { }
    if ($Allow404 -and $code -eq 404) { return $null }
    $detail = ''
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream)
      $detail = $reader.ReadToEnd()
    } catch { }
    $hint = ''
    if ($code -eq 404) { $hint = "`n        （私有仓库要求令牌有 repo 权限；也可能是 owner/仓库名写错，当前：$Owner/$Repo）" }
    if ($code -eq 401) { $hint = "`n        （令牌无效或已过期，删掉旧凭据后重新推一次 Push-To-GitHub.cmd，或用 -Token 给新令牌）" }
    throw ("GitHub API $Method $Path 失败（HTTP $code）：$($_.Exception.Message)$hint`n$detail")
  }
}

# ------------------------------------------------------------------ 5. 建 Release
Step '4/5' "检查 tag 是否已在远端 …"
$remoteTag = Invoke-Api -Method Get -Path "/git/ref/tags/$([uri]::EscapeDataString($Tag))" -Allow404
if (-not $remoteTag) {
  Fail "远端还没有 tag「$Tag」。先双击 Push-To-GitHub.cmd 把 tag 推上去（它会 git push origin --tags），再回来发 Release"
}
Info "远端 tag 存在：$($remoteTag.object.sha.Substring(0,7))"

$existing = Invoke-Api -Method Get -Path "/releases/tags/$([uri]::EscapeDataString($Tag))" -Allow404
$payload = @{
  tag_name         = $Tag
  target_commitish = 'main'
  name             = $heading
  body             = $bodyText
  draft            = [bool]$Draft
  prerelease       = [bool]$Prerelease
}

if ($existing) {
  if (-not $Force) {
    Warn "这个 tag 已经有 Release 了（见下）。要覆盖说明 / 重传 APK，请加 -Force"
    Info $existing.html_url
    exit 0
  }
  Info "已存在 Release #$($existing.id)，-Force：覆盖说明"
  $rel = Invoke-Api -Method Patch -Path "/releases/$($existing.id)" -Body $payload
} else {
  Info '创建 Release …'
  $rel = Invoke-Api -Method Post -Path '/releases' -Body $payload
}

# ------------------------------------------------------------------ 6. 传 APK
if ($assetName) {
  Step '5/5' "上传 APK 附件 …"
  $existingAssets = Invoke-Api -Method Get -Path "/releases/$($rel.id)/assets"
  foreach ($a in @($existingAssets)) {
    if ($a -and $a.name -eq $assetName) {
      Info "删掉同名旧附件 #$($a.id)"
      Invoke-Api -Method Delete -Path "/releases/assets/$($a.id)" | Out-Null
    }
  }
  $uploadUrl = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($rel.id)/assets?name=" + [uri]::EscapeDataString($assetName)
  $up = $null
  try {
    $up = Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $Headers -ContentType 'application/vnd.android.package-archive' -InFile $apkPath
  } catch {
    $code = 0
    try { $code = [int]$_.Exception.Response.StatusCode } catch { }
    $detail = ''
    try {
      $stream = $_.Exception.Response.GetResponseStream()
      $reader = New-Object System.IO.StreamReader($stream)
      $detail = $reader.ReadToEnd()
    } catch { }
    Fail ("上传附件失败（HTTP $code）：$($_.Exception.Message)`n$detail")
  }
  if (-not $up -or $up.state -ne 'uploaded') { Fail ("附件状态异常：" + ($up | Out-String)) }
  Info "附件就绪：$($up.name) / $($up.size) 字节"
} else {
  Step '5/5' '跳过附件上传'
}

Write-Host ''
Write-Host ("  [OK] {0} 已发布" -f $Version) -ForegroundColor Green
Write-Host ("       页面    {0}" -f $rel.html_url)
if ($assetName) { Write-Host ("       附件    {0}（{1} 字节，sha256 前 16 位 {2}）" -f $assetName, $sizeText, $hash16) }
if ($Draft) { Write-Host '       注意    这是草稿（-Draft），别人看不到，需要你在网页上点 Publish' -ForegroundColor Yellow }
if ($Prerelease) { Write-Host '       注意    标成了预发布（-Prerelease）' -ForegroundColor Yellow }
Write-Host ("       说明    {0}" -f $notesSrc)
Write-Host ''