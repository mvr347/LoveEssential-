#!/usr/bin/env bash
set -euo pipefail
: "${JAVA_HOME:?Set JAVA_HOME to JDK 25}"
repo_dir=$(git rev-parse --show-toplevel)
benchmark_dir=$(mktemp -d)
trap 'rm -rf "$benchmark_dir"' EXIT
output_dir=${1:-/tmp/loveessential-snapshot-results}
mkdir -p "$output_dir" "$benchmark_dir/src/dev/lovelace/loveessential/npc"
git -C "$repo_dir" show 163f5279f10582418727c755f005edbea4278f34:plugin/src/main/java/dev/lovelace/loveessential/npc/PathSearch.java | sed 's/PathSearch/OldPathSearch/g' > "$benchmark_dir/src/dev/lovelace/loveessential/npc/OldPathSearch.java"
cp "$repo_dir/plugin/src/main/java/dev/lovelace/loveessential/npc/PathSearch.java" "$benchmark_dir/src/dev/lovelace/loveessential/npc/PathSearch.java"
"$JAVA_HOME/bin/javac" -d "$benchmark_dir/classes" "$benchmark_dir"/src/dev/lovelace/loveessential/npc/*.java "$repo_dir/tools/benchmarks/SnapshotBenchmark.java"
for variant in old new; do
  "$JAVA_HOME/bin/java" -Xms256m -Xmx512m "-XX:StartFlightRecording=filename=$output_dir/snapshots-$variant.jfr,settings=profile,dumponexit=true" -cp "$benchmark_dir/classes" SnapshotBenchmark "$variant" > "$output_dir/snapshots-$variant.log"
  tail -n 1 "$output_dir/snapshots-$variant.log"
done
