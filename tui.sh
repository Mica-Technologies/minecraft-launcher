#!/usr/bin/env bash
#
# Build (if needed) and run the launcher's terminal UI (--cli) inline in THIS terminal.
#
# The native Lanterna UnixTerminal needs a real controlling TTY (it shells out to
# `stty` against /dev/tty), so run this from a real terminal emulator (macOS:
# Terminal.app / iTerm; Linux: GNOME Terminal, Konsole, xterm, …) — NOT from
# IntelliJ's run console, which has no controlling terminal and therefore falls
# back to the windowed Swing emulator.
#
# Maven and the JDK aren't on PATH; we default to IntelliJ's bundled Maven and
# the IntelliJ-managed Azul 26 JDK per the platform conventions in CLAUDE.md.
# Override either with the MVN / JAVA_HOME environment variables.
#
# Usage:
#   ./tui.sh          # reuse the existing fat JAR (builds it if missing)
#   ./tui.sh -b       # force a fresh build first (use after code changes)
#
set -euo pipefail
cd "$(dirname "$0")"

JAR="build/target/launcher-0.0.0-jar-with-dependencies.jar"

# Platform-specific defaults for Maven and JAVA_HOME (overridable via env).
case "$(uname -s)" in
    Darwin)
        DEFAULT_MVN="$HOME/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
        DEFAULT_JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/azul-26.0.1/Contents/Home"
        ;;
    Linux)
        DEFAULT_MVN="$HOME/.local/share/JetBrains/Toolbox/apps/intellij-idea/plugins/maven/lib/maven3/bin/mvn"
        DEFAULT_JAVA_HOME="$HOME/.jdks/azul-26.0.1"
        ;;
    *)
        DEFAULT_MVN="mvn"
        DEFAULT_JAVA_HOME=""
        ;;
esac

MVN="${MVN:-$DEFAULT_MVN}"
# Fall back to a PATH-resolved mvn if the bundled IntelliJ Maven isn't present.
if [[ ! -x "$MVN" ]] && command -v mvn >/dev/null 2>&1; then
    MVN="$(command -v mvn)"
fi

export JAVA_HOME="${JAVA_HOME:-$DEFAULT_JAVA_HOME}"

if [[ "${1:-}" == "-b" || "${1:-}" == "--build" || ! -f "$JAR" ]]; then
    echo "Building fat JAR (skipping native packaging / DMG step)…"
    # -Dexec.skip=true skips the jpackage-* installer steps, which otherwise fail
    # on the 0.0.0 dev revision ("first number in an app-version cannot be zero").
    "$MVN" -B -Drevision=0.0.0 package -DskipTests -Dexec.skip=true
fi

# Use the configured JDK's java (the launcher needs JDK 26 flags); fall back to
# PATH java only if JAVA_HOME isn't set.
JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
fi

exec "$JAVA_BIN" \
    --enable-native-access=ALL-UNNAMED \
    --enable-final-field-mutation=ALL-UNNAMED \
    -jar "$JAR" --cli
