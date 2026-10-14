# Copyright (c) 2026 Mica Technologies
#
# This program is free software: you can redistribute it and/or modify it
# under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License,
# or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but
# WITHOUT ANY WARRANTY; without even the implied warranty
# of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
# See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://www.gnu.org/licenses/>.

<#
.SYNOPSIS
  Builds the native starter (starter.c) and puts it in front of the jpackage launcher.

.DESCRIPTION
  Run by the windows-packaging profile between the jpackage app-image step and the two MSI
  steps. In the app image it renames "<Name>.exe" to "<Name> Core.exe" (and its app\<Name>.cfg
  to match, since the jpackage launcher finds its config by its own file name), then installs
  the starter as "<Name>.exe", so every shortcut, pin and URL handler runs the starter.

  Generates two headers first: starter_build.h (names, version, upgrade code, icon) and
  starter_strings.h (the starter.* strings from every resource bundle, so the starter speaks the
  launcher's languages). Compiles with the newest Visual Studio C++ toolset vswhere finds.

  Windows PowerShell 5.1 compatible: CI runs it through powershell.exe.
#>
param(
    [Parameter(Mandatory = $true)] [string] $AppImage,
    [Parameter(Mandatory = $true)] [string] $Name,
    [Parameter(Mandatory = $true)] [string] $Version,
    [Parameter(Mandatory = $true)] [string] $Environment,
    [Parameter(Mandatory = $true)] [string] $Icon,
    [Parameter(Mandatory = $true)] [string] $UpgradeCode,
    [Parameter(Mandatory = $true)] [string] $LangDir,
    [Parameter(Mandatory = $true)] [string] $WorkDir
)

$ErrorActionPreference = 'Stop'
$source = $PSScriptRoot

# Order defines the enum in starter_strings.h; starter.c refers to these by name.
$keys = @(
    'starter.failed.heading',
    'starter.failed.body',
    'starter.missing.body',
    'starter.crashed.heading',
    'starter.crashed.body',
    'starter.button.repair',
    'starter.button.repair.note',
    'starter.button.logs',
    'starter.details.show',
    'starter.details.hide',
    'starter.details.exitCode',
    'starter.details.output',
    'starter.repair.heading',
    'starter.repair.failed',
    'starter.repair.notFound'
)

function EnumName([string] $key)
{
    $parts = $key.Substring('starter.'.Length).Split('.') | ForEach-Object {
        ([regex]::Replace($_, '([a-z])([A-Z])', '$1_$2')).ToUpperInvariant()
    }
    return 'S_' + ($parts -join '_')
}

# Java .properties value escapes -> the actual string.
function Unescape([string] $raw)
{
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $raw.Length) {
        $c = $raw[$i]
        if ($c -ne '\' -or $i + 1 -ge $raw.Length) { [void]$sb.Append($c); $i++; continue }
        $n = $raw[$i + 1]
        switch -CaseSensitive ($n) {
            'u' { [void]$sb.Append([char][Convert]::ToInt32($raw.Substring($i + 2, 4), 16)); $i += 6; continue }
            'n' { [void]$sb.Append("`n"); $i += 2; continue }
            't' { [void]$sb.Append("`t"); $i += 2; continue }
            'r' { $i += 2; continue }
            default { [void]$sb.Append($n); $i += 2; continue }
        }
    }
    return $sb.ToString()
}

# A string -> a C wide literal: ASCII as is, everything else as a universal character name.
function CLiteral([string] $s)
{
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('L"')
    for ($i = 0; $i -lt $s.Length; $i++) {
        $c = [int]$s[$i]
        if ($c -ge 0xD800 -and $c -le 0xDBFF -and $i + 1 -lt $s.Length) {
            $code = [char]::ConvertToUtf32($s[$i], $s[$i + 1])
            [void]$sb.Append(('\U{0:X8}' -f $code)); $i++
        }
        elseif ($c -eq 0x5C) { [void]$sb.Append('\\') }
        elseif ($c -eq 0x22) { [void]$sb.Append('\"') }
        elseif ($c -eq 0x0A) { [void]$sb.Append('\n') }
        elseif ($c -eq 0x09) { [void]$sb.Append('\t') }
        elseif ($c -ge 0x20 -and $c -le 0x7E) { [void]$sb.Append([char]$c) }
        elseif ($c -ge 0xA0) { [void]$sb.Append(('\u{0:X4}' -f $c)) }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function ReadBundle([string] $file)
{
    $values = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($file, [System.Text.Encoding]::UTF8)) {
        if (-not $line.StartsWith('starter.')) { continue }
        $eq = $line.IndexOf('=')
        if ($eq -lt 0) { continue }
        $values[$line.Substring(0, $eq).Trim()] = Unescape $line.Substring($eq + 1)
    }
    return $values
}

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# ---- starter_strings.h --------------------------------------------------------------------
$root = Join-Path $LangDir 'DisplayStrings.properties'
$bundles = @(@{ Tag = 'en'; File = $root })
Get-ChildItem -Path $LangDir -Filter 'DisplayStrings_*.properties' | Sort-Object Name | ForEach-Object {
    $tag = $_.BaseName.Substring('DisplayStrings_'.Length).Replace('_', '-')
    $bundles += @{ Tag = $tag; File = $_.FullName }
}
$english = ReadBundle $root
foreach ($key in $keys) {
    if (-not $english.ContainsKey($key)) { throw "DisplayStrings.properties has no '$key'" }
}

$h = New-Object System.Text.StringBuilder
[void]$h.AppendLine('/* Generated by build-starter.ps1 from src/main/resources/lang. Do not edit. */')
[void]$h.AppendLine('#pragma once')
[void]$h.AppendLine('enum StarterString {')
foreach ($key in $keys) { [void]$h.AppendLine("    $(EnumName $key),") }
[void]$h.AppendLine('    S_COUNT')
[void]$h.AppendLine('};')
[void]$h.AppendLine('typedef struct { const wchar_t *tag; const wchar_t *s[ S_COUNT ]; } StarterLocale;')
[void]$h.AppendLine("#define STARTER_LOCALE_COUNT $($bundles.Count)")
[void]$h.AppendLine('static const StarterLocale STARTER_LOCALES[] = {')
foreach ($b in $bundles) {
    $values = ReadBundle $b.File
    $items = foreach ($key in $keys) {
        if ($values.ContainsKey($key)) { CLiteral $values[$key] } else { 'NULL' }
    }
    [void]$h.AppendLine("    { L`"$($b.Tag)`", {")
    [void]$h.AppendLine('        ' + ($items -join ",`n        "))
    [void]$h.AppendLine('    } },')
}
[void]$h.AppendLine('};')
[System.IO.File]::WriteAllText((Join-Path $WorkDir 'starter_strings.h'), $h.ToString(), (New-Object System.Text.UTF8Encoding($false)))

# ---- starter_build.h ----------------------------------------------------------------------
$parts = @($Version.Split('.') | ForEach-Object { [int]$_ })
while ($parts.Count -lt 4) { $parts += 0 }
$isDev = $Environment -eq 'DEV'
$dataFolder = if ($isDev) { '.MicaMinecraftLauncherDEV' } else { '.MicaMinecraftLauncher' }
$upgrade = '{' + $UpgradeCode.Trim('{', '}').ToUpperInvariant() + '}'
$iconC = (Resolve-Path $Icon).Path.Replace('\', '\\')
$build = @"
/* Generated by build-starter.ps1. Do not edit. */
#pragma once
#define MMCL_APP_NAME L"$Name"
#define MMCL_APP_NAME_A "$Name"
#define MMCL_EXE_NAME_A "$Name.exe"
#define MMCL_DATA_FOLDER L"$dataFolder"
#define MMCL_UPGRADE_CODE L"$upgrade"
#define MMCL_VERSION_STRING "$Version"
#define MMCL_VERSION_BINARY $($parts[0]),$($parts[1]),$($parts[2]),$($parts[3])
#define MMCL_ICON_PATH "$iconC"

"@
[System.IO.File]::WriteAllText((Join-Path $WorkDir 'starter_build.h'), $build, (New-Object System.Text.UTF8Encoding($false)))

# ---- compile ------------------------------------------------------------------------------
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) { throw "vswhere.exe not found; the starter needs the Visual Studio C++ build tools" }
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw "No Visual Studio installation with the x64 C++ build tools was found" }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'

Copy-Item (Join-Path $source 'starter.c'), (Join-Path $source 'starter.rc'), (Join-Path $source 'starter.manifest') $WorkDir -Force
$exe = Join-Path $WorkDir 'starter.exe'
$commands = @(
    "set `"PATH=$(Split-Path $vswhere);%PATH%`"",
    "call `"$vcvars`" >nul",
    'rc /nologo /fo starter.res starter.rc',
    'cl /nologo /O1 /W4 /WX /utf-8 /MT /GS /guard:cf /sdl starter.c starter.res /Fe:starter.exe /link /SUBSYSTEM:WINDOWS /DYNAMICBASE /NXCOMPAT /guard:cf'
) -join ' && '
Push-Location $WorkDir
try {
    & cmd.exe /d /c $commands
    if ($LASTEXITCODE -ne 0) { throw "Building the starter failed (exit code $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

# ---- stage into the app image -------------------------------------------------------------
$main = Join-Path $AppImage "$Name.exe"
$core = Join-Path $AppImage "$Name Core.exe"
$cfg = Join-Path $AppImage "app\$Name.cfg"
$coreCfg = Join-Path $AppImage "app\$Name Core.cfg"
if (-not (Test-Path $core)) {
    if (-not (Test-Path $main)) { throw "The app image has no '$Name.exe' to put the starter in front of" }
    Move-Item $main $core
    Move-Item $cfg $coreCfg
}
Copy-Item $exe $main -Force
Write-Host "Starter installed: $main -> $core"
