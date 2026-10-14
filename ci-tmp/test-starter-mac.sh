#!/usr/bin/env bash
# TEMPORARY (tmp/starter-ci only): exercises the macOS starter on a GitHub runner.
set -u
OUT="$PWD/starter-results"
mkdir -p "$OUT"
NAME="Mica Minecraft Launcher"
mkdir -p /tmp/t
rm -rf "/tmp/t/$NAME.app"
cp -R "build/target/app-image/$NAME.app" /tmp/t/
APP="/tmp/t/$NAME.app"
BIN="$APP/Contents/MacOS/$NAME"
MODULES="$APP/Contents/runtime/Contents/Home/lib/modules"
LOG="$HOME/.MicaMinecraftLauncher/logs/startup.log"
SUMMARY="$OUT/summary.txt"

shot() { screencapture -x "$OUT/$1.png" 2>>"$OUT/screencapture.err" || true; }
starter_lines() { local n; n=$(grep -c "Starter:" "$LOG" 2>/dev/null); echo "${n:-0}"; }
note() { echo "$*" | tee -a "$SUMMARY"; }
procs() { pgrep -fl "$NAME" || true; }
killall_app() { pkill -9 -f "Contents/MacOS/$NAME" || true; sleep 2; }

note "== layout"
ls -la "$APP/Contents/MacOS" "$APP/Contents/app" | tee -a "$SUMMARY"
cat "$APP/Contents/app/$NAME Core.cfg" > "$OUT/core.cfg" 2>&1
codesign --verify --deep --strict --verbose=2 "$APP" > "$OUT/codesign.txt" 2>&1; note "codesign verify exit $?"
file "$BIN" "$BIN Core" | tee -a "$SUMMARY"
ls -la build/packaging | tee -a "$SUMMARY"

note "== T1 normal launch via Launch Services, quit with an Apple event"
before=$(starter_lines)
open -n "$APP"
sleep 45
shot t1-running
procs > "$OUT/t1-procs.txt"
note "processes while running:"; cat "$OUT/t1-procs.txt" | tee -a "$SUMMARY"
ls "${TMPDIR:-/tmp}"/mmcl-starter-* 2>/dev/null | while read -r f; do note "state file $f: $(cat "$f")"; done
note "== T5 second launch hands off to the running one (exits 0 before any window)"
"$BIN"; note "second launch exit $?"
sleep 5
shot t5-after-second-launch
osascript -e "quit app \"$NAME\"" 2>&1 | tee -a "$SUMMARY"
sleep 10
pkill -TERM -f "Contents/MacOS/$NAME Core" && note "quit declined; sent SIGTERM to Core"
sleep 20
shot t1-after-quit
note "processes after quit:"; procs | tee -a "$SUMMARY"
after=$(starter_lines); note "T1+T5 starter lines added (expect 0): $((after - before))"
note "appcds cache:"; ls -la "$HOME/Library/Caches/MicaMinecraftLauncher/appcds" 2>&1 | tee -a "$SUMMARY"
find "$HOME" -maxdepth 3 -name '$HOME' 2>/dev/null | tee -a "$SUMMARY"
killall_app

note "== T2 missing runtime/lib/modules"
mv "$MODULES" "$MODULES.bak"
"$BIN" & pid=$!
sleep 10
shot t2-missing
tail -n 2 "$LOG" | tee -a "$SUMMARY"
kill "$pid" 2>/dev/null; killall_app
mv "$MODULES.bak" "$MODULES"

note "== T3 corrupt runtime/lib/modules (JVM fails before main)"
cp "$MODULES" "$OUT/../modules.keep"
echo corrupt > "$MODULES"
"$BIN" &
sleep 25
shot t3-corrupt
tail -n 1 "$LOG" | tee -a "$SUMMARY"
killall_app
mv "$OUT/../modules.keep" "$MODULES"

note "== T4 --mcp relay with no launcher running (stdout must stay clean, no dialog)"
before=$(starter_lines)
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | "$BIN" --mcp > "$OUT/mcp-out.txt" 2> "$OUT/mcp-err.txt"
note "mcp exit $?; stdout bytes $(wc -c < "$OUT/mcp-out.txt"); starter lines added (expect 0): $(( $(starter_lines) - before ))"
cat "$OUT/mcp-err.txt" | tee -a "$SUMMARY"

cp "$LOG" "$OUT/startup.log" 2>/dev/null
cp -R "$HOME/.MicaMinecraftLauncher/logs" "$OUT/launcher-logs" 2>/dev/null
ls -la "$HOME/Library/Logs/DiagnosticReports" > "$OUT/diagnostic-reports.txt" 2>&1
exit 0
