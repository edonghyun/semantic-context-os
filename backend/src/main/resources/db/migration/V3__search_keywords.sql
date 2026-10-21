-- 엔진 모듈(engine): 키워드 색인의 첫 어댑터 = 직접 bigram 테이블(ADR-0014 P-4). 확장 불필요, 점수는 코드 소유.
-- unit_texts: 유닛 본문(소문자, title\ncontent)과 필터 열. seq는 적재 순서(동점 규칙).
-- unit_bigrams: 공백 제거 본문의 2-gram과 등장 횟수. (space_id, bigram, unit_seq) B-tree가 2글자 질의를 색인으로 푼다.
CREATE TABLE unit_texts (
  seq         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  space_id    TEXT NOT NULL,
  unit_id     TEXT NOT NULL,
  document_id TEXT NOT NULL,
  partition   TEXT NOT NULL DEFAULT '/',
  archived    BOOLEAN NOT NULL DEFAULT FALSE,
  text        TEXT NOT NULL,
  UNIQUE (space_id, unit_id)
);

CREATE TABLE unit_bigrams (
  space_id  TEXT NOT NULL,
  unit_seq  BIGINT NOT NULL REFERENCES unit_texts (seq) ON DELETE CASCADE,
  bigram    TEXT NOT NULL,
  cnt       INTEGER NOT NULL,
  PRIMARY KEY (space_id, bigram, unit_seq)
);

CREATE INDEX unit_bigrams_unit ON unit_bigrams (unit_seq);
