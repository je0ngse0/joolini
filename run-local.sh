#!/bin/sh
set -eu

if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

exec env GRADLE_USER_HOME="${GRADLE_USER_HOME:-/private/tmp/joolini-gradle}" ./gradlew bootRun --console=plain
