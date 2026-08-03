#!/usr/bin/env bash
# Tears down the containers + wipes the Openfire volume (fresh setup
# wizard next time), then rebuilds everything via start-env.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

echo "== tearing down containers =="
sudo docker compose down

echo "== wiping openfire data volume =="
sudo docker volume rm docker_openfire-data

./scripts/start-env.sh
