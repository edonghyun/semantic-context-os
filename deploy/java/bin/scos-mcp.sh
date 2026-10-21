#!/bin/bash
# MCP stdio 서버 래퍼(TS mcp.sh 대체). 클라이언트가 세션마다 띄운다 — stdout은 JSON-RPC 전용이라 래퍼는 stdout에 쓰지 않는다.
# - .env 600 검사·적재, 질의 로그 SCOS_QUERY_LOG(기본 logs/mcp-queries.jsonl)를 디렉터리 700·파일 600으로 만든다(Java는 기본 umask)
# - CDS 아카이브(SCOS_CDS_ARCHIVE, 기본 cds/scos-mcp.jsa)가 있으면 쓴다(ADR-0030). JVM 경고는 stderr로. 종료 코드는 java 그대로(exec)
set -euo pipefail
# shellcheck source=scos-common.sh
. "$(dirname "$0")/scos-common.sh"

# 호출자가 준 SCOS_QUERY_LOG(빈 값 = 끔)는 .env보다 우선한다 — o2 비교가 운영 질의 로그에 쓰지 않게(F11 리뷰 I3, 복원과 같은 원칙)
caller_query_log_set="${SCOS_QUERY_LOG+set}"
caller_query_log="${SCOS_QUERY_LOG-}"
scos_load_env
if [ -n "$caller_query_log_set" ]; then SCOS_QUERY_LOG="$caller_query_log"; fi
umask 077
# 없으면 기본 경로, 빈 값이면 질의 로그를 끈다(o2 비교 재생 — 질의 원문을 디스크에 남기지 않는다)
export SCOS_QUERY_LOG="${SCOS_QUERY_LOG-$SCOS_LOG_DIR/mcp-queries.jsonl}"
if [ -n "$SCOS_QUERY_LOG" ]; then
  scos_private_dir "$(dirname "$SCOS_QUERY_LOG")"
  [ -e "$SCOS_QUERY_LOG" ] || install -m 600 /dev/null "$SCOS_QUERY_LOG"
  chmod 600 "$SCOS_QUERY_LOG"
fi

# JVM 통합 로그(경고 포함)의 기본 출력은 stdout이다 — MCP stdout은 JSON-RPC 전용이라, 기본 출력을 끄고 경고 이상을 stderr로 보낸다.
# 맞지 않는 CDS 아카이브 경고("[warning][cds] …")가 대표 사례다(리뷰 B I2, JDK 21에서 확인)
jvm_log=(-Xlog:disable -Xlog:all=warning:stderr)
archive="${SCOS_CDS_ARCHIVE:-$SCOS_HOME/cds/scos-mcp.jsa}"
if [ -f "$archive" ]; then
  exec "$SCOS_JAVA" "${jvm_log[@]}" "-XX:SharedArchiveFile=$archive" -jar "$SCOS_JAR" mcp
fi
exec "$SCOS_JAVA" "${jvm_log[@]}" -jar "$SCOS_JAR" mcp
