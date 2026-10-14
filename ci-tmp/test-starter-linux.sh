#!/usr/bin/env bash
# TEMPORARY (tmp/starter-ci only): exercises the Linux starter, installed from the .deb, on a runner.
set -u
OUT="$PWD/starter-results"
mkdir -p "$OUT"
NAME="Mica Minecraft Launcher"
ROOT=/opt/mica-minecraft-launcher
BIN="$ROOT/bin/$NAME"
MODULES="$ROOT/lib/runtime/lib/modules"
LOG="$HOME/.MicaMinecraftLauncher/logs/startup.log"
SUMMARY="$OUT/summary.txt"

export DISPLAY=:99
Xvfb :99 -screen 0 1600x1000x24 >/dev/null 2>&1 &
sleep 3

shot() { import -window root "$OUT/$1.png" 2>>"$OUT/import.err" || true; }
starter_lines() { grep -c "Starter:" "$LOG" 2>/dev/null || echo 0; }
note() { echo "$*" | tee -a "$SUMMARY"; }
killall_app() { pkill -9 -f "$ROOT/bin/" || true; sleep 2; }

note "== install"
ls -la build/packaging | tee -a "$SUMMARY"
sudo apt-get install -y ./build/packaging/*.deb > "$OUT/apt-install.txt" 2>&1; note "deb install exit $?"
ls -la "$ROOT/bin" "$ROOT/lib/app" | tee -a "$SUMMARY"
cat "$ROOT/lib/app/$NAME Core.cfg" > "$OUT/core.cfg" 2>&1
find /usr/share/applications /opt -name '*.desktop' 2>/dev/null | xargs -r grep -H "^Exec" | tee -a "$SUMMARY"
file "$BIN" | tee -a "$SUMMARY"

note "== T1 normal launch, then SIGTERM like a logout"
before=$(starter_lines)
"$BIN" > "$OUT/t1-stdout.txt" 2> "$OUT/t1-stderr.txt" & pid=$!
sleep 45
shot t1-running
pgrep -fa "$ROOT" | tee -a "$SUMMARY"
ls /tmp/mmcl-starter-* 2>/dev/null | while read -r f; do note "state file $f: $(cat "$f")"; done
note "== T5 second launch hands off to the running one"
"$BIN" > /dev/null 2>&1; note "second launch exit $?"
kill -TERM "$pid"
wait "$pid"; note "starter exit after SIGTERM: $?"
sleep 3
shot t1-after-term
after=$(starter_lines); note "T1+T5 starter lines added (expect 0): $((after - before))"
note "appcds cache:"; ls -la "$HOME/.cache/MicaMinecraftLauncher/appcds" 2>&1 | tee -a "$SUMMARY"
find "$HOME" / -maxdepth 3 -name '$HOME' 2>/dev/null | tee -a "$SUMMARY"
grep -i "cds\|hs_err\|fatal" "$OUT/t1-stderr.txt" | head -20 | tee -a "$SUMMARY"
killall_app

note "== T2 missing runtime/lib/modules"
sudo mv "$MODULES" "$MODULES.bak"
"$BIN" & pid=$!
sleep 10
shot t2-missing
tail -n 1 "$LOG" | tee -a "$SUMMARY"
kill "$pid" 2>/dev/null; killall_app
sudo mv "$MODULES.bak" "$MODULES"

note "== T3 corrupt runtime/lib/modules (JVM fails before main)"
sudo cp "$MODULES" /tmp/modules.keep
echo corrupt | sudo tee "$MODULES" > /dev/null
"$BIN" & pid=$!
sleep 20
shot t3-corrupt
tail -n 1 "$LOG" | tee -a "$SUMMARY"
kill "$pid" 2>/dev/null; killall_app
sudo cp /tmp/modules.keep "$MODULES"

note "== T6 crash after the window is up (SIGSEGV to the JVM)"
before=$(starter_lines)
"$BIN" & pid=$!
sleep 40
jvm=$(pgrep -f "$ROOT/bin/$NAME Core" | tail -n 1)
note "killing JVM pid $jvm with SIGSEGV"
kill -SEGV "$jvm"
sleep 8
shot t6-crash
tail -n 1 "$LOG" | tee -a "$SUMMARY"
note "starter lines added (expect 1): $(( $(starter_lines) - before ))"
kill "$pid" 2>/dev/null; killall_app

note "== T4 --mcp relay with no launcher running (stdout must stay clean, no dialog)"
before=$(starter_lines)
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | timeout 60 "$BIN" --mcp > "$OUT/mcp-out.txt" 2> "$OUT/mcp-err.txt"
note "mcp exit $?; stdout bytes $(wc -c < "$OUT/mcp-out.txt"); starter lines added (expect 0): $(( $(starter_lines) - before ))"
cat "$OUT/mcp-err.txt" | tee -a "$SUMMARY"

cp "$LOG" "$OUT/startup.log" 2>/dev/null
cp -R "$HOME/.MicaMinecraftLauncher/logs" "$OUT/launcher-logs" 2>/dev/null
exit 0
