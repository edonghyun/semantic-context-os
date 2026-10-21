#!/bin/bash
# 디스크 감시(T10.1): SCOS_DISK_PATHS(콜론 구분, 기본 SCOS_HOME)의 사용률이 SCOS_DISK_MAX_PERCENT(기본 85)를 넘으면
# error disk.usage.high를 남기고 종료 코드 1. df를 읽지 못하는 경로가 있으면 2(1보다 앞선다). 읽지 못해도 나머지 경로를
# 끝까지 본다 — 그 뒤에 숨은 가득 찬 디스크를 놓치지 않으려는 것이다(B9). .env를 읽지 않는다 — 설정이 깨져도 감시는 돈다.
# Docker Desktop의 PostgreSQL 데이터는 호스트 디스크의 가상 디스크 파일에 있으므로 호스트 경로를 감시하면 된다.
set -euo pipefail
# shellcheck source=scos-common.sh
. "$(dirname "$0")/scos-common.sh"

max="${SCOS_DISK_MAX_PERCENT:-85}"
if ! [[ "$max" =~ ^[0-9]+$ ]]; then
  scos_log error disk.threshold.invalid "{\"maxPercent\":$(scos_json_string "$max")}"
  exit 2
fi
status=0
unreadable=0
IFS=':' read -r -a paths <<< "${SCOS_DISK_PATHS:-$SCOS_HOME}"
for path in "${paths[@]}"; do
  # 사용률은 끝에서부터 찾은 첫 "숫자%" 열이다 — 파일 시스템 이름·마운트 지점에 공백이 있어도 밀리지 않는다(리뷰 B m4)
  if ! out=$(df -P "$path" 2> /dev/null); then out=""; fi
  used=$(printf '%s\n' "$out" | awk 'NR == 2 { for (i = NF; i > 0; i--) if ($i ~ /^[0-9]+%$/) { print substr($i, 1, length($i) - 1); exit } }')
  if ! [[ "$used" =~ ^[0-9]+$ ]]; then
    scos_log error disk.usage.unreadable "{\"path\":$(scos_json_string "$path")}"
    unreadable=1
    continue
  fi
  detail="{\"path\":$(scos_json_string "$path"),\"usedPercent\":$used,\"maxPercent\":$max}"
  if [ "$used" -gt "$max" ]; then
    scos_log error disk.usage.high "$detail"
    status=1
  else
    scos_log info disk.usage.ok "$detail"
  fi
done
if [ "$unreadable" -eq 1 ]; then exit 2; fi
exit "$status"
