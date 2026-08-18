#!/usr/bin/env bash
# Stops and removes the running containers (openfire + toolbox) and wipes
# the Openfire data volume. Use scripts/start-env.sh (or scripts/reset-env.sh,
# which does both) to bring the environment back up afterwards.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "== stopping containers =="
sudo docker compose down

echo "== wiping openfire data volume =="
sudo docker volume rm docker_openfire-data
