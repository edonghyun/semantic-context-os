-- 접근 코어(0.2-a). V19–V22 운영 배포 뒤에만 적용한다.
CREATE TABLE document_acl (
  space_id    TEXT NOT NULL,
  document_id TEXT NOT NULL,
  status      TEXT NOT NULL CHECK (status IN ('ok','unknown','failed','unsupported')),
  keys        TEXT[] NOT NULL DEFAULT '{}',
  fingerprint TEXT NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (space_id, document_id)
);
CREATE INDEX document_acl_keys ON document_acl USING gin (keys);

CREATE TABLE users (
  id         TEXT PRIMARY KEY,
  email      TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
  active     BOOLEAN NOT NULL,
  role       TEXT NOT NULL CHECK (role IN ('admin','member')),
  created_at TIMESTAMPTZ NOT NULL
);
-- 원천이 진실(설계 §3.3). 쓰는 쪽은 0.2-b 접근 단계 확정. 0.2-a는 guest·deleted로 키 계산에서 뺄 신원을 읽는다
CREATE TABLE source_identities (
  source_instance_id TEXT NOT NULL,
  source_user_id     TEXT NOT NULL,
  email              TEXT,
  email_verified     BOOLEAN NOT NULL,
  guest              BOOLEAN NOT NULL,
  deleted            BOOLEAN NOT NULL,
  observed_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (source_instance_id, source_user_id)
);
CREATE TABLE user_source_identities (
  source_instance_id TEXT NOT NULL,
  source_user_id     TEXT NOT NULL,
  user_id            TEXT NOT NULL REFERENCES users (id),
  method             TEXT NOT NULL CHECK (method IN ('auto','admin')),
  linked_at          TIMESTAMPTZ NOT NULL,
  linked_by          TEXT NOT NULL,
  PRIMARY KEY (source_instance_id, source_user_id)
);
CREATE INDEX user_source_identities_user ON user_source_identities (user_id);
CREATE TABLE source_groups (
  source_instance_id TEXT NOT NULL,
  group_id           TEXT NOT NULL,
  members            TEXT[] NOT NULL DEFAULT '{}',   -- 원천 신원 id, 또는 같은 인스턴스의 '~' 합성 그룹 id(한 단계)
  fingerprint        TEXT NOT NULL,
  observed_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (source_instance_id, group_id)
);
CREATE INDEX source_groups_members ON source_groups USING gin (members);
CREATE TABLE org_wide_scopes (
  source_instance_id TEXT PRIMARY KEY,               -- 소스 인스턴스 단위 지정(설계 C8·A7) → 키 scope:<inst>:*
  designated_by      TEXT NOT NULL,
  designated_at      TIMESTAMPTZ NOT NULL
);
CREATE TABLE audit_events (                          -- 스키마만. 쓰는 쪽은 0.2-c
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at          TIMESTAMPTZ NOT NULL,
  actor       TEXT NOT NULL,
  action      TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id   TEXT NOT NULL,
  detail      JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_events_at ON audit_events (at);

-- 문서 소유 출처는 첫 유닛의 출처다(다른 출처 거부는 0.2-b).
ALTER TABLE document_spaces ADD COLUMN source_id TEXT;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM units GROUP BY document_id HAVING count(DISTINCT source_id) > 1) THEN
    RAISE EXCEPTION 'document_spaces.source_id: 출처가 둘 이상인 문서가 있다 — 설계 0.2 §3.2 A3 정리 후 다시 적용';
  END IF;
END $$;
UPDATE document_spaces d SET source_id = u.source_id
  FROM (SELECT DISTINCT document_id, source_id FROM units) u
  WHERE u.document_id = d.document_id;
DELETE FROM document_spaces WHERE source_id IS NULL;
ALTER TABLE document_spaces ALTER COLUMN source_id SET NOT NULL;
