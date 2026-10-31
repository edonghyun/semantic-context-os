-- 한 페이지 상태의 발견과 eligible 분모를 같은 수집 트랜잭션에 보관한다.
CREATE TABLE reconciliation_discoveries (
  source_instance_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  resource_id TEXT NOT NULL,
  state_key TEXT NOT NULL,
  kind TEXT NOT NULL,
  classification TEXT,
  eligible BOOLEAN NOT NULL,
  PRIMARY KEY (source_instance_id, run_id, resource_id, state_key)
);

-- 원본/블록을 바꾸기 전 비교 기준을 같은 run의 커밋 재시도에서 보존한다.
CREATE TABLE reconciliation_comparisons (
  source_instance_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  page_id TEXT NOT NULL,
  previous_state TEXT,
  previous_version TEXT,
  successful_exploration TEXT,
  bootstrap BOOLEAN NOT NULL,
  PRIMARY KEY (source_instance_id, run_id, fingerprint, page_id)
);
