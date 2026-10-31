-- index.sqlite V1: 엔진 저장 포트 8개의 테이블을 한 번에 둔다(이후 버전은 테이블을 더하지 않는다).
-- 목록 열(links·tags·aliases·compacted_from·members·keys)은 JSON 배열 문자열이다.

-- UnitStore·UnitMaintenance: seq = 적재 순서(동점·페이지·링크 순서의 기준)
CREATE TABLE units (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,
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
  links          TEXT NOT NULL DEFAULT '[]',
  tags           TEXT NOT NULL DEFAULT '[]',
  aliases        TEXT NOT NULL DEFAULT '[]',
  compacted_from TEXT NOT NULL DEFAULT '[]',
  UNIQUE (space_id, id)
);
CREATE INDEX units_space_document_seq ON units (space_id, document_id, seq);

-- 문서 소유 공간·출처는 첫 기록자 기준으로 고정된다
CREATE TABLE document_spaces (
  document_id TEXT PRIMARY KEY,
  space_id    TEXT NOT NULL,
  source_id   TEXT NOT NULL
);

-- KeywordIndex: 본문 두 열과 필터 열은 일반 테이블에 둔다. text = 소문자 title\ncontent(공백 유지, 점수 계산용),
-- compact = text에서 공백을 뺀 것. 검색은 compact만 색인하는 trigram FTS5가 맡는다(외부 콘텐츠, rowid = seq)
CREATE TABLE units_text (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  space_id    TEXT NOT NULL,
  unit_id     TEXT NOT NULL,
  document_id TEXT NOT NULL,
  partition   TEXT NOT NULL DEFAULT '/',
  archived    INTEGER NOT NULL DEFAULT 0,
  text        TEXT NOT NULL,
  compact     TEXT NOT NULL,
  UNIQUE (space_id, unit_id)
);
CREATE INDEX units_text_space_document ON units_text (space_id, document_id);
CREATE VIRTUAL TABLE unit_text USING fts5(compact, content='units_text', content_rowid='seq', tokenize='trigram');

-- VectorIndex: embedding은 float64 BLOB(리틀 엔디언)
CREATE TABLE vectors (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  space_id    TEXT NOT NULL,
  unit_id     TEXT NOT NULL,
  document_id TEXT NOT NULL,
  partition   TEXT NOT NULL DEFAULT '',
  archived    INTEGER NOT NULL DEFAULT 0,
  embedding   BLOB NOT NULL,
  UNIQUE (space_id, unit_id)
);
CREATE INDEX vectors_space_document ON vectors (space_id, document_id);

CREATE TABLE space_meta (
  space_id TEXT NOT NULL,
  key      TEXT NOT NULL,
  value    TEXT NOT NULL,
  PRIMARY KEY (space_id, key)
);

CREATE TABLE embedding_cache (
  generation   TEXT NOT NULL,
  input_sha256 TEXT NOT NULL,
  embedding    BLOB NOT NULL,
  PRIMARY KEY (generation, input_sha256)
);

-- AclStore
CREATE TABLE document_acl (
  space_id    TEXT NOT NULL,
  document_id TEXT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('ok','unknown','failed','unsupported')),
  keys        TEXT NOT NULL DEFAULT '[]',
  fingerprint TEXT NOT NULL,
  observed_at TEXT NOT NULL,
  PRIMARY KEY (space_id, document_id)
);

-- IdentityStore
CREATE TABLE users (
  id         TEXT PRIMARY KEY,
  email      TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
  active     INTEGER NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('admin','member')),
  created_at TEXT NOT NULL
);
CREATE TABLE source_identities (
  source_instance_id TEXT NOT NULL,
  source_user_id     TEXT NOT NULL,
  email              TEXT,
  email_verified     INTEGER NOT NULL,
  guest              INTEGER NOT NULL,
  deleted            INTEGER NOT NULL,
  observed_at        TEXT NOT NULL,
  PRIMARY KEY (source_instance_id, source_user_id)
);
CREATE TABLE user_source_identities (
  source_instance_id TEXT NOT NULL,
  source_user_id     TEXT NOT NULL,
  user_id            TEXT NOT NULL REFERENCES users (id),
  method             TEXT NOT NULL CHECK (method IN ('auto','admin')),
  linked_at          TEXT NOT NULL,
  linked_by          TEXT NOT NULL,
  PRIMARY KEY (source_instance_id, source_user_id)
);
CREATE INDEX user_source_identities_user ON user_source_identities (user_id);
CREATE TABLE source_groups (
  source_instance_id TEXT NOT NULL,
  group_id           TEXT NOT NULL,
  members            TEXT NOT NULL DEFAULT '[]',
  fingerprint        TEXT NOT NULL,
  observed_at        TEXT NOT NULL,
  PRIMARY KEY (source_instance_id, group_id)
);
CREATE TABLE org_wide_scopes (
  source_instance_id TEXT PRIMARY KEY,
  designated_by      TEXT NOT NULL,
  designated_at      TEXT NOT NULL
);
