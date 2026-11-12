#!/bin/bash
# EC2에서 실행: 포털 주요 기능 검증 (testuser = order-admin 소유자, settle-admin은 소유자 아님)
set -u
IP=15.165.81.59
P=http://$IP:8083
J=/tmp/portal.jar; rm -f $J
C="curl -sk -b $J -c $J --resolve $IP:443:127.0.0.1 --resolve $IP:8083:127.0.0.1"
step() { echo; echo "== $* =="; }
csrf() { $C "$P$1" | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"$//'; }
flash() { grep -oE 'class="flash (ok|err)">[^<]*' "$1" | sed 's/class="flash //;s/">/: /' | head -2; }
post() { # path redirectOut data...
  local path=$1; local out=$2; shift 2
  local t=$(csrf /)
  $C -L -o "$out" -d "_csrf=$t" "$@" "$P$path"
}

step "1. 로그인 (testuser)"
PAGE=$($C -L $P/oauth2/authorization/keycloak)
ACTION=$(echo "$PAGE" | grep -o 'action="[^"]*"' | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
$C -L -o /tmp/p_home.html -w "  %{url_effective} (%{http_code})\n" -d username=testuser -d 'password=Test1234!' -d credentialId= "$ACTION"
grep -o '안녕하세요, <span>[^<]*' /tmp/p_home.html | sed 's/<span>//'

step "2. 어드민 목록 (seed: order-admin=testuser 소유, settle-admin=소유자 없음)"
$C -o /tmp/p_clients.html $P/clients
grep -oE '<code>[a-z-]+</code>' /tmp/p_clients.html | sed 's/<[^>]*>//g' | tr '\n' ' '; echo
echo "  관리 가능: $(grep -c '관리 가능' /tmp/p_clients.html) / 조회만: $(grep -c '조회만' /tmp/p_clients.html)"

step "3. 새 어드민 등록 (sample-admin)"
T=$(csrf /clients)
$C -o /tmp/p_reg.html -d "_csrf=$T" -d clientId=sample-admin -d name="샘플 어드민" -d baseUrl=http://$IP:8084 $P/clients
grep -q 'client secret' /tmp/p_reg.html && echo "  등록 완료, secret 1회 노출: $(grep -oE '<code[^>]*>[0-9a-zA-Z]{20,}</code>' /tmp/p_reg.html | sed 's/<[^>]*>//g' | cut -c1-6)..." || flash /tmp/p_reg.html

step "4. 소유 어드민(sample-admin)에 권한 생성 + testuser에게 부여"
post /clients/sample-admin/roles /tmp/p_r1.html -d role=sample.read -d description="조회"; flash /tmp/p_r1.html
post /clients/sample-admin/roles/sample.read/users /tmp/p_r2.html -d username=testuser; flash /tmp/p_r2.html

step "5. 소유하지 않은 어드민(settle-admin)에 권한 생성 시도 → 차단"
post /clients/settle-admin/roles /tmp/p_r3.html -d role=settle.hack; flash /tmp/p_r3.html

step "6. 권한 신청 → 셀프 승인 (settle-admin / settle.approve)"
post /requests /tmp/p_q1.html -d "target=settle-admin|settle.approve" -d reason="정산 승인 업무"; flash /tmp/p_q1.html
RID=$(grep -oE '/requests/[0-9]+/approve' /tmp/p_q1.html | head -1 | grep -oE '[0-9]+')
echo "  신청서 #$RID"
post /requests/$RID/approve /tmp/p_q2.html -d comment="PoC 셀프 승인"; flash /tmp/p_q2.html

step "7. Keycloak 실제 반영 확인 (Admin API 직접 조회)"
source /opt/keycloak-test/.env
AT=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=$KC_BOOTSTRAP_ADMIN_USERNAME -d password=$KC_BOOTSTRAP_ADMIN_PASSWORD http://localhost:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)
K=http://localhost:8080/admin/realms/demo
UID_=$(curl -s -H "Authorization: Bearer $AT" "$K/users?username=testuser&exact=true" | jq -r '.[0].id')
for c in settle-admin sample-admin; do
  CID=$(curl -s -H "Authorization: Bearer $AT" "$K/clients?clientId=$c" | jq -r '.[0].id')
  echo "  $c: $(curl -s -H "Authorization: Bearer $AT" "$K/users/$UID_/role-mappings/clients/$CID/composite" | jq -r '[.[].name]|sort|join(", ")')"
done

step "8. 화면 확인 (임직원 상세 / 그룹 / 감사 로그)"
$C -o /dev/null -w "  /users/{testuser} -> %{http_code}\n" $P/users/$UID_
GID=$(curl -s -H "Authorization: Bearer $AT" "$K/groups" | jq -r '.[0].id')
$C -o /dev/null -w "  /groups/{id} -> %{http_code}\n" $P/groups/$GID
$C -o /tmp/p_audit.html -w "  /audit -> %{http_code}\n" $P/audit
grep -oE '<code>[A-Z_]+</code>' /tmp/p_audit.html | sed 's/<[^>]*>//g' | sort | uniq -c

step "9. 원복: 테스트로 부여한 settle.approve 회수 (다른 사람 테스트에 영향 없게)"
SCID=$(curl -s -H "Authorization: Bearer $AT" "$K/clients?clientId=settle-admin" | jq -r '.[0].id')
ROLE=$(curl -s -H "Authorization: Bearer $AT" "$K/clients/$SCID/roles/settle.approve")
curl -s -o /dev/null -w "  회수: %{http_code}\n" -X DELETE -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$K/users/$UID_/role-mappings/clients/$SCID" -d "[$ROLE]"

docker logs admin-portal 2>&1 | grep -E "ERROR|Exception" | tail -5
