# generate-appcds.ps1
#
# Runs the bundled launcher once with -XX:ArchiveClassesAtExit to dump an
# AppCDS archive (.jsa file) that captures the classes loaded during a
# representative cold-start through to main-menu-painted.
#
# Reads the same MMCL_PROFILE_OUTPUT + MMCL_EXIT_AFTER_PAINT env vars as
# the regular measurement loop, so the launcher self-terminates after the
# main menu paints and the JVM writes the archive on its way out.

param(
    [string] $InstallDir = "C:\Program Files\Mica Minecraft Launcher",
    [string] $JsaPath    = (Join-Path $PSScriptRoot "results\launcher.jsa")
)

$ErrorActionPreference = "Stop"

$javaExe = Join-Path $InstallDir "runtime\bin\java.exe"
$appDir  = Join-Path $InstallDir "app"
$cfgFile = Join-Path $appDir   "Mica Minecraft Launcher.cfg"

if (-not (Test-Path -LiteralPath $javaExe)) { Write-Error "Bundled java.exe not found: $javaExe" }
if (-not (Test-Path -LiteralPath $cfgFile))  { Write-Error "Launcher cfg not found: $cfgFile" }

# Parse the cfg to discover the actual main JAR filename + JVM options.
$cfgLines = Get-Content -LiteralPath $cfgFile
$mainJar = $null
$mainClass = $null
$jvmOpts = New-Object System.Collections.Generic.List[string]
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
            if ($opt -notmatch '^-XX:SharedArchiveFile=') {
                $jvmOpts.Add($opt) | Out-Null
            }
        }
    }
}
if (-not $mainJar) { Write-Error "Could not parse app.classpath from cfg" }
if (-not $mainClass) { Write-Error "Could not parse app.mainclass from cfg" }

$jarPath = Join-Path $appDir $mainJar

$jsaDir = Split-Path $JsaPath -Parent
if (-not (Test-Path -LiteralPath $jsaDir)) { New-Item -ItemType Directory -Path $jsaDir | Out-Null }
if (Test-Path -LiteralPath $JsaPath) {
    Write-Host "Removing existing archive: $JsaPath"
    Remove-Item -LiteralPath $JsaPath -Force
}

$profileCsv = Join-Path $jsaDir "appcds-training.csv"
if (Test-Path -LiteralPath $profileCsv) { Remove-Item -LiteralPath $profileCsv -Force }

$env:MMCL_PROFILE_OUTPUT   = $profileCsv
$env:MMCL_EXIT_AFTER_PAINT = "true"

# Build the argument array. The call operator (&) handles paths-with-spaces
# correctly when arguments are passed as an array — unlike Start-Process,
# which silently drops quoting on -ArgumentList items.
$argList = @()
foreach ($o in $jvmOpts) { $argList += $o }
$argList += "-XX:ArchiveClassesAtExit=$JsaPath"
$argList += "-cp"
$argList += $jarPath
$argList += $mainClass

Write-Host ""
Write-Host "=== AppCDS training run ===" -ForegroundColor Cyan
Write-Host "  java:       $javaExe"
Write-Host "  jar:        $jarPath"
Write-Host "  main:       $mainClass"
Write-Host "  jsa out:    $JsaPath"
Write-Host "  jvm opts:"
foreach ($o in $jvmOpts) { Write-Host "      $o" }
Write-Host "      -XX:ArchiveClassesAtExit=$JsaPath"
Write-Host ""

$start = Get-Date
$stdoutPath = Join-Path $jsaDir "appcds-training.out"
$stderrPath = Join-Path $jsaDir "appcds-training.err"

# PowerShell 5.1's call operator drops quoting on native-command args that
# contain spaces. Use System.Diagnostics.Process with a manually-quoted
# argument string to make sure -cp "C:\Program Files\..." stays intact.
function Quote-Arg($s) {
    if ($s -match '[ "]') {
        '"' + ($s -replace '"', '\"') + '"'
    } else {
        $s
    }
}
$quoted = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javaExe
$psi.Arguments = $quoted
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.WorkingDirectory = $jsaDir
$proc = [System.Diagnostics.Process]::Start($psi)
$stdoutTask = $proc.StandardOutput.ReadToEndAsync()
$stderrTask = $proc.StandardError.ReadToEndAsync()
$proc.WaitForExit()
$stdoutText = $stdoutTask.Result
$stderrText = $stderrTask.Result
Set-Content -LiteralPath $stdoutPath -Value $stdoutText -Encoding UTF8
Set-Content -LiteralPath $stderrPath -Value $stderrText -Encoding UTF8
$javaExit = $proc.ExitCode
$wallMs = [int]((Get-Date) - $start).TotalMilliseconds

Remove-Item Env:MMCL_PROFILE_OUTPUT -ErrorAction SilentlyContinue
Remove-Item Env:MMCL_EXIT_AFTER_PAINT -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Training run finished in $wallMs ms (exit $javaExit)"

if (-not (Test-Path -LiteralPath $JsaPath)) {
    Write-Host "ERROR: archive was not created!" -ForegroundColor Red
    Write-Host "  stdout tail:"
    Get-Content $stdoutPath -Tail 40 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    exit 1
}

$jsaInfo = Get-Item -LiteralPath $JsaPath
Write-Host "Archive created: $JsaPath" -ForegroundColor Green
Write-Host ("  size: {0:N1} MB" -f ($jsaInfo.Length / 1MB))
