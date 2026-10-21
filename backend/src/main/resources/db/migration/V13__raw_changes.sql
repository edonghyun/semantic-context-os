-- F10 변경 로그(ADR-0020 §5): 페이지 커밋·run 완료와 같은 트랜잭션에 append. 메타데이터 관측 이력이지 본문 보존이 아니다.
CREATE TABLE raw_changes (
  seq                BIGSERIAL PRIMARY KEY,
  source_instance_id TEXT NOT NULL,
  external_id        TEXT,
  kind               TEXT NOT NULL CHECK (kind IN ('upsert', 'provider_delete', 'snapshot_absent', 'collection_incomplete')),
  source_version     TEXT,
  metadata           JSONB,
  occurred_at        TIMESTAMPTZ NOT NULL
);
CREATE INDEX raw_changes_source_seq ON raw_changes (source_instance_id, seq);
-- 보존 하한: 유지보수가 지운 마지막 seq. 이 아래의 커서는 만료(ChangeCursorExpiredException).
CREATE TABLE raw_change_retention (
  id    SMALLINT PRIMARY KEY CHECK (id = 1),
  floor BIGINT NOT NULL DEFAULT 0
);
INSERT INTO raw_change_retention (id, floor) VALUES (1, 0);
