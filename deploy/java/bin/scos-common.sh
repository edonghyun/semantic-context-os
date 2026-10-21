# shellcheck shell=bash
# 운영 래퍼 공통(source로 읽는다). macOS /bin/bash 3.2와 GNU 둘 다에서 돈다.
# 로그는 stderr에 TS JsonLineLogger와 같은 JSON 한 줄({"ts","level","event","detail"?})로 쓴다 — serve.log·status --log가 읽는다.

SCOS_HOME="${SCOS_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
SCOS_JAR="${SCOS_JAR:-$SCOS_HOME/scos-backend.jar}"
SCOS_JAVA="${SCOS_JAVA:-java}"
SCOS_ENV_FILE="${SCOS_ENV_FILE:-$SCOS_HOME/.env}"
SCOS_LOG_DIR="${SCOS_LOG_DIR:-$SCOS_HOME/logs}"

# JSON 문자열(따옴표 포함). JSON.stringify처럼 역슬래시·큰따옴표·제어 문자(U+0000–U+001F)를 이스케이프한다. 바이트 단위(LC_ALL=C)로
# 돌아 UTF-8 다중 바이트 문자는 그대로 지나간다.
scos_json_string() {
  local LC_ALL=C s="$1" out="" c code i
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in
      '"') out+='\"' ;;
      '\') out+='\\' ;;
      $'\n') out+='\n' ;;
      $'\r') out+='\r' ;;
      $'\t') out+='\t' ;;
      *)
        printf -v code '%d' "'$c"
        if [ "$code" -ge 0 ] && [ "$code" -lt 32 ]; then
          printf -v c '\\u%04x' "$code"
        fi
        out+="$c"
        ;;
    esac
  done
  printf '"%s"' "$out"
}

# scos_log <level> <event> [detail JSON 객체]
scos_log() {
  local ts
  ts="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
  if [ -n "${3-}" ]; then
    printf '{"ts":"%s","level":"%s","event":"%s","detail":%s}\n' "$ts" "$1" "$2" "$3" >&2
  else
    printf '{"ts":"%s","level":"%s","event":"%s"}\n' "$ts" "$1" "$2" >&2
  fi
}

# 파일 권한 8진수. GNU stat -c를 먼저 시도하고 성공했을 때만 그 출력을 쓴다 — GNU에서 -f는 파일 시스템 정보 옵션이라 실패하면서도 stdout에
# 쓴다(리뷰 B I1). BSD(macOS) stat에는 -c가 없어 실패하고 -f '%Lp'로 간다.
scos_mode() {
  if stat -c '%a' "$1" > /dev/null 2>&1; then
    stat -c '%a' "$1"
  else
    stat -f '%Lp' "$1"
  fi
}

# .env를 읽어 내보낸다(TS serve.sh·mcp.sh와 같은 계약): 600이 아니면 600으로 고치고 경고, 고칠 수 없거나 없으면 거부(종료 코드 1).
scos_load_env() {
  if [ ! -f "$SCOS_ENV_FILE" ]; then
    scos_log error wrapper.env.missing "{\"path\":$(scos_json_string "$SCOS_ENV_FILE")}"
    exit 1
  fi
  local mode
  mode="$(scos_mode "$SCOS_ENV_FILE")"
  if [ "$mode" != "600" ]; then
    chmod 600 "$SCOS_ENV_FILE" 2>/dev/null || true
    if [ "$(scos_mode "$SCOS_ENV_FILE")" != "600" ]; then
      scos_log error wrapper.env.permissions "{\"path\":$(scos_json_string "$SCOS_ENV_FILE"),\"mode\":$(scos_json_string "$mode")}"
      exit 1
    fi
    scos_log warn wrapper.env.permissions.fixed "{\"path\":$(scos_json_string "$SCOS_ENV_FILE"),\"was\":$(scos_json_string "$mode")}"
  fi
  set -a
  # shellcheck disable=SC1090
  . "$SCOS_ENV_FILE"
  set +a
}

# 운영 사용자만 읽는 로그 디렉터리: 래퍼가 새로 만들 때만 700으로 둔다. 이미 있던 디렉터리(공유 경로일 수 있다)는 권한을 바꾸지 않고, 그룹·
# 다른 사용자에게 열려 있으면 경고만 한다(리뷰 A m8).
scos_private_dir() {
  if [ -d "$1" ]; then
    local mode
    mode="$(scos_mode "$1")"
    case "$mode" in
      *00) ;;
      *) scos_log warn wrapper.log_dir.permissions "{\"path\":$(scos_json_string "$1"),\"mode\":$(scos_json_string "$mode")}" ;;
    esac
    return 0
  fi
  mkdir -p "$1"
  chmod 700 "$1"
}
