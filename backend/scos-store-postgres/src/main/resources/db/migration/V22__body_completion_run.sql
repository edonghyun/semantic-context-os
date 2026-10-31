-- 이전 run의 완료를 이번 전체 부재 검증의 열거 증거로 사용하지 않는다.
ALTER TABLE body_completions ADD COLUMN completed_run_id TEXT;
