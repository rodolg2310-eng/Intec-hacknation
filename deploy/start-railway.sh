#!/bin/sh
set -eu

mkdir -p "$APP_DATA_DIR"
if [ ! -f "$APP_DATA_DIR/traina.mv.db" ]; then
  cp -a /seed/data/. "$APP_DATA_DIR/"
fi

cd /app
if [ -n "${RAILWAY_PUBLIC_DOMAIN:-}" ]; then
  APP_ALLOWED_ORIGINS="https://${RAILWAY_PUBLIC_DOMAIN}"
  export APP_ALLOWED_ORIGINS
fi

java -jar /app/api.jar --spring.profiles.active=local --server.address=127.0.0.1 &
api_pid=$!

ready=false
for attempt in $(seq 1 90); do
  if curl --fail --silent http://127.0.0.1:8080/api/health >/dev/null; then
    ready=true
    break
  fi
  if ! kill -0 "$api_pid" 2>/dev/null; then
    wait "$api_pid"
    exit 1
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  echo "Backend did not become healthy in time." >&2
  kill "$api_pid" 2>/dev/null || true
  exit 1
fi

cd /app/web
export NITRO_HOST="0.0.0.0"
export NITRO_PORT="${PORT:-3000}"
echo "Starting Traina web server on port ${NITRO_PORT}"
node .output/server/index.mjs
web_status=$?
echo "Traina web server exited with status ${web_status}" >&2
exit "$web_status"
