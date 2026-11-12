#!/bin/bash
# EC2에서 실행: Keycloak hostname 고정 + 예시 어드민용 client/role/group 생성
set -eu
IP=15.165.81.59
KC=http://localhost:8080
REALM=demo

echo "== 0. swap 2GB (빌드·어드민 2개 메모리 여유) =="
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo '/swapfile none swap defaults 0 0' >> /etc/fstab
fi
free -m | head -3

echo "== 1. Keycloak 재기동: hostname 고정 + 백채널 동적 + docker network =="
# - KC_HOSTNAME: 토큰 issuer를 https://IP 로 고정 (브라우저/서버 어디서 호출해도 동일)
# - KC_HOSTNAME_BACKCHANNEL_DYNAMIC: 어드민 서버는 docker 내부망(http://keycloak:8080)으로 토큰/JWKS 호출 가능
docker network inspect kc-net >/dev/null 2>&1 || docker network create kc-net >/dev/null
docker rm -f keycloak >/dev/null 2>&1 || true
docker run -d --name keycloak --network kc-net --restart unless-stopped \
  --env-file /opt/keycloak-test/.env \
  -e KC_HEALTH_ENABLED=true -e KC_METRICS_ENABLED=true \
  -e JAVA_OPTS_KC_HEAP='-Xms256m -Xmx640m' \
  -e KC_HOSTNAME=https://$IP -e KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true \
  -e KC_HTTPS_CERTIFICATE_FILE=/opt/keycloak/conf/certs/tls.crt \
  -e KC_HTTPS_CERTIFICATE_KEY_FILE=/opt/keycloak/conf/certs/tls.key \
  -p 443:8443 -p 127.0.0.1:8080:8080 -p 127.0.0.1:9000:9000 \
  -v /opt/keycloak-test/data:/opt/keycloak/data \
  -v /opt/keycloak-test/certs:/opt/keycloak/conf/certs:ro \
  quay.io/keycloak/keycloak:26.4 start-dev >/dev/null
for i in $(seq 1 80); do
  s=$(curl -sk https://localhost:9000/health/ready | jq -r .status 2>/dev/null || true)
  [ "$s" = "UP" ] && break; sleep 3
done
echo "health: $s"

source /opt/keycloak-test/.env
AT=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=$KC_BOOTSTRAP_ADMIN_USERNAME \
  -d password=$KC_BOOTSTRAP_ADMIN_PASSWORD $KC/realms/master/protocol/openid-connect/token | jq -r .access_token)
kc() { curl -s -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$@"; }
code() { curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$@"; }

echo "== 2. client 생성 (order-admin:8081, settle-admin:8082) =="
: > /opt/keycloak-test/admins.env
chmod 600 /opt/keycloak-test/admins.env
for spec in "order-admin 8081 주문 ORDER" "settle-admin 8082 정산 SETTLE"; do
  set -- $spec; CID=$1; PORT=$2; LABEL=$3; VAR=$4
  OLD=$(kc "$KC/admin/realms/$REALM/clients?clientId=$CID" | jq -r '.[0].id // empty')
  [ -n "$OLD" ] && kc -X DELETE "$KC/admin/realms/$REALM/clients/$OLD"
  SECRET=$(openssl rand -hex 16)
  echo -n "$CID: "
  code -X POST "$KC/admin/realms/$REALM/clients" -d "{
    \"clientId\":\"$CID\",\"name\":\"$LABEL 어드민\",\"publicClient\":false,\"secret\":\"$SECRET\",
    \"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":false,\"frontchannelLogout\":false,
    \"rootUrl\":\"http://$IP:$PORT\",\"redirectUris\":[\"http://$IP:$PORT/*\"],\"webOrigins\":[\"http://$IP:$PORT\"]}"
  echo
  echo "${VAR}_SECRET=$SECRET" >> /opt/keycloak-test/admins.env
done

cid() { kc "$KC/admin/realms/$REALM/clients?clientId=$1" | jq -r '.[0].id'; }
ORDER_CID=$(cid order-admin); SETTLE_CID=$(cid settle-admin)

echo "== 3. client role 생성 =="
for r in order.read order.refund; do echo -n "order-admin/$r: "; code -X POST "$KC/admin/realms/$REALM/clients/$ORDER_CID/roles" -d "{\"name\":\"$r\"}"; echo; done
for r in settle.read settle.approve; do echo -n "settle-admin/$r: "; code -X POST "$KC/admin/realms/$REALM/clients/$SETTLE_CID/roles" -d "{\"name\":\"$r\"}"; echo; done
role() { kc "$KC/admin/realms/$REALM/clients/$1/roles/$2"; }

echo "== 4. 그룹 '정산팀' 생성 → settle.read 부여 (그룹 상속 예시) =="
OLDG=$(kc "$KC/admin/realms/$REALM/groups" | jq -r '.[] | select(.name=="정산팀") | .id')
[ -n "$OLDG" ] && kc -X DELETE "$KC/admin/realms/$REALM/groups/$OLDG"
code -X POST "$KC/admin/realms/$REALM/groups" -d '{"name":"정산팀"}'; echo
GID=$(kc "$KC/admin/realms/$REALM/groups" | jq -r '.[] | select(.name=="정산팀") | .id')
echo -n "group role: "; code -X POST "$KC/admin/realms/$REALM/groups/$GID/role-mappings/clients/$SETTLE_CID" -d "[$(role $SETTLE_CID settle.read)]"; echo

echo "== 5. testuser 권한: order.read/order.refund 직접 부여 + 정산팀 가입 =="
UID_=$(kc "$KC/admin/realms/$REALM/users?username=testuser&exact=true" | jq -r '.[0].id')
echo -n "direct roles: "; code -X POST "$KC/admin/realms/$REALM/users/$UID_/role-mappings/clients/$ORDER_CID" -d "[$(role $ORDER_CID order.read),$(role $ORDER_CID order.refund)]"; echo
echo -n "join group: "; code -X PUT "$KC/admin/realms/$REALM/users/$UID_/groups/$GID"; echo

echo "== 확인: issuer =="
curl -s $KC/realms/$REALM/.well-known/openid-configuration | jq '{issuer, token_endpoint}'
