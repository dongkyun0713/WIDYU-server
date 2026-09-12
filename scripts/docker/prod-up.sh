#!/usr/bin/env bash
# Run from a release directory; secrets stay outside release artifacts.
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${PROD_ENV_FILE:?Set PROD_ENV_FILE to the absolute server environment file}"
: "${DOCKER_IMAGE_NAME:?Set DOCKER_IMAGE_NAME}"
: "${IMAGE_TAG:?Set IMAGE_TAG to the release commit SHA}"
: "${PROD_DOMAIN:?Set PROD_DOMAIN}"
[[ "$IMAGE_TAG" =~ ^[a-f0-9]{40}$ ]] || { echo "A full commit SHA is required." >&2; exit 1; }
[[ "$PROD_DOMAIN" =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ ]] || exit 1
[[ "$PROD_ENV_FILE" = /* && -f "$PROD_ENV_FILE" ]] || exit 1
: "${PROD_DEPLOY_LOCK_FILE:=/home/ec2-user/widyu-prod-deploy.lock}"
[[ "$PROD_DEPLOY_LOCK_FILE" = /* ]] || exit 1
exec 9>"$PROD_DEPLOY_LOCK_FILE"
flock -n 9 || { echo "Another production deployment is running." >&2; exit 1; }
compose=(docker compose --project-name widyu-prod --env-file "$PROD_ENV_FILE" -f docker-compose.yml -f docker-compose.prod.yml)
"${compose[@]}" config --quiet
python3 scripts/docker/validate-prod.py "${compose[@]}"
test -s admin/dist/index.html
"${compose[@]}" pull widyu-api
# Check certificates and the rendered proxy configuration before replacing a working API.
"${compose[@]}" run --rm --no-deps --entrypoint sh certbot -c 'test -s "/etc/letsencrypt/live/$1/fullchain.pem" && test -s "/etc/letsencrypt/live/$1/privkey.pem"' sh "$PROD_DOMAIN"
"${compose[@]}" run --rm --no-deps nginx nginx -t
"${compose[@]}" up -d --wait --wait-timeout 180 --no-build --no-deps redis widyu-ai
"${compose[@]}" up -d --wait --wait-timeout 240 --no-build --no-deps widyu-api
for endpoint in http://127.0.0.1:8080/actuator/health http://127.0.0.1:8080/actuator/prometheus; do
  curl --fail --silent --show-error --output /dev/null --retry 12 --retry-all-errors --retry-delay 5 --connect-timeout 5 --max-time 10 "$endpoint"
done
"${compose[@]}" up -d --no-build --no-deps --force-recreate nginx
curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 --connect-timeout 5 --max-time 10 --resolve "$PROD_DOMAIN:443:127.0.0.1" "https://$PROD_DOMAIN/actuator/health"
echo "API and Nginx are healthy; starting monitoring services."
"${compose[@]}" up -d --wait --wait-timeout 180 --no-build node-exporter prometheus loki promtail grafana
for endpoint in http://127.0.0.1:9090/-/ready http://127.0.0.1:3100/ready http://127.0.0.1:3000/api/health; do
  curl --fail --silent --show-error --output /dev/null --retry 12 --retry-all-errors --retry-delay 5 --connect-timeout 5 --max-time 10 "$endpoint"
done
echo "Production deployment succeeded: $IMAGE_TAG"
