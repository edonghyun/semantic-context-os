-- 원본 ACL은 본문과 독립적으로 관측한다. tombstone의 ACL 삭제는 수집 커밋이 명시한다.
CREATE TABLE raw_acl (
  source_instance_id TEXT NOT NULL,
  external_id        TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN ('ok', 'unknown', 'failed', 'unsupported')),
  keys               TEXT[] NOT NULL,
  fingerprint        TEXT NOT NULL,
  observed_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (source_instance_id, external_id)
);

CREATE TABLE access_observations (
  run_id      TEXT NOT NULL REFERENCES collection_runs (run_id) ON DELETE CASCADE,
  kind        TEXT NOT NULL CHECK (kind IN ('group', 'identity')),
  key         TEXT NOT NULL,
  payload     JSONB NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  conflicted  BOOLEAN NOT NULL DEFAULT false,
  PRIMARY KEY (run_id, kind, key)
);

ALTER TABLE collection_runs
  ADD COLUMN access_result TEXT NOT NULL DEFAULT 'skipped'
    CHECK (access_result IN ('complete', 'incomplete', 'claim_lost', 'skipped', 'unsupported')),
  ADD COLUMN access_finished_at TIMESTAMPTZ;
