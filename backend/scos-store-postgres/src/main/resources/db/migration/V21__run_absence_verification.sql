-- 검증 근거는 현재 run에만 귀속한다. 이전 run 관측/완료 세션을 인계하지 않는다.
ALTER TABLE collection_runs ADD COLUMN reconciliation_fingerprint TEXT;
ALTER TABLE collection_runs ADD COLUMN absence_candidate_token TEXT;
ALTER TABLE collection_runs ADD COLUMN absence_exclusions TEXT[];
