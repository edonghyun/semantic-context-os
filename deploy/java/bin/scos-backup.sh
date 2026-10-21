#!/bin/bash
# PostgreSQL 백업(T10.1): 컨테이너 안의 pg_dump -Fc → backups/scos-<UTC>.dump(600). pg_restore --list로 읽히는지 확인한 뒤에만 남긴다.
# 보존 기간(SCOS_BACKUP_RETENTION_DAYS, 기본 14일)보다 오래된 scos-*.dump를 지운다. 실패하면 부분 파일 없이 종료 코드 1.
set -euo pipefail
# shellcheck source=scos-common.sh
. "$(dirname "$0")/scos-common.sh"

scos_load_env
docker="${SCOS_DOCKER:-docker}"
container="${SCOS_PG_CONTAINER:-scos-pg}"
user="${SCOS_PG_USER:-scos}"
db="${SCOS_PG_DB:-scos}"
dir="${SCOS_BACKUP_DIR:-$SCOS_HOME/backups}"
days="${SCOS_BACKUP_RETENTION_DAYS:-14}"

umask 077
scos_private_dir "$dir"
# 이전 백업이 SIGKILL 등으로 죽으면 .partial이 남는다 — 보존 glob(scos-*.dump)에 걸리지 않으므로 시작할 때 지운다(리뷰 A m1)
find "$dir" -maxdepth 1 -type f -name 'scos-*.dump.partial' -exec rm -f {} +
file="$dir/scos-$(date -u +%Y%m%dT%H%M%SZ).dump"
partial="$file.partial"
trap 'rm -f "$partial"' EXIT

fail() {
  scos_log error backup.failed "{\"reason\":$(scos_json_string "$1")}"
  exit 1
}
"$docker" exec "$container" pg_dump -U "$user" -d "$db" -Fc > "$partial" || fail "pg_dump failed"
[ -s "$partial" ] || fail "empty dump"
"$docker" exec -i "$container" pg_restore --list < "$partial" > /dev/null || fail "dump unreadable"
mv "$partial" "$file"
chmod 600 "$file"

pruned=$(find "$dir" -maxdepth 1 -type f -name 'scos-*.dump' -mtime +"$days" -print -exec rm -f {} + | wc -l | tr -d ' ')
bytes=$(wc -c < "$file" | tr -d ' ')
scos_log info backup.completed "{\"file\":$(scos_json_string "$file"),\"bytes\":$bytes,\"pruned\":$pruned}"
