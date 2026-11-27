#!/bin/sh
# Persistence cycles on the soak server (tools/soak.sh). Run from mod/.
#   sh tools/soak_persist.sh fixture        start, 4 bots, build the fixture, snapshot "s0", clean stop
#   sh tools/soak_persist.sh restart N      N clean restarts; snapshot "r<i>" right after each boot (no bots)
#   sh tools/soak_persist.sh kill N         N hard kills mid-save (bots join, play 60 s, save-all flush, kill after a
#                                           random delay); snapshot "k<i>" after the next boot
# Then: python tools/soak_diff.py run-soak/soak/snap-s0 run-soak/soak/snap-r1 --yaml
cd "$(dirname "$0")/.." || exit 1
S="sh tools/soak.sh"
waitlog() { # pattern [timeout s]
  # (sh functions share variables with the caller: no "i" here)
  L=$(cat run-soak/current-log); t=${2:-120}; w=0
  while [ $w -lt $t ]; do grep -q "$1" "$L" && return 0; w=$((w + 1)); sleep 1; done
  echo "timeout waiting for $1"; return 1
}
boot_snap() { # name
  $S start || exit 1
  sleep 8
  $S cmd "soak snap $1"
  waitlog "snapshot $1:" 60
  grep -E "Could not read|unreadable|broken|CLOSED|PROBLEM|ERROR" "$(cat run-soak/current-log)" | grep -v "^\s*at " | head -20
}
case "$1" in
  fixture)
    $S start || exit 1
    $S cmd "soak bots 4"; waitlog "Soak4.*logged in" 60; sleep 5
    $S cmd "soak fixture"; waitlog "fixture: .* bots set up" 60; sleep 4
    $S cmd "soak names"; sleep 4
    $S cmd "save-all flush"; waitlog "Saved the game" 120; sleep 3
    $S cmd "soak snap s0"; waitlog "snapshot s0:" 60
    $S stop
    ;;
  restart)
    n=${2:-3}; i=1
    while [ $i -le "$n" ]; do boot_snap "r$i"; $S stop; i=$((i + 1)); done
    ;;
  kill)
    n=${2:-3}; i=1
    while [ $i -le "$n" ]; do
      $S start || exit 1
      $S cmd "soak bots 4"; waitlog "Soak4.*logged in" 60
      $S cmd "soak run 1"; waitlog "chaos loop done" 120
      $S cmd "soak quitall"; sleep 2
      $S cmd "save-all flush"
      d=$(( (RANDOM % 1500) + 50 ))
      perl -e "select(undef,undef,undef,$d/1000)"
      echo "kill $i after ${d} ms"
      $S kill
      sleep 3
      boot_snap "k$i"; $S stop
      i=$((i + 1))
    done
    ;;
esac
