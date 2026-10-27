#!/usr/bin/env bash
# Run one part 2 node as a plain JVM. sbt allows one instance per project, so three `sbt runMain`s can't run side by side.
#
#   scripts/node.sh 0   # start node-0 first: it is the first seed node and forms the cluster
#   scripts/node.sh 1
#   scripts/node.sh 2
#
# The classpath is exported from sbt once and again whenever sources or the build change.
set -euo pipefail

n=${1:?usage: node.sh <0|1|2>}

cd "$(git rev-parse --show-toplevel)"

sbt=$(command -v sbt || command -v sbt.bat)
cp=part2-messaging/target/classpath.txt

if [[ ! -f $cp || -n $(find build.sbt common/src part2-messaging/src -newer "$cp" -print -quit) ]]; then
  mkdir -p part2-messaging/target
  "$sbt" -batch -error "export part2-messaging/Runtime/fullClasspath" | tail -n 1 | tr -d '\r' > "$cp"
fi

exec java -cp "$(cat "$cp")" dadbot.part2.startNode "$n"
