-- 본문 완료는 raw 페이지 버전과 별개다. fingerprint 밖의 소속 증거를 재사용하지 않는다.
CREATE TABLE body_completions (
  source_instance_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  page_id TEXT NOT NULL,
  version TEXT NOT NULL,
  order_scheme TEXT NOT NULL,
  order_position TEXT NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL,
  blocks TEXT[] NOT NULL,
  PRIMARY KEY (source_instance_id, fingerprint, page_id)
);

-- 관측 중인 버전은 완료 요약을 덮어쓰지 않는다. 새 run은 이전 run의 관측을 인계하지 않는다.
CREATE TABLE body_enumerations (
  source_instance_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  page_id TEXT NOT NULL,
  version TEXT NOT NULL,
  order_scheme TEXT NOT NULL,
  order_position TEXT NOT NULL,
  run_id TEXT NOT NULL,
  observed TEXT[] NOT NULL,
  PRIMARY KEY (source_instance_id, fingerprint, page_id)
);
