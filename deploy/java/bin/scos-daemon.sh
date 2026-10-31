#!/bin/bash
# 수집·투영 데몬 래퍼(TS serve.sh·ingest.sh·google-drive-serve.sh 대체).
# - MCP·서버는 SCOS_DAEMON_PORT(기본 8081), 상태·지표는 127.0.0.1:${SCOS_MANAGEMENT_PORT:-8082}/actuator/health(별도 관리 포트)
# - .env 600 검사·적재, 정비 유예 기본 7일(TS --maintenance-grace-days 7), 힙 상한 SCOS_DAEMON_HEAP(기본 2g — GoogleDriveHeapProbeTest 측정 기준)
# - java를 자식으로 띄우고 SIGTERM·SIGINT를 넘긴다. 종료 코드: 143·130(신호로 정상 정지) → 0, 70(루프 사망) → 70, 3(OOM) → 3, 그 밖은 그대로
#   서비스 관리자(systemd·launchd 등)로 띄우고 늘 다시 띄우게 둔다 — 정지는 서비스 관리자의 정지 명령(SIGTERM)으로 한다
set -euo pipefail
# shellcheck source=scos-common.sh
. "$(dirname "$0")/scos-common.sh"

scos_load_env
scos_private_dir "$SCOS_LOG_DIR"
# 데몬은 이 디렉터리에 serve.log(사람)·serve.jsonl(기계)을 직접 쓰고 하루 단위로 회전한다(logging.md §8)
export SCOS_LOG_DIR
export SCOS_DAEMON_MAINTENANCE_GRACE_DAYS="${SCOS_DAEMON_MAINTENANCE_GRACE_DAYS:-7}"
# 없으면 2g, 빈 값이면 -Xmx를 넣지 않는다(컨테이너에서 SCOS_JAVA_OPTS=-XX:MaxRAMPercentage=50을 쓸 때)
heap="${SCOS_DAEMON_HEAP-2g}"
heap_opt=()
[ -n "$heap" ] && heap_opt=("-Xmx$heap")
# SCOS_JAVA_OPTS는 공백으로 나누되 글롭(*·?)은 펼치지 않는다(read -a는 펼치지 않는다)
java_opts=()
read -r -a java_opts <<< "${SCOS_JAVA_OPTS:-}"

# 신호 처리는 java를 띄우기 전에 건다 — 띄운 직후 trap 전에 온 SIGTERM이 래퍼만 죽이고 java를 남기지 않게(리뷰 B m7)
child=""
stopping=0
forward() {
  stopping=1
  if [ -n "$child" ]; then kill -TERM "$child" 2> /dev/null || true; fi
}
trap forward TERM INT

# -XX:+ExitOnOutOfMemoryError: 루프 밖 스레드(HTTP·heartbeat)의 OOM도 JVM을 끝내 종료 코드 3으로 드러나게 한다(리뷰 A m7) —
# 그러지 않으면 데몬이 살아 있지만 일하지 않는 채로 남고, 서비스 관리자는 health를 보지 않는다
"$SCOS_JAVA" -XX:+ExitOnOutOfMemoryError ${heap_opt[@]+"${heap_opt[@]}"} ${java_opts[@]+"${java_opts[@]}"} -jar "$SCOS_JAR" daemon &
child=$!
# 띄우는 사이에 신호가 왔으면 지금 넘긴다
if [ "$stopping" = 1 ]; then kill -TERM "$child" 2> /dev/null || true; fi

set +e
wait "$child"
code=$?
# 신호를 받으면 wait가 먼저 돌아온다 — 자식이 끝날 때까지 다시 기다린다
while kill -0 "$child" 2>/dev/null; do
  wait "$child"
  code=$?
done
set -e

# java가 끝났으니 serve.jsonl에 쓰는 것은 래퍼뿐이다 — 오류 이벤트는 status --log가 보게 그 파일에도 덧붙인다(scos_log_jsonl)
case "$code" in
  0 | 130 | 143)
    scos_log info daemon.stopped "{\"exitCode\":$code}"
    exit 0
    ;;
  70)
    scos_log_jsonl error daemon.loop.died "{\"exitCode\":70}"
    exit 70
    ;;
  3)
    # -XX:+ExitOnOutOfMemoryError. 같은 소스(대개 Drive)에서 되풀이되면 힙 부족이다(SCOS_DAEMON_HEAP를 올린다)
    scos_log_jsonl error daemon.out_of_memory "{\"exitCode\":3}"
    exit 3
    ;;
  *)
    scos_log_jsonl error daemon.exited "{\"exitCode\":$code}"
    exit "$code"
    ;;
esac
