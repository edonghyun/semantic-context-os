#!/bin/bash
# PostgreSQL 복원(T10.1, F10 리뷰 A I1): scos-restore.sh <dump> --yes. 대상 DB를 덤프 시점 그대로 되돌린다 — 수집·MCP를 멈춘 뒤에만.
# - 두 번째 인자가 정확히 --yes가 아니면 아무것도 하지 않는다(종료 2).
# - 대상: SCOS_PG_CONTAINER·SCOS_PG_USER·SCOS_PG_DB. 호출자 환경이 .env보다 우선한다(연습용 대상을 줬는데 .env의 운영 대상을 덮어쓰지 않게).
# - 실행 직전에 대상과 다른 연결 수를 남기고, 대상 DB에 다른 연결이 있으면 거부한다(종료 1).
# - 대상 DB 크기의 2배 여유 공간이 없으면 시작하지 않는다(복원은 DB를 하나 더 만들고 WAL·인덱스 임시 공간을 쓴다).
# - 새 빈 DB(<db>__restore_<시각>)에 --single-transaction으로 복원한 뒤에만, 원래 DB의 새 연결을 막고 다시 확인한 다음 이름을 바꾼다. 덤프 뒤 생긴 객체(새 마이그레이션의
#   테이블 등)가 남지 않는다. 복원·교체가 실패하거나 신호를 받으면 원래 DB의 연결을 다시 허용하고 새 DB만 지운다(원래 이름으로 되돌리지 못하면 둘 다 남긴다).
set -euo pipefail
# shellcheck source=scos-common.sh
. "$(dirname "$0")/scos-common.sh"

if [ "$#" -ne 2 ] || [ "$2" != "--yes" ] || [ ! -f "$1" ]; then
  echo "usage: scos-restore.sh <dump> --yes   (대상 DB를 덤프로 바꾼다 — 데몬·MCP를 먼저 멈춘다)" >&2
  exit 2
fi
dump="$1"

# 호출자가 준 대상은 .env보다 우선한다
caller_container="${SCOS_PG_CONTAINER-}"
caller_user="${SCOS_PG_USER-}"
caller_db="${SCOS_PG_DB-}"
scos_load_env
docker="${SCOS_DOCKER:-docker}"
container="${caller_container:-${SCOS_PG_CONTAINER:-scos-pg}}"
user="${caller_user:-${SCOS_PG_USER:-scos}}"
db="${caller_db:-${SCOS_PG_DB:-scos}}"
# 단순 식별자, 38자 이하 — 접미사 "__restore_<14자리 시각>"(24자)·"__replaced_<14자리 시각>"(25자)를 붙여도 PostgreSQL 식별자 상한 63자
# 안이어야 한다(넘으면 PostgreSQL이 잘라 로그의 이름과 실제 이름이 달라진다)
if ! [[ "$db" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || [ "${#db}" -gt 38 ]; then
  echo "scos-restore.sh: SCOS_PG_DB must be a simple identifier of at most 38 characters" >&2
  exit 2
fi

psql_admin() {
  "$docker" exec "$container" psql -U "$user" -d postgres -v ON_ERROR_STOP=1 -Atc "$1"
}

others=$(psql_admin "SELECT count(*) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid()")
scos_log info restore.target "{\"container\":$(scos_json_string "$container"),\"db\":$(scos_json_string "$db"),\"otherConnections\":$others,\"file\":$(scos_json_string "$dump")}"
if [ "$others" != "0" ]; then
  scos_log error restore.refused "{\"reason\":\"target database has other connections — stop the daemon and MCP first\"}"
  exit 1
fi

# 복원은 새 DB를 하나 더 만든다 — 대상 DB 크기의 2배 여유가 없으면 시작하지 않는다. 사본 하나에 더해 --single-transaction 복원은
# 데이터만큼의 WAL과 인덱스 빌드 임시 파일을 함께 쓴다(실측 전 보수 기준, 이 기기는 ENOSPC 이력이 있다 — F10 재확인 2 RC-5).
# 대상 DB가 없으면(새 호스트) 크기는 0이다 — pg_database_size('<이름>')은 없는 이름에 오류를 내므로 pg_database 행으로 묻는다(T15.5 A7)
size=$(psql_admin "SELECT COALESCE((SELECT pg_database_size(oid) FROM pg_database WHERE datname = '$db'), 0)")
free_kb=$("$docker" exec "$container" sh -c 'df -Pk "$PGDATA" 2>/dev/null || df -Pk /var/lib/postgresql/data' | awk 'NR == 2 { print $4 }')
if ! [[ "$free_kb" =~ ^[0-9]+$ ]] || [ $((free_kb * 1024)) -lt $((size * 2)) ]; then
  scos_log error restore.refused "{\"reason\":\"not enough free space (twice the database size)\",\"databaseBytes\":$size,\"freeKb\":$(scos_json_string "$free_kb")}"
  exit 1
fi

stamp="$(date -u +%Y%m%d%H%M%S)"
fresh="${db}__restore_${stamp}"
replaced="${db}__replaced_${stamp}"
drop_fresh() { "$docker" exec "$container" dropdb -U "$user" --if-exists "$fresh" || true; }

# 단계(F10 재확인 2 RC-1·RC-4, F15 T15.0): created = 새 DB를 만든다(원래 DB는 그대로), blocked = 원래 DB의 새 연결을 막았다,
# replaced = 원래 DB가 $replaced 이름이다. 이 단계들에서 끝나면(처리한 실패든 set -e·신호든) undo_swap이 원래 DB를 되살리고 새 DB를
# 지운다. 원래 이름으로 되돌리지 못하면 두 DB를 모두 남긴다.
phase=""
undo_swap() { # <event> <추가 JSON 필드>
  local was="$phase"
  phase=""
  trap - EXIT
  if [ "$was" = replaced ]; then
    if psql_admin "ALTER DATABASE \"$replaced\" RENAME TO \"$db\""; then
      was=blocked
    else
      local recovery="ALTER DATABASE \"$replaced\" RENAME TO \"$db\"; ALTER DATABASE \"$db\" ALLOW_CONNECTIONS true"
      scos_log error restore.failed "{\"file\":$(scos_json_string "$dump"),$2,\"kept\":[$(scos_json_string "$replaced"),$(scos_json_string "$fresh")],\"recovery\":$(scos_json_string "$recovery")}"
      exit 1
    fi
  fi
  [ "$was" = blocked ] && { psql_admin "ALTER DATABASE \"$db\" ALLOW_CONNECTIONS true" || true; }
  drop_fresh
  # kept = 복원 뒤 남은 DB 이름. 대상 DB가 없던 곳(새 호스트)에서는 새 DB도 지웠으니 남은 것이 없다(T15.5 A7)
  local kept
  kept=$(scos_json_string "$db")
  [ "${exists-}" = 0 ] && kept="[]"
  scos_log error "$1" "{\"file\":$(scos_json_string "$dump"),$2,\"kept\":$kept}"
  exit 1
}
on_exit() {
  local status=$?
  [ -n "$phase" ] && undo_swap restore.failed "\"reason\":\"interrupted during swap\",\"status\":$status"
  return 0
}
trap on_exit EXIT
trap 'exit 1' INT TERM HUP

# 대상 DB가 있는지는 새 DB를 만들기 전에 본다 — 그 뒤 어느 단계에서 실패해도 kept(복원 뒤 남은 DB 이름)를 바르게 알린다(T15.5 A7)
exists=$(psql_admin "SELECT count(*) FROM pg_database WHERE datname = '$db'")
phase=created
"$docker" exec "$container" createdb -U "$user" "$fresh"
if ! "$docker" exec -i "$container" pg_restore -U "$user" -d "$fresh" --no-owner --single-transaction < "$dump"; then
  undo_swap restore.failed "\"reason\":\"pg_restore failed\""
fi

if [ "$exists" != "0" ]; then
  # 교체 동안 새 연결을 막고, 그 사이에 생긴 연결이 있는지 다시 본다(F10 리뷰 A R-m1)
  phase=blocked
  psql_admin "ALTER DATABASE \"$db\" ALLOW_CONNECTIONS false"
  late=$(psql_admin "SELECT count(*) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid()")
  if [ "$late" != "0" ]; then
    undo_swap restore.refused "\"reason\":\"target database got connections during restore\",\"otherConnections\":$late"
  fi
  if ! psql_admin "ALTER DATABASE \"$db\" RENAME TO \"$replaced\""; then
    undo_swap restore.failed "\"reason\":\"rename failed\""
  fi
  phase=replaced
fi
if ! psql_admin "ALTER DATABASE \"$fresh\" RENAME TO \"$db\""; then
  undo_swap restore.failed "\"reason\":\"rename failed\""
fi
phase=""
if [ "$exists" != "0" ] && ! "$docker" exec "$container" dropdb -U "$user" "$replaced"; then
  # 교체는 끝났다 — 옛 DB만 남았다. 성공으로 끝내고 지울 이름을 알린다
  scos_log warn restore.old_database_left "{\"database\":$(scos_json_string "$replaced")}"
fi
scos_log info restore.completed "{\"file\":$(scos_json_string "$dump"),\"db\":$(scos_json_string "$db")}"
