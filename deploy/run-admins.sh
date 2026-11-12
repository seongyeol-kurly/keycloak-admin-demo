#!/bin/bash
# EC2에서 실행: 예시 어드민 이미지 빌드 후 2개(주문 8081, 정산 8082) 실행
set -eu
IP=15.165.81.59
cd /opt/keycloak-admin-demo
echo "== 이미지 빌드 =="
docker build -q -t keycloak-admin-demo .
source /opt/keycloak-test/admins.env

run() { # name port label color cookie client secret otherName otherPort requiredRoles
  docker rm -f "$1" >/dev/null 2>&1 || true
  docker run -d --name "$1" --network kc-net --restart unless-stopped -p "$2:8080" \
    -e REQUIRED_ROLES="${10}" \
    -e APP_NAME="$3" -e APP_COLOR="$4" -e APP_COOKIE="$5" \
    -e CLIENT_ID="$6" -e CLIENT_SECRET="$7" \
    -e OTHER_NAME="$8" -e OTHER_URL="http://$IP:$9/" \
    -e KC_PUBLIC_URL="https://$IP" -e KC_INTERNAL_URL="http://keycloak:8080" -e KC_REALM=demo \
    keycloak-admin-demo >/dev/null
  echo "$1 started on :$2"
}
run order-admin  8081 "주문 어드민" "#7c3aed" ORDER_ADMIN_SESSION  order-admin  "$ORDER_SECRET"  "정산 어드민" 8082 order.read
run settle-admin 8082 "정산 어드민" "#0969da" SETTLE_ADMIN_SESSION settle-admin "$SETTLE_SECRET" "주문 어드민" 8081 settle.read

for p in 8081 8082; do
  for i in $(seq 1 40); do c=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$p/login || true); [ "$c" = "200" ] && break; sleep 3; done
  echo ":$p /login -> $c"
done
docker stats --no-stream --format '{{.Name}} MEM={{.MemUsage}}'
