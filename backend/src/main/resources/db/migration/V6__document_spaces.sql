-- CONTRACTS A2(리뷰 I-8): 문서 소유 공간은 첫 기록자 기준으로 고정된다. UnitStore.upsert가 배치의 모든 문서를 먼저 확인하고 불일치면 거부한다.
CREATE TABLE document_spaces (
  document_id TEXT PRIMARY KEY,
  space_id    TEXT NOT NULL
);
