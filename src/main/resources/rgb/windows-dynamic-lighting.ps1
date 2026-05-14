# Mica Minecraft Launcher — Windows Dynamic Lighting helper.
#
# Spawned as a long-lived subprocess by WindowsDynamicLightingBackend.
# Loads WinRT projections, enumerates connected LampArray devices
# (Windows.Devices.Lights), and serves color commands from stdin until
# the parent sends EXIT or closes the pipe.
#
# Protocol (all line-oriented, UTF-8):
#   Child -> parent (on stdout):
#     READY <n>        # n LampArray devices found; ready to accept commands.
#     OK               # last command applied successfully.
#     NO_DEVICES       # WinRT loaded but no DL hardware on this machine.
#     ERROR <message>  # something went wrong; child will exit shortly.
#   Parent -> child (via stdin):
#     COLOR RRGGBB     # apply this color across every LampArray.
#     EXIT             # graceful shutdown — script restores black and exits.
#
# Why PowerShell at all? Java has no first-class WinRT bindings. The
# alternatives are a JNA-based COM/WinRT bridge (~1500 lines of
# HSTRING / RoActivateInstance / IAsyncOperation polling) or shipping
# a tiny native exe (adds a C# / Rust toolchain to the build).
# PowerShell is on every supported Windows install and can call WinRT
# projections directly — slower than a native helper but no new build
# dependencies.

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

# --- Load WinRT type projections -------------------------------------
try {
    $null = [Windows.Devices.Lights.LampArray,            Windows.Devices.Lights,      ContentType = WindowsRuntime]
    $null = [Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
    $null = [Windows.UI.Color,                              Windows.UI,                  ContentType = WindowsRuntime]
}
catch {
    Write-Output "ERROR Failed to load WinRT projections — likely a pre-Windows-10 host or PowerShell < 5.1"
    [Console]::Out.Flush()
    exit 1
}

# --- Synchronously await an IAsyncOperation --------------------------
# WinRT async ops project to PowerShell as System.IAsyncInfo. AsTask()
# turns them into a regular .NET Task we can Wait() on. WindowsRuntime-
# SystemExtensions ships with .NET Framework 4.5+ which is what PS 5.1
# runs on, so this just works on every supported Win10+/Win11 host.
function Await-Op {
    param( $asyncOp )
    $task = [System.WindowsRuntimeSystemExtensions]::AsTask( $asyncOp )
    $task.Wait()
    return $task.Result
}

# --- Enumerate LampArrays --------------------------------------------
try {
    $selector  = [Windows.Devices.Lights.LampArray]::GetDeviceSelector()
    $infosOp   = [Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync( $selector )
    $infos     = Await-Op $infosOp
}
catch {
    Write-Output "ERROR Device enumeration failed: $($_.Exception.Message)"
    [Console]::Out.Flush()
    exit 1
}

if ( $infos.Count -eq 0 ) {
    Write-Output "NO_DEVICES"
    [Console]::Out.Flush()
    exit 0
}

# --- Connect to each LampArray ---------------------------------------
$lamps = New-Object System.Collections.Generic.List[object]
foreach ( $info in $infos ) {
    try {
        $lampOp = [Windows.Devices.Lights.LampArray]::FromIdAsync( $info.Id )
        $lamp   = Await-Op $lampOp
        if ( $lamp -ne $null ) {
            $lamps.Add( $lamp ) | Out-Null
        }
    }
    catch {
        # One device's failure shouldn't kill enumeration of the rest.
        # The Java side sees the eventual READY count and decides what
        # to do.
    }
}

if ( $lamps.Count -eq 0 ) {
    Write-Output "NO_DEVICES"
    [Console]::Out.Flush()
    exit 0
}

Write-Output "READY $($lamps.Count)"
[Console]::Out.Flush()

# --- Command loop ----------------------------------------------------
while ( $true ) {
    $line = [Console]::In.ReadLine()
    if ( $line -eq $null )   { break }   # parent closed stdin
    if ( $line -eq 'EXIT' )  { break }
    if ( -not $line )        { continue }

    if ( $line -match '^COLOR ([0-9A-Fa-f]{6})$' ) {
        $hex = $matches[1]
        try {
            $r = [Convert]::ToByte( $hex.Substring( 0, 2 ), 16 )
            $g = [Convert]::ToByte( $hex.Substring( 2, 2 ), 16 )
            $b = [Convert]::ToByte( $hex.Substring( 4, 2 ), 16 )
            $color = [Windows.UI.Color]::FromArgb( 255, $r, $g, $b )
            foreach ( $lamp in $lamps ) {
                $lamp.SetColor( $color )
            }
            Write-Output 'OK'
        }
        catch {
            Write-Output "ERROR Color apply failed: $($_.Exception.Message)"
        }
        [Console]::Out.Flush()
    }
    else {
        Write-Output "ERROR Unknown command: $line"
        [Console]::Out.Flush()
    }
}

# --- Shutdown: paint final black so devices don't stay stuck --------
try {
    $black = [Windows.UI.Color]::FromArgb( 255, 0, 0, 0 )
    foreach ( $lamp in $lamps ) {
        $lamp.SetColor( $black )
    }
}
catch { }

exit 0
