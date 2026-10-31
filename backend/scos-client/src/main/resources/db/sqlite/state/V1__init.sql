-- 클라이언트 state.sqlite: 수집한 원본(진실의 원천)과 어댑터 체크포인트. index.sqlite는 여기서 다시 만든다
CREATE TABLE raw_items (
  source TEXT NOT NULL,
  external_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  resource_type TEXT NOT NULL,
  source_version TEXT NOT NULL,
  source_updated_at TEXT,
  observed_at TEXT NOT NULL,
  content BLOB,
  PRIMARY KEY (source, external_id)
);

CREATE INDEX raw_items_document ON raw_items (source, document_id, resource_type);

CREATE TABLE checkpoints (
  source TEXT PRIMARY KEY,
  schema_version INTEGER NOT NULL,
  value_json TEXT NOT NULL
);

-- 커밋됐지만 아직 색인에 투영되지 않은 문서(투영 성공 뒤에 지운다): 중단돼도 다음 run이 이어 투영한다
CREATE TABLE pending_projection (
  source TEXT NOT NULL,
  document_id TEXT NOT NULL,
  PRIMARY KEY (source, document_id)
);
