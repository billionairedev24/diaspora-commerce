#!/usr/bin/env bash
# S268: reproducible-build check. Builds one Java module twice from a clean target/ and compares the sha256 of the jars.
#
#   tools/ci/reproducible-build-check.sh [module]      module is a Maven -pl path, default libs/platform-web
#
# Jars are reproducible because java/platform-parent/pom.xml sets project.build.outputTimestamp (a fixed instant;
# CI may override it with the commit time: -Dproject.build.outputTimestamp=<commit time, ISO 8601>) and Spring Boot's
# repackage and the jar plugin then write sorted entries with that timestamp. Pass extra Maven arguments through MVN_ARGS
# (for example MVN_ARGS="-o" offline). Exit 0 when both builds are byte-identical, 1 with a diff of the entry lists otherwise.
# CI runs it as a non-blocking job (.github/workflows/reproducible-build.yml) until it has been green for a while.
set -euo pipefail
cd "$(dirname "$0")/../../java"

module="${1:-libs/platform-web}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

build() { # build <n>: clean package without tests, copy the jars aside
  # shellcheck disable=SC2086
  ./mvnw -B -q ${MVN_ARGS:-} -pl "$module" -am clean package -DskipTests -Dcheckstyle.skip -Dspotless.check.skip=true -Djacoco.skip=true
  mkdir -p "$work/$1"
  find "$module/target" -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp {} "$work/$1/" \;
}

build 1
build 2

status=0
for jar in "$work"/1/*.jar; do
  name="$(basename "$jar")"
  a="$(sha256sum "$jar" | cut -d' ' -f1)"
  b="$(sha256sum "$work/2/$name" | cut -d' ' -f1)"
  if [ "$a" = "$b" ]; then
    echo "reproducible: $name $a"
  else
    echo "NOT reproducible: $name $a != $b" >&2
    diff <(unzip -lv "$jar" | awk '{print $2,$7,$8}') <(unzip -lv "$work/2/$name" | awk '{print $2,$7,$8}') | head -20 >&2 || true
    status=1
  fi
done
[ -n "$(ls "$work"/1/*.jar 2>/dev/null)" ] || { echo "no jar built for $module" >&2; exit 1; }
exit $status
