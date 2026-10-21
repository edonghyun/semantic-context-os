-- 엔진 모듈(engine): 유닛 저장(UnitStore). seq = 적재 순서(TS rowid 대응, 동점·페이지·링크 순서의 기준).
-- 임베딩은 여기 없다(unit_vectors, VectorIndex). 키워드 색인 본문도 여기 없다(unit_texts, KeywordIndex).
CREATE TABLE units (
  seq            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  space_id       TEXT NOT NULL,
  id             TEXT NOT NULL,
  document_id    TEXT NOT NULL,
  partition      TEXT NOT NULL,
  title          TEXT NOT NULL,
  content        TEXT NOT NULL,
  content_hash   TEXT NOT NULL,
  tokens         INTEGER NOT NULL,
  tier           TEXT NOT NULL CHECK (tier IN ('raw', 'compacted', 'archived')),
  source_id      TEXT NOT NULL,
  uri            TEXT NOT NULL,
  captured_at    TEXT NOT NULL,
  importance     INTEGER NOT NULL,
  valid_from     TEXT,
  invalidated_at TEXT,
  links          TEXT[] NOT NULL DEFAULT '{}',
  tags           TEXT[] NOT NULL DEFAULT '{}',
  aliases        TEXT[] NOT NULL DEFAULT '{}',
  compacted_from TEXT[] NOT NULL DEFAULT '{}',
  UNIQUE (space_id, id)
);

CREATE INDEX units_space_document_seq ON units (space_id, document_id, seq);
CREATE INDEX units_space_links ON units USING gin (links) WHERE cardinality(links) > 0;
