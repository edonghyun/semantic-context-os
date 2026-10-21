-- 수집 모듈(collection): 작업 점유·체크포인트·원본 항목·투영 요청(Outbox). ADR-0006·0007·0013.
-- 원자성은 이 네 테이블이 같은 DataSource/트랜잭션을 공유하는 어댑터 조합의 성질이다(DESIGN-PATTERNS §10).

CREATE TABLE work_claims (
  source_instance_id TEXT PRIMARY KEY,
  owner_id           TEXT NOT NULL,
  fencing_token      BIGINT NOT NULL,
  expires_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE source_checkpoints (
  source_instance_id TEXT PRIMARY KEY,
  revision           BIGINT NOT NULL,
  schema_version     INTEGER NOT NULL,
  value              JSONB NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE raw_items (
  source_instance_id TEXT NOT NULL,
  external_id        TEXT NOT NULL,
  source_version     TEXT NOT NULL,
  body               JSONB NOT NULL,
  observed_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (source_instance_id, external_id)
);

-- 소스당 1행: 투영이 필요한 소스. 같은 소스의 여러 페이지 커밋은 행 하나로 합쳐진다(큐 상한 = 소스 수).
CREATE TABLE projection_requests (
  source_instance_id TEXT PRIMARY KEY,
  requested_at       TIMESTAMPTZ NOT NULL
);
