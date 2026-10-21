-- 소스별 감독 주기(ADR-0022 §3). TS는 데몬 옵션 --interval-seconds-for <id>=<s>(성공 뒤 간격)와 snapshotIntervalMsBySource(스냅샷
-- 모드 간격)로 줬다. Java는 등록에 둔다. NULL = 데몬 기본값(SCOS_DAEMON_INTERVAL·SCOS_DAEMON_SNAPSHOT_INTERVAL). 기존 행은 NULL로 남아
-- 동작이 바뀌지 않는다.
ALTER TABLE source_instances
  ADD COLUMN collect_interval_seconds  INTEGER CHECK (collect_interval_seconds > 0),
  ADD COLUMN snapshot_interval_seconds INTEGER CHECK (snapshot_interval_seconds > 0);
