echo "$*" >> "$SCOS_RECORDS/docker.calls"
unexpected() { echo "unexpected docker $*" >&2; exit 97; }
[ "$1" = exec ] || unexpected "$@"
if [ "$2" = -i ]; then
  [ "$#" = 10 ] && [ "$4 $5 $7 $9 ${10}" = "pg_restore -U -d --no-owner --single-transaction" ] || unexpected "$@"
  cat > "$SCOS_RECORDS/docker.stdin"
  if [ -n "${FAKE_RESTORE_HOLD-}" ]; then
    touch "$SCOS_RECORDS/restore.held"
    while [ ! -f "$SCOS_RECORDS/restore.release" ]; do sleep 0.05; done
  fi
  [ -n "${FAKE_RESTORE_FAIL-}" ] && exit 1
  exit 0
fi
case "$3" in
  psql)
    [ "$#" = 11 ] && [ "$4 $6 $7 $8 $9 ${10}" = "-U -d postgres -v ON_ERROR_STOP=1 -Atc" ] || unexpected "$@"
    sql="${11}"
    case "$sql" in
      *pg_stat_activity*)
        n=$(cat "$SCOS_RECORDS/activity.count" 2>/dev/null || echo 0); echo $((n + 1)) > "$SCOS_RECORDS/activity.count"
        if [ "$n" = 0 ]; then echo "${FAKE_CONNECTIONS:-0}"; exit 0; fi
        [ -n "${FAKE_LATE_FAIL-}" ] && exit 2
        if [ -n "${FAKE_LATE_HOLD-}" ]; then
          touch "$SCOS_RECORDS/late.held"
          while [ ! -f "$SCOS_RECORDS/late.release" ]; do sleep 0.05; done
        fi
        echo "${FAKE_LATE_CONNECTIONS:-0}" ;;
      *pg_database_size*)
        if [ -z "${FAKE_TARGET_MISSING-}" ]; then echo "${FAKE_DB_SIZE:-1000}"
        else
          case "$sql" in *"pg_database_size('"*) echo "ERROR:  database does not exist" >&2; exit 1 ;; esac
          echo 0
        fi ;;
      *"pg_database WHERE"*) [ -n "${FAKE_TARGET_MISSING-}" ] && echo 0 || echo 1 ;;
      *"ALLOW_CONNECTIONS false"|*"ALLOW_CONNECTIONS true") ;;
      *"RENAME TO"*)
        [ -n "${FAKE_RENAME_FAIL-}" ] && exit 1
        case "$sql" in
          *'DATABASE "scos__restore_'*) [ -n "${FAKE_FRESH_RENAME_FAIL-}" ] && exit 1 ;;
          *'DATABASE "scos__replaced_'*) [ -n "${FAKE_REVERT_FAIL-}" ] && exit 1 ;;
        esac ;;
      *) unexpected "$@" ;;
    esac ;;
  sh)
    [ "$#" = 5 ] && [ "$4" = -c ] && [ "$5" = 'df -Pk "$PGDATA" 2>/dev/null || df -Pk /var/lib/postgresql/data' ] || unexpected "$@"
    echo "Filesystem 1024-blocks Used Available Capacity Mounted on"; echo "overlay 100000000 1000 ${FAKE_FREE_KB:-99999000} 1% /var/lib/postgresql/data" ;;
  createdb) [ "$#" = 6 ] && [ "$4" = -U ] || unexpected "$@" ;;
  dropdb)
    if [ "$#" = 7 ] && [ "$4 $6" = "-U --if-exists" ]; then :
    elif [ "$#" = 6 ] && [ "$4" = -U ]; then [ -n "${FAKE_DROP_OLD_FAIL-}" ] && exit 1
    else unexpected "$@"; fi ;;
  *) unexpected "$@" ;;
esac
exit 0
