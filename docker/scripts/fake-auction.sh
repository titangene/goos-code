#!/usr/bin/env bash
# Interactive fake auction house -- lets you drive the running app by hand
# (send PRICE/CLOSE events to whichever sniper joined this item).
# See docker/tools/FakeAuction.java for the commands available once it's
# waiting for input.
#
# Usage:
#   ./fake-auction.sh <itemId>
#   e.g. ./fake-auction.sh item-54321
set -euo pipefail

cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
  echo "usage: $0 <itemId>" >&2
  exit 1
fi

sudo docker compose exec toolbox bash -c '
  set -euo pipefail
  PROJ=/app
  TOOLS=$PROJ/docker/tools
  APP_CP=$(ls $PROJ/lib/deploy/*.jar | tr "\n" ":")

  javac -cp "$APP_CP" -d "$TOOLS" "$TOOLS/FakeAuction.java"
  java -cp "$TOOLS:$APP_CP" FakeAuction '"$@"'
'
