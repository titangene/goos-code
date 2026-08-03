#!/usr/bin/env bash
# Runs unit, integration and end-to-end tests inside the toolbox
# container. Assumes scripts/start-env.sh has already been run.
set -euo pipefail

cd "$(dirname "$0")/.."

sudo docker compose exec toolbox bash /app/docker/scripts/run-tests.sh
