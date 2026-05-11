# Emit a monotonic 16-bit number to stdout, used as the 4th component of the
# Burn bundle's Version. Maven (pom.xml windows-packaging profile) captures
# this via Ant exec.outputproperty and passes it to candle as
# -dBundleBuildRevision, which bundle.wxs splices into Bundle/@Version.
#
# Encoding: minutes since 2024-01-01 UTC, mod 65536. 1-minute granularity is
# enough to distinguish any two builds done at least a minute apart; the wrap
# happens every ~45 days, which is well outside a typical dev iteration loop
# but keeps the value safely inside the .NET 16-bit Version-component limit
# that Burn's a.b.c.d parser enforces.
#
# Lives in its own file (rather than inline `-Command "..."` in pom.xml)
# because Ant's <exec> arg-joining on Windows splits the inline script at
# the first space, which mangles the parse and silently yields 0.

$epoch = [DateTime]::new(2024, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
$minutes = [Math]::Floor((([DateTime]::UtcNow - $epoch).TotalMinutes))
Write-Host ($minutes % 65536)
