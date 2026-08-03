#!/usr/bin/env bash
# Brings up the Openfire + toolbox containers, runs the Playwright
# setup script against Openfire (wizard + test accounts), and leaves
# everything ready for docker/test.sh to run the test suites.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "== starting openfire =="
sudo docker compose up -d openfire

echo "== waiting for http://localhost:9090 =="
for i in $(seq 1 30); do
  curl -sf http://localhost:9090 > /dev/null && break
  sleep 1
done

echo "== building/starting toolbox =="
sudo docker compose build toolbox
sudo docker compose up -d toolbox

if [ ! -d node_modules ]; then
  echo "== installing playwright (first run) =="
  npm install playwright
  npx playwright install chromium
fi

echo "== running Openfire setup wizard + test accounts =="
node setup-openfire.js

echo "== environment ready. run scripts/test.sh to execute the test suites =="
