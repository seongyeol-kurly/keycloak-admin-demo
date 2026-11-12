#!/bin/bash
# EC2에서 실행: 브라우저 흐름을 curl로 재현 (쿠키 저장소 1개 = 브라우저 1개)
set -u
IP=15.165.81.59
J=/tmp/e2e.jar; rm -f $J
C="curl -sk -b $J -c $J --resolve $IP:443:127.0.0.1 --resolve $IP:8081:127.0.0.1 --resolve $IP:8082:127.0.0.1"
ORDER=http://$IP:8081; SETTLE=http://$IP:8082
step() { echo; echo "== $* =="; }
summary() { # html file
  echo "  환영문구: $(grep -o '환영합니다, <span>[^<]*' "$1" | sed 's/<span>//')"
  echo "  권한: $(grep -oE 'class="chip[^"]*">[^<]+' "$1" | sed 's/.*>//' | tr '\n' ' ')"
}
kc_sessions() {
  source /opt/keycloak-test/.env
  AT=$(curl -s -d grant_type=password -d client_id=admin-cli -d username=$KC_BOOTSTRAP_ADMIN_USERNAME -d password=$KC_BOOTSTRAP_ADMIN_PASSWORD http://localhost:8080/realms/master/protocol/openid-connect/token | jq -r .access_token)
  UID_=$(curl -s -H "Authorization: Bearer $AT" "http://localhost:8080/admin/realms/demo/users?username=testuser&exact=true" | jq -r '.[0].id')
  curl -s -H "Authorization: Bearer $AT" http://localhost:8080/admin/realms/demo/users/$UID_/sessions | jq -c '[.[] | {sso_session: .id[0:8], clients: [.clients[]]}]'
}

step "1. 주문 어드민 미로그인 접근"
$C -o /dev/null -w "  GET / -> %{http_code} -> %{redirect_url}\n" $ORDER/

step "2. 'Keycloak으로 로그인' 클릭 → Keycloak 로그인 화면"
PAGE=$($C -L $ORDER/oauth2/authorization/keycloak)
ACTION=$(echo "$PAGE" | grep -o 'action="[^"]*"' | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
[ -n "$ACTION" ] && echo "  비밀번호 입력 화면 표시됨" || echo "  !! 로그인 화면 없음"

step "3. testuser 로그인 → 주문 어드민 홈"
$C -L -o /tmp/e2e_order.html -w "  최종: %{url_effective} (%{http_code})\n" -d username=testuser -d 'password=Test1234!' -d credentialId= "$ACTION"
summary /tmp/e2e_order.html
echo "  Keycloak 세션: $(kc_sessions)"

step "4. 정산 어드민 → 버튼 클릭 (SSO 원클릭, 비밀번호 화면 없이)"
$C -o /dev/null -w "  GET / -> %{http_code} -> %{redirect_url}\n" $SETTLE/
$C -L -o /tmp/e2e_settle.html -w "  최종: %{url_effective} (%{http_code})\n" $SETTLE/oauth2/authorization/keycloak
grep -q 'name="password"' /tmp/e2e_settle.html && echo "  !! 비밀번호 화면이 떴음" || echo "  비밀번호 화면 없이 로그인됨"
summary /tmp/e2e_settle.html
echo "  Keycloak 세션: $(kc_sessions)"

step "5. 주문 어드민만 로그아웃"
CSRF=$($C $ORDER/ | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"$//')
$C -o /dev/null -w "  POST /logout -> %{http_code} -> %{redirect_url}\n" -d "_csrf=$CSRF" $ORDER/logout
sleep 1
$C -o /dev/null -w "  주문 GET / -> %{http_code} -> %{redirect_url}  (로그아웃됨)\n" $ORDER/
$C -o /tmp/e2e_settle2.html -w "  정산 GET / -> %{http_code}  (유지되는지)\n" $SETTLE/
summary /tmp/e2e_settle2.html
echo "  Keycloak 세션: $(kc_sessions)"

step "6. 주문 어드민 다시 로그인 버튼 클릭 (SSO 원클릭)"
$C -L -o /tmp/e2e_order2.html -w "  최종: %{url_effective} (%{http_code})\n" $ORDER/oauth2/authorization/keycloak
grep -q 'name="password"' /tmp/e2e_order2.html && echo "  !! 비밀번호 화면이 떴음" || echo "  비밀번호 화면 없이 로그인됨"
summary /tmp/e2e_order2.html

step "어드민 로그"
docker logs order-admin 2>&1 | grep -E "logout|ERROR|WARN" | tail -5
