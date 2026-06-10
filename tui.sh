#!/usr/bin/env bash
#
# Build (if needed) and run the launcher's terminal UI (--cli) inline in THIS terminal.
#
# The native Lanterna UnixTerminal needs a real controlling TTY (it shells out to
# `stty` against /dev/tty), so run this from Terminal.app / iTerm — NOT from
# IntelliJ's run console, which has no controlling terminal and therefore falls
# back to the windowed Swing emulator.
#
# Usage:
#   ./tui.sh          # reuse the existing fat JAR (builds it if missing)
#   ./tui.sh -b       # force a fresh build first (use after code changes)
#
set -euo pipefail
cd "$(dirname "$0")"

JAR="build/target/launcher-0.0.0-jar-with-dependencies.jar"
MVN="${MVN:-$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn}"
export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/azul-26.0.1/Contents/Home}"

if [[ "${1:-}" == "-b" || "${1:-}" == "--build" || ! -f "$JAR" ]]; then
    echo "Building fat JAR (skipping native packaging / DMG step)…"
    # -Dexec.skip=true skips the jpackage-* installer steps, which otherwise fail
    # on the 0.0.0 dev revision ("first number in an app-version cannot be zero").
    "$MVN" -B -Drevision=0.0.0 package -DskipTests -Dexec.skip=true
fi

exec java \
    --enable-native-access=ALL-UNNAMED \
    --enable-final-field-mutation=ALL-UNNAMED \
    -jar "$JAR" --cli
