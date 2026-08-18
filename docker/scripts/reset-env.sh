#!/usr/bin/env bash
# Tears down the containers + wipes the Openfire volume (fresh setup
# wizard next time), then rebuilds everything via start-env.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

./scripts/stop-env.sh
./scripts/start-env.sh
