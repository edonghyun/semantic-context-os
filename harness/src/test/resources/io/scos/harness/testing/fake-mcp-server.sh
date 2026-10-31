# 가벼운 가짜 MCP 서버(stdio JSON-RPC, bash) — 서버를 실행 파일로 받는 명령(evaluate·compare-parallel)의 시험용.
# FakeMcpServer(JVM)와 같은 계약의 부분집합이다: 기동이 밀리초라 --timeout-seconds가 JVM 기동 시간을 재지 않는다.
# 요청 한 줄은 하네스 클라이언트가 쓰는 압축 JSON({"jsonrpc":"2.0","id":n,"method":"…",…})이라고 본다.
# FAKE_RESPONSE=<json> → tools/call 응답 result(필수), FAKE_DIE_AFTER=n → n번째 tools/call에서 종료,
# FAKE_ENV_LOG=<path> → 기동 때 받은 SCOS_QUERY_LOG(없으면 <unset>), FAKE_CALL_LOG=<path> → 받은 tools/call 요청 줄.
if [ -n "${FAKE_ENV_LOG:-}" ]; then
  printf 'SCOS_QUERY_LOG=%s\n' "${SCOS_QUERY_LOG-<unset>}" >> "$FAKE_ENV_LOG"
fi
calls=0
while IFS= read -r line; do
  [[ $line =~ \"id\":([0-9]+) ]] || continue
  id=${BASH_REMATCH[1]}
  case $line in
    *'"method":"initialize"'*)
      body='"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"fake","version":"0"}}' ;;
    *'"method":"tools/list"'*)
      body='"result":{"tools":[]}' ;;
    *'"method":"tools/call"'*)
      calls=$((calls + 1))
      if [ -n "${FAKE_CALL_LOG:-}" ]; then printf '%s\n' "$line" >> "$FAKE_CALL_LOG"; fi
      if [ "$calls" = "${FAKE_DIE_AFTER:-}" ]; then exit 1; fi
      body="\"result\":$FAKE_RESPONSE" ;;
    *)
      body='"error":{"code":-32601,"message":"unknown method"}' ;;
  esac
  printf '{"jsonrpc":"2.0","id":%s,%s}\n' "$id" "$body"
done
