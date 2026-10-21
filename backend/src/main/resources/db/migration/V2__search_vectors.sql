-- 엔진 모듈(engine): 벡터 색인의 PostgreSQL 어댑터 저장(ADR-0014). seq는 적재 순서 — 동점 규칙(TS rowid)의 대응.
-- 차원은 컬럼에 고정하지 않는다(임베딩 공간 전환은 S3 범위). HNSW/IVFFlat 색인은 차원 고정이 필요하므로 P2에서
-- 공간별 테이블 또는 차원 고정 컬럼으로 바꾼다(실험 experiments/pa/pg-lab).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE unit_vectors (
  seq         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  space_id    TEXT NOT NULL,
  unit_id     TEXT NOT NULL,
  document_id TEXT NOT NULL,
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  embedding   vector NOT NULL,
  UNIQUE (space_id, unit_id)
);

CREATE INDEX unit_vectors_space_seq ON unit_vectors (space_id, seq);
