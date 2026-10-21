-- P4(ADR-0018): 수집 run·관측·rejection·소스 등록·원본 항목 F4 전체 의미·blob(bytea)·투영 재시도 열.
-- 원자성은 여전히 "같은 DataSource를 공유하는 어댑터 조합"의 성질이다(DESIGN-PATTERNS §10).

-- 원본 항목: state(active|deleted)·순서 스킴·메타데이터·콘텐츠(inline jsonb 또는 blob 참조). tombstone은 본문·메타 NULL.
-- JSON은 TEXT로 둔다(jsonb는 공백·키 순서를 정규화해 원문 바이트가 바뀐다 — 어댑터 소유 JSON은 그대로 돌려준다).
ALTER TABLE raw_items RENAME COLUMN body TO metadata;
ALTER TABLE raw_items
  ALTER COLUMN source_version DROP NOT NULL,
  ALTER COLUMN metadata DROP NOT NULL,
  ALTER COLUMN metadata TYPE TEXT USING metadata::text,
  ADD COLUMN state              TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'deleted')),
  ADD COLUMN resource_type      TEXT,
  ADD COLUMN order_scheme       TEXT,
  ADD COLUMN order_position     TEXT,
  ADD COLUMN source_created_at  TEXT,
  ADD COLUMN source_updated_at  TEXT,
  ADD COLUMN content_storage    TEXT CHECK (content_storage IN ('inline', 'blob')),
  ADD COLUMN content_media_type TEXT,
  ADD COLUMN content_inline     TEXT,
  ADD COLUMN content_sha256     TEXT,
  ADD COLUMN content_size       BIGINT,
  ADD COLUMN deleted_at         TIMESTAMPTZ;
CREATE INDEX raw_items_source_state ON raw_items (source_instance_id, state, external_id);

-- blob: 내용 주소(sha256). 페이지 커밋과 같은 트랜잭션에 쓰인다(ADR-0018 §5). 참조 없는 행은 GC가 지운다.
CREATE TABLE raw_blobs (
  sha256     TEXT PRIMARY KEY,
  media_type TEXT NOT NULL,
  size       BIGINT NOT NULL,
  data       BYTEA NOT NULL,
  stored_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE source_instances (
  id                    TEXT PRIMARY KEY,
  kind                  TEXT NOT NULL,
  display_name          TEXT NOT NULL,
  config_schema_version INTEGER NOT NULL,
  config                TEXT NOT NULL,
  credential_ref        TEXT NOT NULL,
  enabled               BOOLEAN NOT NULL,
  created_at            TIMESTAMPTZ NOT NULL,
  updated_at            TIMESTAMPTZ NOT NULL
);

CREATE TABLE collection_runs (
  run_id             TEXT PRIMARY KEY,
  source_instance_id TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('running', 'succeeded', 'succeeded_with_rejections', 'partially_succeeded', 'failed', 'cancelled')),
  started_at         TIMESTAMPTZ NOT NULL,
  finished_at        TIMESTAMPTZ,
  pages_applied      INTEGER NOT NULL DEFAULT 0,
  upserted           INTEGER NOT NULL DEFAULT 0,
  deleted            INTEGER NOT NULL DEFAULT 0,
  unchanged          INTEGER NOT NULL DEFAULT 0,
  rejected           INTEGER NOT NULL DEFAULT 0,
  error_count        INTEGER NOT NULL DEFAULT 0,
  error_kind         TEXT,
  error_summary      TEXT,
  coverage           TEXT CHECK (coverage IN ('incremental', 'complete_snapshot'))
);
CREATE INDEX collection_runs_source_started ON collection_runs (source_instance_id, started_at DESC, run_id);

-- 이번 run에서 관측한 키 — succeeded + complete_snapshot 완료의 reconcile 근거(F5). 완료 시 지운다.
CREATE TABLE run_observations (
  run_id      TEXT NOT NULL,
  external_id TEXT NOT NULL,
  PRIMARY KEY (run_id, external_id)
);

CREATE TABLE raw_rejections (
  id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id                 TEXT NOT NULL,
  source_instance_id     TEXT NOT NULL,
  captured_at            TIMESTAMPTZ NOT NULL,
  observed_at            TIMESTAMPTZ NOT NULL,
  failure_kind           TEXT NOT NULL CHECK (failure_kind IN ('invalid_item', 'unsupported_item')),
  message                TEXT NOT NULL,
  provider_locator       TEXT,
  quarantined_storage    TEXT CHECK (quarantined_storage IN ('inline', 'blob')),
  quarantined_media_type TEXT,
  quarantined_inline     TEXT,
  quarantined_sha256     TEXT,
  quarantined_size       BIGINT
);
CREATE INDEX raw_rejections_run ON raw_rejections (run_id, id);

-- 마지막 complete snapshot 완료(F5·F11 due 판정)
CREATE TABLE source_snapshots (
  source_instance_id TEXT PRIMARY KEY,
  completed_at       TIMESTAMPTZ NOT NULL,
  deletion_count     INTEGER NOT NULL
);

-- 투영 재시도(ADR-0018 §6): 사이클당 1회, backoff 상한 5분, 마지막 오류(비밀·원문 없음)
ALTER TABLE projection_requests
  ADD COLUMN attempts        INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at TIMESTAMPTZ,
  ADD COLUMN last_error      TEXT;
