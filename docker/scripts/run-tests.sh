#!/usr/bin/env bash
# Runs unit, integration and end-to-end tests inside the toolbox container.
# Assumes it runs with network_mode: service:openfire (see docker-compose.yml),
# so "localhost" resolves to the Openfire server.
set -euo pipefail

PROJ=/app
BUILD=$PROJ/build-docker

APP_CP=$(ls $PROJ/lib/deploy/*.jar | tr '\n' ':')
DEV_CP=$(ls $PROJ/lib/develop/*.jar | grep -v -- '-src.jar' | tr '\n' ':')

compile() {
  local src=$1 out=$2 cp=$3
  mkdir -p "$out"
  javac -d "$out" -cp "$cp" -sourcepath "$src" $(find "$src" -name '*.java')
}

echo "== compiling app =="
compile "$PROJ/src" "$BUILD/app" "$APP_CP"

echo "== compiling unit tests =="
compile "$PROJ/test/unit" "$BUILD/unit-test" "$BUILD/app:$APP_CP$DEV_CP"

# integration tests reuse helper classes (FakeAuctionServer, ApplicationRunner,
# AuctionSniperDriver, ...) that live under test/end-to-end, so that must be
# compiled first and included on the integration classpath.
echo "== compiling end-to-end tests =="
compile "$PROJ/test/end-to-end" "$BUILD/e2e-test" "$BUILD/app:$APP_CP$DEV_CP"

echo "== compiling integration tests =="
compile "$PROJ/test/integration" "$BUILD/integration-test" "$BUILD/app:$BUILD/e2e-test:$APP_CP$DEV_CP"

run_junit() {
  local classes_dir=$1 extra_cp=$2; shift 2
  local full_cp="$classes_dir:$extra_cp:$BUILD/app:$APP_CP$DEV_CP"
  local classes=$(cd "$classes_dir" && find . -name '*.class' ! -name '*\$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | grep -E "$1")
  xvfb-run -a java -cp "$full_cp" org.junit.runner.JUnitCore $classes
}

echo "== running unit tests =="
run_junit "$BUILD/unit-test" "" 'Tests?$'

echo "== waiting for Openfire (localhost:9090) =="
for i in $(seq 1 30); do
  curl -sf http://localhost:9090 > /dev/null && break
  sleep 1
done

echo "== running integration tests =="
run_junit "$BUILD/integration-test" "$BUILD/e2e-test" 'Test$'

echo "== running end-to-end tests =="
run_junit "$BUILD/e2e-test" "" 'Test$'

echo "== all suites finished =="
