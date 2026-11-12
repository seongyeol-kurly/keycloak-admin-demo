#!/bin/bash
# EC2에서 실행: 어드민 포털 빌드 후 8083 포트로 실행
set -eu
IP=15.165.81.59
cd /opt/keycloak-admin-demo/portal
echo "== 이미지 빌드 =="
docker build -q -t keycloak-admin-portal .
source /opt/keycloak-test/admins.env
mkdir -p /opt/keycloak-portal-data

docker rm -f admin-portal >/dev/null 2>&1 || true
docker run -d --name admin-portal --network kc-net --restart unless-stopped -p 8083:8080 \
  -e CLIENT_ID=admin-portal -e CLIENT_SECRET="$PORTAL_SECRET" \
  -e KC_PUBLIC_URL="https://$IP" -e KC_INTERNAL_URL="http://keycloak:8080" -e KC_REALM=demo \
  -e PORTAL_DATA_DIR=/data -v /opt/keycloak-portal-data:/data \
  keycloak-admin-portal >/dev/null

for i in $(seq 1 40); do c=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/login || true); [ "$c" = "200" ] && break; sleep 3; done
echo ":8083 /login -> $c"
docker logs admin-portal 2>&1 | grep -E "seed|ERROR|Started" | tail -5
docker stats --no-stream --format '{{.Name}} MEM={{.MemUsage}}'
