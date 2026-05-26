# measure-cold-start.ps1
#
# Drives N back-to-back cold starts of the Mica Minecraft Launcher and
# captures phase timings via the launcher's built-in ColdStartProfiler.
#
# Two run modes:
#   default       - launches the jpackage-generated EXE (real user path)
#   -UseJavaDirect - launches the bundled JVM with the cfg's JVM options
#                    parsed out; lets us add -XX:SharedArchiveFile to
#                    compare AppCDS vs no-AppCDS without admin write
#                    access to the per-system cfg under Program Files.
#
# Usage:
#   .\measure-cold-start.ps1 -Runs 10 -Label baseline
#   .\measure-cold-start.ps1 -Runs 10 -Label baseline-java -UseJavaDirect
#   .\measure-cold-start.ps1 -Runs 10 -Label cds -UseJavaDirect -SharedArchive "E:\...\launcher.jsa"

param(
    [int]    $Runs       = 10,
    [string] $Label      = "baseline",
    [string] $Exe        = "C:\Program Files\Mica Minecraft Launcher\Mica Minecraft Launcher.exe",
    [string] $InstallDir = "C:\Program Files\Mica Minecraft Launcher",
    [string] $OutputDir  = (Join-Path $PSScriptRoot "results"),
    [int]    $RunTimeoutSec = 90,
    [int]    $BetweenRunDelaySec = 2,
    [switch] $Warmup,
    [switch] $FlushCache,
    [switch] $UseJavaDirect,
    [string] $SharedArchive = ""
)

$ErrorActionPreference = "Stop"

function Quote-Arg($s) {
    if ($s -match '[ "]') {
        '"' + ($s -replace '"', '\"') + '"'
    } else {
        $s
    }
}

if ($UseJavaDirect) {
    $javaExe = Join-Path $InstallDir "runtime\bin\java.exe"
    $appDir  = Join-Path $InstallDir "app"
    $cfgFile = Join-Path $appDir   "Mica Minecraft Launcher.cfg"

    if (-not (Test-Path -LiteralPath $javaExe)) { Write-Error "Bundled java.exe not found: $javaExe" }
    if (-not (Test-Path -LiteralPath $cfgFile))  { Write-Error "Launcher cfg not found: $cfgFile" }

    $cfgLines = Get-Content -LiteralPath $cfgFile
    $mainJar = $null; $mainClass = $null; $jvmOpts = New-Object System.Collections.Generic.List[string]
    $section = ""
    foreach ($line in $cfgLines) {
        if ($line -match '^\s*\[(.+)\]\s*$') { $section = $matches[1]; continue }
        if ($section -eq "Application") {
            if ($line -match '^\s*app\.classpath\s*=\s*\$APPDIR[\\\/](.+)$') { $mainJar = $matches[1].Trim() }
            if ($line -match '^\s*app\.mainclass\s*=\s*(.+)$')            { $mainClass = $matches[1].Trim() }
        }
        elseif ($section -eq "JavaOptions") {
            if ($line -match '^\s*java-options\s*=\s*(.+)$') {
                $opt = $matches[1].Trim()
                # Skip any pre-existing SharedArchiveFile / ArchiveClassesAtExit from cfg —
                # we own those for this measurement.
                if ($opt -notmatch '^-XX:(SharedArchiveFile|ArchiveClassesAtExit)=') {
                    $jvmOpts.Add($opt) | Out-Null
                }
            }
        }
    }
    if (-not $mainJar)   { Write-Error "Could not parse app.classpath from cfg" }
    if (-not $mainClass) { Write-Error "Could not parse app.mainclass from cfg" }
    $jarPath = Join-Path $appDir $mainJar

    $jvmArgs = New-Object System.Collections.Generic.List[string]
    foreach ($o in $jvmOpts) { $jvmArgs.Add($o) | Out-Null }
    if ($SharedArchive -ne "") {
        if (-not (Test-Path -LiteralPath $SharedArchive)) { Write-Error "SharedArchive not found: $SharedArchive" }
        $jvmArgs.Add("-XX:SharedArchiveFile=$SharedArchive") | Out-Null
    }
    $jvmArgs.Add("-cp") | Out-Null
    $jvmArgs.Add($jarPath) | Out-Null
    $jvmArgs.Add($mainClass) | Out-Null

    $quotedArgs = ($jvmArgs | ForEach-Object { Quote-Arg $_ }) -join ' '
}
else {
    if (-not (Test-Path -LiteralPath $Exe)) {
        Write-Error "Launcher executable not found: $Exe"
    }
}

if (-not (Test-Path -LiteralPath $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$csvPath = Join-Path $OutputDir ("{0}-{1}.csv" -f $Label, $timestamp)
$logPath = Join-Path $OutputDir ("{0}-{1}.log" -f $Label, $timestamp)

Write-Host ""
Write-Host "=== Mica Minecraft Launcher cold-start measurement ===" -ForegroundColor Cyan
if ($UseJavaDirect) {
    Write-Host "  Mode:     java.exe direct (cfg-parsed args)"
    Write-Host "  Java:     $javaExe"
    Write-Host "  Jar:      $jarPath"
    if ($SharedArchive -ne "") { Write-Host "  SharedArchiveFile: $SharedArchive" }
}
else {
    Write-Host "  Mode:     launcher.exe (jpackage wrapper)"
    Write-Host "  Exe:      $Exe"
}
Write-Host "  Runs:     $Runs ($(if ($Warmup) { '+1 warmup (discarded)' } else { 'no warmup' }))"
Write-Host "  Label:    $Label"
Write-Host "  CSV out:  $csvPath"
Write-Host "  Log:      $logPath"
Write-Host ""

function Stop-LingeringLauncher {
    $candidates = Get-Process -Name "Mica Minecraft Launcher" -ErrorAction SilentlyContinue
    $candidates += Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "*Mica Minecraft Launcher*" }
    if ($candidates) {
        $candidates | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

function Invoke-CacheFlush {
    if (-not $FlushCache) { return }
    $tool = Get-Command "EmptyStandbyList.exe" -ErrorAction SilentlyContinue
    if (-not $tool) { $tool = Get-Command "RAMMap.exe" -ErrorAction SilentlyContinue }
    if ($tool) { & $tool.Source workingsets standbylist 2>&1 | Out-Null }
    else { Write-Host "  (No cache-flush tool on PATH; -FlushCache had no effect.)" -ForegroundColor Yellow }
}

function Invoke-OneRun {
    param([int]$Index, [string]$CsvOutputPath, [bool]$IsWarmup)

    Stop-LingeringLauncher
    Invoke-CacheFlush

    $env:MMCL_PROFILE_OUTPUT   = $CsvOutputPath
    $env:MMCL_EXIT_AFTER_PAINT = "true"

    $tag = if ($IsWarmup) { "warmup" } else { "run $Index/$Runs" }
    $startWall = Get-Date
    Write-Host ("  [{0:HH:mm:ss}] {1,-10} ..." -f $startWall, $tag) -NoNewline

    if ($UseJavaDirect) {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $javaExe
        $psi.Arguments = $quotedArgs
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $proc = [System.Diagnostics.Process]::Start($psi)
        $proc.StandardOutput.ReadToEndAsync() | Out-Null
        $proc.StandardError.ReadToEndAsync()  | Out-Null
        $exited = $proc.WaitForExit($RunTimeoutSec * 1000)
    }
    else {
        $proc = Start-Process -FilePath $Exe -PassThru -WindowStyle Normal
        $exited = $proc.WaitForExit($RunTimeoutSec * 1000)
    }
    $endWall = Get-Date
    $wallMs = [int]($endWall - $startWall).TotalMilliseconds

    if (-not $exited) {
        Write-Host " TIMEOUT ($RunTimeoutSec s); killing" -ForegroundColor Red
        try { $proc.Kill() } catch { }
        Stop-LingeringLauncher
        Add-Content -Path $logPath -Value "run $Index timed out after $RunTimeoutSec s"
        return $false
    }

    $exitCode = $proc.ExitCode
    Write-Host (" done in {0,5} ms (exit {1})" -f $wallMs, $exitCode)
    Add-Content -Path $logPath -Value ("run {0}: wall {1} ms, exit {2}" -f $Index, $wallMs, $exitCode)
    return $true
}

if ($Warmup) {
    $warmCsv = Join-Path $OutputDir ".warmup-discard.csv"
    if (Test-Path -LiteralPath $warmCsv) { Remove-Item -LiteralPath $warmCsv -Force }
    Invoke-OneRun -Index 0 -CsvOutputPath $warmCsv -IsWarmup $true | Out-Null
    if (Test-Path -LiteralPath $warmCsv) { Remove-Item -LiteralPath $warmCsv -Force }
    Start-Sleep -Seconds $BetweenRunDelaySec
}

$succeeded = 0; $failed = 0
for ($i = 1; $i -le $Runs; $i++) {
    $ok = Invoke-OneRun -Index $i -CsvOutputPath $csvPath -IsWarmup $false
    if ($ok) { $succeeded++ } else { $failed++ }
    if ($i -lt $Runs) { Start-Sleep -Seconds $BetweenRunDelaySec }
}

Remove-Item Env:MMCL_PROFILE_OUTPUT -ErrorAction SilentlyContinue
Remove-Item Env:MMCL_EXIT_AFTER_PAINT -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
Write-Host ("  Succeeded:  {0}/{1}" -f $succeeded, $Runs)
if ($failed -gt 0) { Write-Host ("  Failed:     {0}" -f $failed) -ForegroundColor Yellow }
Write-Host "  CSV:        $csvPath"

if (Test-Path -LiteralPath $csvPath) {
    $lineCount = (Get-Content -LiteralPath $csvPath | Measure-Object -Line).Lines
    Write-Host ("  CSV rows:   {0} (incl. header)" -f $lineCount)
    $analyzer = Join-Path $PSScriptRoot "analyze.py"
    if (Test-Path -LiteralPath $analyzer) {
        Write-Host ""
        Write-Host "Run the analyzer:" -ForegroundColor Cyan
        Write-Host ("  python `"$analyzer`" `"$csvPath`"") -ForegroundColor Gray
    }
}
else {
    Write-Host "  CSV was not created -- did the launcher hit the main menu?" -ForegroundColor Red
}
