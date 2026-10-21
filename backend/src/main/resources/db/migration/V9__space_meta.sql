-- P4(ADR-0018 §6, CONTRACTS G9·G6): 공간별 메타(임베딩 세대 `embedding:version`, 소스 커서 `cursor:<sourceId>`). TS space_meta 대응.
CREATE TABLE space_meta (
  space_id TEXT NOT NULL,
  key      TEXT NOT NULL,
  value    TEXT NOT NULL,
  PRIMARY KEY (space_id, key)
);
