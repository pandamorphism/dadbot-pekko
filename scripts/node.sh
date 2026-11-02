#!/usr/bin/env bash
# Run one node of part 2 or 3 as a plain JVM. sbt allows one instance per project, so three `sbt runMain`s can't run
# side by side.
#
#   scripts/node.sh 2 0   # part 2, node-0. Start node-0 first: it is the first seed node and forms the cluster
#   scripts/node.sh 2 1
#   scripts/node.sh 3 0   # part 3 also needs Postgres: docker compose up -d
#
# The classpath is exported from sbt once and again whenever sources or the build change.
set -euo pipefail

usage="usage: node.sh <part: 2|3> <node: 0|1|2>"
part=${1:?$usage}
n=${2:?$usage}
case $part in
  2) module=part2-messaging ;;
  3) module=part3-delivery ;;
  *) echo "$usage" >&2; exit 1 ;;
esac

cd "$(git rev-parse --show-toplevel)"

sbt=$(command -v sbt || command -v sbt.bat)
cp=$module/target/classpath.txt

if [[ ! -f $cp || -n $(find build.sbt common/src $module/src -newer "$cp" -print -quit) ]]; then
  mkdir -p $module/target
  "$sbt" -batch -error "export $module/Runtime/fullClasspath" | tail -n 1 | tr -d '\r' > "$cp"
fi

exec java -cp "$(cat "$cp")" "dadbot.part$part.startNode" "$n"
