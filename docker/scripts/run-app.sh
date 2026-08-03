#!/usr/bin/env bash
# Runs the actual Auction Sniper Swing app (auctionsniper.Main) inside the
# existing toolbox container, showing its window on the host's X
# display. Reuses the same container/network as the tests -- no separate
# docker environment needed.
#
# Usage:
#   ./run-app.sh                       # connects as sniper/sniper
#   ./run-app.sh <username> <password> # connects as a different account
#
# Requires scripts/start-env.sh to have been run already (openfire +
# toolbox up, accounts created).
set -euo pipefail

cd "$(dirname "$0")/.."

USERNAME="${1:-sniper}"
PASSWORD="${2:-sniper}"

xhost +local:docker > /dev/null 2>&1 || true

sudo docker compose exec \
  -e DISPLAY="$DISPLAY" \
  -e SNIPER_USERNAME="$USERNAME" \
  -e SNIPER_PASSWORD="$PASSWORD" \
  toolbox bash -c '
    set -euo pipefail
    PROJ=/app
    BUILD=$PROJ/build-docker
    APP_CP=$(ls $PROJ/lib/deploy/*.jar | tr "\n" ":")

    if [ ! -d "$BUILD/app" ]; then
      echo "== compiling app =="
      mkdir -p "$BUILD/app"
      javac -d "$BUILD/app" -cp "$APP_CP" -sourcepath "$PROJ/src" $(find "$PROJ/src" -name "*.java")
    fi

    echo "== launching auctionsniper.Main localhost $SNIPER_USERNAME =="
    java -cp "$BUILD/app:$APP_CP" auctionsniper.Main localhost "$SNIPER_USERNAME" "$SNIPER_PASSWORD"
  '
