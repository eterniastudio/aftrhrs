#!/bin/sh
# Migration at scale: each prepared Paper copy under run-mig/<name> (world + plugins/Feather) boots on a fresh Fabric
# server, loads every chunk on disk (so every entity / block entity / item meets its converter), stops, then boots twice
# more (idempotence). Logs: run-mig/<name>/soak-*.log. Run from mod/:  sh tools/soak_migrate.sh companion263 server ...
cd "$(dirname "$0")/.." || exit 1
for w in "$@"; do
  export SOAK_DIR=run-mig/$w
  for boot in 1 2 3; do
    sh tools/soak.sh start || { echo "$w boot $boot: start failed"; break; }
    L=$(cat $SOAK_DIR/current-log)
    sleep 5
    sh tools/soak.sh cmd "soak loadall"
    # wait for the queue ("loadall queued N"), then until every queued dimension finished
    n=0
    while [ $n -lt 120 ] && ! grep -q "loadall queued" "$L"; do n=$((n + 1)); sleep 1; done
    q=$(grep -o "loadall queued [0-9]*" "$L" | grep -o "[0-9]*$")
    n=0
    while [ $n -lt 900 ]; do
      d=$(grep -cE "gen minecraft:[a-z_]+ ([0-9]+)/\1 " "$L")
      [ "$d" -ge "${q:-0}" ] && break
      n=$((n + 1)); sleep 1
    done
    sleep 8
    sh tools/soak.sh cmd "save-all flush"
    sleep 5
    sh tools/soak.sh stop > /dev/null
    echo "== $w boot $boot: $(grep -c '/ERROR\]' "$L") errors, $(grep -c '/WARN\]' "$L") warnings, $(grep -c 'Exception' "$L") exception lines"
    grep -E -A60 "Migration \(server stopping\)" "$L" | grep -E "Migration|^  " | cut -c1-220
    grep -E "Importing Feather|Imported [0-9]+ Feather|Adopted|loadall .*disk|/ERROR\]" "$L" | cut -c1-220 | head -20
  done
done
