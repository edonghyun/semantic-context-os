-- 미완료 열거의 관측 소속은 완료 버전 manifest와 독립해서 보존한다.
CREATE TABLE body_memberships (
  source_instance_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  page_id TEXT NOT NULL,
  block_id TEXT NOT NULL,
  PRIMARY KEY (source_instance_id, fingerprint, page_id, block_id)
);
CREATE INDEX body_membership_owners ON body_memberships (source_instance_id, fingerprint, block_id);

INSERT INTO body_memberships (source_instance_id, fingerprint, page_id, block_id)
SELECT source_instance_id, fingerprint, page_id, unnest(blocks) FROM body_completions
ON CONFLICT DO NOTHING;

INSERT INTO body_memberships (source_instance_id, fingerprint, page_id, block_id)
SELECT source_instance_id, fingerprint, page_id, unnest(observed) FROM body_enumerations
ON CONFLICT DO NOTHING;
