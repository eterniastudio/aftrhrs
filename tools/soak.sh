#!/bin/sh
# Soak dev server (gametest/soak/SoakDriver): start it with a console fed from run-soak/console.in, so commands can be
# appended while it runs:  sh tools/soak.sh start;  sh tools/soak.sh cmd "soak stats";  sh tools/soak.sh stop|kill
# Soak JVM flags go through JAVA_TOOL_OPTIONS, e.g. JAVA_TOOL_OPTIONS="-Daftrhrs.soak.tickLog=50" sh tools/soak.sh start:
#   -Daftrhrs.soak.tickLog=<ms>  ticks at/over <ms> and every save tick go to run-soak/soak/ticks.csv (off by default)
#   -Daftrhrs.soak.gc=true       a forced System.gc() before each 5-minute stats pass (off: it stalls that tick)
#   -XX:StartFlightRecording=filename=soak.jfr,settings=<file>.jfc  JFR; aftrhrs.soak.Tick/LevelTick/Save/Gap events
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME=C:/Users/kevin/.jdks/temurin-25.0.3
D=${SOAK_DIR:-run-soak}
# the "tail -f console.in" feeding this server's console (Git Bash has no pkill)
untail() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.CommandLine -match 'tail.*$(echo $D | sed 's#/#.#g')/console.in' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" 2>/dev/null
}
case "$1" in
  start)
    rm -f $D/world/session.lock
    : > $D/console.in
    LOG=$D/soak-$(date +%H%M%S).log
    ln -sf "$(basename "$LOG")" $D/latest.log 2>/dev/null || cp /dev/null $D/latest.log
    echo "$LOG" > $D/current-log
    (tail -f $D/console.in | ./gradlew runSoak -PsoakDir=$D --console=plain) > "$LOG" 2>&1 &
    i=0
    while [ $i -lt 600 ]; do
      if grep -q "soak driver ready" "$LOG" 2>/dev/null; then break; fi
      if grep -qE "BUILD FAILED|Exception in server tick loop|Failed to start" "$LOG" 2>/dev/null; then echo "start failed"; tail -30 "$LOG"; exit 1; fi
      i=$((i + 1)); sleep 1
    done
    grep -o "soak driver ready.*" "$LOG"
    ;;
  cmd)
    shift
    printf '%s\n' "$*" >> $D/console.in
    ;;
  stop)
    printf 'stop\n' >> $D/console.in
    LOG=$(cat $D/current-log)
    i=0
    while [ $i -lt 300 ]; do
      if grep -qE "BUILD SUCCESSFUL|BUILD FAILED" "$LOG"; then break; fi
      i=$((i + 1)); sleep 1
    done
    untail
    tail -3 "$LOG"
    ;;
  kill)
    PID=$(grep -o "soak driver ready (pid [0-9]*" "$(cat $D/current-log)" | grep -o "[0-9]*$" | tail -1)
    echo "taskkill $PID"
    taskkill //F //PID "$PID"
    untail
    ;;
esac
