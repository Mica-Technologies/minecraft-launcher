# Cold-Start Measurement

Drives the launcher's built-in `ColdStartProfiler` (Java-side) from a
PowerShell wrapper that handles run management, then aggregates the
resulting CSV with a Python analyzer.

The scripts here are dev tooling — committed so the methodology is
reproducible across machines and not gated to a single developer's
local copy. Measurement output lands under `results/` which IS
gitignored (the stdout dumps include machine-specific paths and the
directory grows unboundedly).

## Quick start

```powershell
# Baseline: 10 cold starts + 1 discarded warmup
.\measure-cold-start.ps1 -Runs 10 -Label baseline -Warmup

# After making a change you want to test (rebuild + reinstall first)
.\measure-cold-start.ps1 -Runs 10 -Label myfix -Warmup

# Compare
python analyze.py .\results\baseline-*.csv .\results\myfix-*.csv
```

## How it works

The launcher contains `ColdStartProfiler.mark("phase_name")` calls at
seven cold-start waypoints: `main_entry`, `session_start`,
`locale_ready`, `logger_ready`, `auth_done`, `packs_loaded`,
`main_menu_painted`. The profiler is **off by default** — `mark()`
is a single static-final check + early return when the
`MMCL_PROFILE_OUTPUT` environment variable is not set, so the hooks
have negligible runtime cost in shipped builds.

The wrapper script:

1. Sets `MMCL_PROFILE_OUTPUT=<csv-path>` and `MMCL_EXIT_AFTER_PAINT=true`.
2. Kills any lingering launcher processes.
3. Launches the bundled exe via `Start-Process -Wait`.
4. The launcher self-terminates ~100 ms after the main menu first paints.
5. Repeats N times, appending CSV rows on each run.

## Files

| File | Purpose |
|------|---------|
| `measure-cold-start.ps1` | Windows wrapper script (drives N runs) |
| `analyze.py`             | Reads 1 or 2 CSVs, prints per-phase stats + comparison |
| `generate-appcds.ps1`    | Runs the launcher once with `-XX:ArchiveClassesAtExit` to dump an AppCDS archive for the optional `-SharedArchive` measurement |
| `results/`               | Output dir for CSV + log files (gitignored) |

## measure-cold-start.ps1 parameters

| Param | Default | Notes |
|-------|---------|-------|
| `-Runs N`           | 10 | Number of measured cold starts |
| `-Label name`       | "baseline" | Prefix for the output CSV filename |
| `-Exe path`         | `C:\Program Files\Mica Minecraft Launcher\Mica Minecraft Launcher.exe` | Override to point at a different build |
| `-InstallDir path`  | `C:\Program Files\Mica Minecraft Launcher` | Used in `-UseJavaDirect` mode to locate `runtime\bin\java.exe` + the cfg |
| `-OutputDir dir`    | `.\results` | Where to write the CSV + log |
| `-RunTimeoutSec N`  | 90 | Kill any run that takes longer than this |
| `-BetweenRunDelaySec N` | 2 | Sleep between runs so background work from the prior run can settle |
| `-Warmup`           | off | Add an extra discarded run before the measured ones (smooths first-cold-cache effects) |
| `-FlushCache`       | off | Best-effort working-set flush via `EmptyStandbyList.exe` / `RAMMap.exe` if installed |
| `-UseJavaDirect`    | off | Bypass the jpackage launcher.exe and invoke the bundled `java.exe` directly. Lets you add JVM flags (notably `-SharedArchive`) without admin write access to the per-system cfg file |
| `-SharedArchive path` | "" | Only with `-UseJavaDirect`: pass `-XX:SharedArchiveFile=<path>` to the JVM to measure AppCDS impact. Generate the archive first with `generate-appcds.ps1` |

## generate-appcds.ps1

Trains an AppCDS archive on top of the base CDS archive jlink emits.
Runs the bundled launcher once with `-XX:ArchiveClassesAtExit` set,
exits cleanly after the main menu paints (via the same
`MMCL_EXIT_AFTER_PAINT` flag the measurement loop uses), and writes
the resulting `.jsa` file to `results/launcher.jsa` by default.

After the archive is in place, re-measure via:

```powershell
.\measure-cold-start.ps1 -Runs 10 -Label cds -UseJavaDirect `
    -SharedArchive .\results\launcher.jsa -Warmup
```

## CSV format

Long format — one row per (run_id, phase):

```
ts_iso, run_id, jvm_start_epoch_ms, jdk_version, os, arch, cds_enabled, phase, offset_ms
```

`offset_ms` is the elapsed wall time from the launcher's JVM class-load
to when each `mark()` fired. Phase order is preserved within a run by
the order `mark()` was called.

## Reading the comparison output

The analyzer's comparison mode prints inter-phase deltas (time spent
*between* consecutive waypoints), not absolute offsets. A 50 ms median
in `locale_ready` means "it took 50 ms to get from `session_start` to
`locale_ready`."

Per-phase delta of `-100 ms` with `-15%` and a `*` marker indicates
that phase ran 10% or faster in the treatment than the baseline — a
strong signal worth committing the change for. A `!` marker on the
delta column means the phase regressed by 10% or more.
