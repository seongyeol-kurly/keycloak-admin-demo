#!/bin/bash
# EC2에서 실행: 어드민 포털용 Keycloak client(admin-portal) + 서비스 계정 권한 설정
set -eu
IP=15.165.81.59
KC=http://localhost:8080
K=$KC/admin/realms/demo

source /opt/keycloak-test/.env
AT=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=$KC_BOOTSTRAP_ADMIN_USERNAME \
  -d password=$KC_BOOTSTRAP_ADMIN_PASSWORD $KC/realms/master/protocol/openid-connect/token | jq -r .access_token)
kc() { curl -s -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$@"; }
code() { curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$@"; }
cid() { kc "$K/clients?clientId=$1" | jq -r '.[0].id // empty'; }

echo "== 1. client admin-portal 생성 (사용자 로그인 + 서비스 계정) =="
OLD=$(cid admin-portal); [ -n "$OLD" ] && kc -X DELETE "$K/clients/$OLD"
SECRET=$(openssl rand -hex 16)
code -X POST "$K/clients" -d "{
  \"clientId\":\"admin-portal\",\"name\":\"어드민 포털\",\"publicClient\":false,\"secret\":\"$SECRET\",
  \"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":false,\"serviceAccountsEnabled\":true,\"frontchannelLogout\":false,
  \"rootUrl\":\"http://$IP:8083\",\"redirectUris\":[\"http://$IP:8083/*\"],\"webOrigins\":[\"http://$IP:8083\"]}"; echo
PID=$(cid admin-portal)

echo "== 2. 포털 관리자 role (portal-admin) 생성 — 우리 팀에게만 부여 =="
code -X POST "$K/clients/$PID/roles" -d '{"name":"portal-admin","description":"모든 어드민 권한 관리 가능"}'; echo

echo "== 3. 서비스 계정에 최소 권한(realm-management) 부여 =="
SA=$(kc "$K/clients/$PID/service-account-user" | jq -r .id)
RM=$(cid realm-management)
ROLES=""
for r in manage-clients view-clients query-clients manage-users view-users query-users query-groups; do
  ROLES="$ROLES$(kc "$K/clients/$RM/roles/$r"),"
done
code -X POST "$K/users/$SA/role-mappings/clients/$RM" -d "[${ROLES%,}]"; echo
kc "$K/users/$SA/role-mappings/clients/$RM" | jq -r '[.[].name] | join(", ")'

touch /opt/keycloak-test/admins.env && chmod 600 /opt/keycloak-test/admins.env
sed -i '/^PORTAL_SECRET=/d' /opt/keycloak-test/admins.env
echo "PORTAL_SECRET=$SECRET" >> /opt/keycloak-test/admins.env
echo "done"
