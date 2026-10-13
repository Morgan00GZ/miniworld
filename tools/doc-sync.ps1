<#
doc-sync.ps1 - batch, verified text replacement for docs (no full-file dumps, tiny output).

Anchor file format (UTF-8, one edit per line):
  relative/path.md ||| old text ||| new text
  - "old text" must appear exactly once in the file (otherwise AMBIGUOUS / NOTFOUND).
  - leave "new text" empty to delete the old text.
  - lines starting with # and blank lines are ignored.
  - all three fields are single-line; leading/trailing spaces of every field are trimmed.

Usage:
  pwsh -File tools\doc-sync.ps1 -Anchors tools\doc-anchors.txt -Check   # report only
  pwsh -File tools\doc-sync.ps1 -Anchors tools\doc-anchors.txt          # apply + read-back verify

Output per row: OK / OK(check) / NOTFOUND / AMBIGUOUS(n) / NOFILE / BADFORMAT / VERIFYFAIL, then one summary line.
#>
param(
  [Parameter(Mandatory = $true)][string]$Anchors,
  [switch]$Check
)

$ErrorActionPreference = 'Stop'
function Say($m) { Write-Host $m }

$repo = Split-Path -Parent $PSScriptRoot
$utf8 = New-Object Text.UTF8Encoding($false)
if (-not (Test-Path $Anchors)) { throw "anchors file not found: $Anchors" }

$rows = @()
$i = 0
foreach ($line in (Get-Content $Anchors -Encoding UTF8)) {
  $i++
  if (-not $line.Trim()) { continue }
  if ($line.TrimStart().StartsWith('#')) { continue }
  $p = $line -split '\|\|\|'
  if ($p.Count -lt 2) { Say ("row {0}: BADFORMAT" -f $i); continue }
  $rows += [pscustomobject]@{
    Line = $i
    File = $p[0].Trim()
    Old  = $p[1].Trim()
    New  = $(if ($p.Count -ge 3) { $p[2].Trim() } else { '' })
  }
}

$ok = 0; $bad = 0
foreach ($r in $rows) {
  $path = Join-Path $repo $r.File
  if ($r.Old -eq '') { Say ("row {0} {1}: BADFORMAT(empty old)" -f $r.Line, $r.File); $bad++; continue }
  if (-not (Test-Path $path)) { Say ("row {0} {1}: NOFILE" -f $r.Line, $r.File); $bad++; continue }
  $txt = [IO.File]::ReadAllText($path)
  $n = ([regex]::Matches($txt, [regex]::Escape($r.Old))).Count
  if ($n -eq 0) { Say ("row {0} {1}: NOTFOUND" -f $r.Line, $r.File); $bad++; continue }
  if ($n -gt 1) { Say ("row {0} {1}: AMBIGUOUS({2})" -f $r.Line, $r.File, $n); $bad++; continue }
  if ($Check) { Say ("row {0} {1}: OK(check)" -f $r.Line, $r.File); $ok++; continue }
  $out = $txt.Replace($r.Old, $r.New)
  [IO.File]::WriteAllText($path, $out, $utf8)
  $back = [IO.File]::ReadAllText($path)
  $good = if ($r.New -eq '') { -not $back.Contains($r.Old) } else { $back.Contains($r.New) }
  if ($good) { Say ("row {0} {1}: OK" -f $r.Line, $r.File); $ok++ }
  else { Say ("row {0} {1}: VERIFYFAIL" -f $r.Line, $r.File); $bad++ }
}
Say ("summary: rows={0} ok={1} problem={2} mode={3}" -f $rows.Count, $ok, $bad, $(if ($Check) { 'check' } else { 'apply' }))
