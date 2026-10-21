-- 스냅샷 축소 보호(ADR-0028, TS와 다른 의도한 차이). complete_snapshot 정리가 정리 직전 active의 너무 많은 부분을 지우려 하면 정리를
-- 건너뛴다. 기준은 데몬 기본값(SCOS_SNAPSHOT_GUARD_*)이고 소스별 값이 있으면 그것을 쓴다. NULL = 기본값 — 기존 행은 NULL로 남는다.
ALTER TABLE source_instances
  ADD COLUMN shrink_guard_min_active         INTEGER CHECK (shrink_guard_min_active >= 1),
  ADD COLUMN shrink_guard_max_delete_percent INTEGER CHECK (shrink_guard_max_delete_percent BETWEEN 1 AND 100);

-- 소스별 마지막 보호 판정과 정리 없이 이어진 보호 횟수. 정리가 일어나면 행이 지워진다(source status의 snapshotReconcileGuard).
CREATE TABLE snapshot_reconcile_guards (
  source_instance_id TEXT PRIMARY KEY,
  run_id             TEXT NOT NULL,
  guarded_at         TIMESTAMPTZ NOT NULL,
  unobserved_count   INTEGER NOT NULL CHECK (unobserved_count > 0),
  active_count       INTEGER NOT NULL CHECK (active_count >= unobserved_count),
  consecutive_count  INTEGER NOT NULL CHECK (consecutive_count >= 1)
);

-- 운영자의 축소 허용(source accept-shrink, 감사 기록). consumed_at·expired_at이 둘 다 NULL이면 대기 중이다. 다음 succeeded +
-- complete_snapshot 완료 한 번이 기준 없이 정리하고 consumed_at·run_id를 채운다. 그 완료가 허용 유효 기간(기본 7일) 뒤면 쓰지 않고
-- expired_at을 채운다.
CREATE TABLE snapshot_shrink_acceptances (
  source_instance_id TEXT PRIMARY KEY,
  accepted_at        TIMESTAMPTZ NOT NULL,
  consumed_at        TIMESTAMPTZ,
  consumed_run_id    TEXT,
  expired_at         TIMESTAMPTZ,
  CHECK ((consumed_at IS NULL) = (consumed_run_id IS NULL)),
  CHECK (consumed_at IS NULL OR expired_at IS NULL)
);

-- complete_snapshot run이 시작할 때의 active 수(정리 판정의 분모 — 이번 run이 새로 넣은 항목은 빼고 본다, ADR-0028 §1). 소스마다 마지막 run
-- 하나만 둔다. 완료는 자기 run_id의 행만 쓴다(없으면 정리 직전 active 수).
CREATE TABLE snapshot_run_baselines (
  source_instance_id TEXT PRIMARY KEY,
  run_id             TEXT NOT NULL,
  active_count       INTEGER NOT NULL CHECK (active_count >= 0)
);
