#!/bin/bash

# SSL 인증서 최초 발급 스크립트
# 사용법: ./scripts/ssl/certbot-init.sh yourdomain.com your-email@example.com

set -e

DOMAIN=${1}
EMAIL=${2}

if [ -z "$DOMAIN" ] || [ -z "$EMAIL" ]; then
    echo "사용법: $0 <domain> <email>"
    echo "예시: $0 api.widyu.com admin@widyu.com"
    exit 1
fi

echo "🔐 SSL 인증서 발급 시작..."
echo "도메인: $DOMAIN"
echo "이메일: $EMAIL"
echo ""

# Certbot을 사용하여 인증서 발급
docker compose run --rm certbot certonly \
    --webroot \
    --webroot-path=/var/www/certbot \
    --email "$EMAIL" \
    --agree-tos \
    --no-eff-email \
    --force-renewal \
    -d "$DOMAIN"

echo ""
echo "✅ 인증서 발급 완료!"
echo ""
echo "📝 다음 단계:"

if [ -f nginx/nginx.https.conf ]; then
    cp nginx/nginx.https.conf nginx/nginx.conf
    docker compose restart nginx
    echo "1. HTTPS Nginx 설정으로 전환했습니다."
else
    echo "1. nginx/nginx.conf에서 도메인이 '$DOMAIN'인지 확인하세요."
fi

echo "2. HTTPS 테스트: https://$DOMAIN/actuator/health"
