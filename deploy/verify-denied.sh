#!/bin/bash
# testuser의 order.read를 잠시 회수 → 주문 어드민 차단 화면 확인 → 원복
set -u
IP=15.165.81.59
source /opt/keycloak-test/.env
AT=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=$KC_BOOTSTRAP_ADMIN_USERNAME -d password=$KC_BOOTSTRAP_ADMIN_PASSWORD http://localhost:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)
kc() { curl -s -H "Authorization: Bearer $AT" -H "Content-Type: application/json" "$@"; }
K=http://localhost:8080/admin/realms/demo
UID_=$(kc "$K/users?username=testuser&exact=true" | jq -r '.[0].id')
CID=$(kc "$K/clients?clientId=order-admin" | jq -r '.[0].id')
ROLE=$(kc "$K/clients/$CID/roles/order.read")
echo "== order.read 회수 =="; kc -X DELETE "$K/users/$UID_/role-mappings/clients/$CID" -d "[$ROLE]" -o /dev/null -w "%{http_code}\n"

J=/tmp/deny.jar; rm -f $J
C="curl -sk -b $J -c $J --resolve $IP:443:127.0.0.1 --resolve $IP:8081:127.0.0.1 --resolve $IP:8082:127.0.0.1"
PAGE=$($C -L http://$IP:8081/oauth2/authorization/keycloak)
ACTION=$(echo "$PAGE" | grep -o 'action="[^"]*"' | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
$C -L -o /tmp/deny.html -w "로그인 후: %{url_effective} (%{http_code})\n" -d username=testuser -d 'password=Test1234!' -d credentialId= "$ACTION"
echo "제목: $(grep -o '<h1>.*</h1>' /tmp/deny.html | sed 's/<[^>]*>//g')"
echo "필요한 권한: $(grep -oE 'class="chip missing">[^<]+' /tmp/deny.html | sed 's/.*>//' | tr '\n' ' ')"
echo "보유 권한: $(grep -oE 'class="chip">[^<]+' /tmp/deny.html | sed 's/.*>//' | tr '\n' ' ')"
grep -q '아이디' /tmp/deny.html && echo "이름/아이디/이메일 영역 표시됨"

echo "== 정산 어드민(권한 있음) 원클릭 =="
$C -L -o /tmp/ok.html -w "%{url_effective} (%{http_code})\n" http://$IP:8082/oauth2/authorization/keycloak
echo "제목: $(grep -o '<h1>.*</h1>' /tmp/ok.html | sed 's/<[^>]*>//g')"

echo "== order.read 원복 =="; kc -X POST "$K/users/$UID_/role-mappings/clients/$CID" -d "[$ROLE]" -o /dev/null -w "%{http_code}\n"
kc -X POST "$K/users/$UID_/logout" -o /dev/null -w "테스트 세션 정리: %{http_code}\n"
