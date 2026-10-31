-- 기존 행은 JSON으로 캡처한 원본이다. 새 원본은 캡처가 준 미디어 타입을 보존한다.
ALTER TABLE raw_items ADD COLUMN media_type TEXT NOT NULL DEFAULT 'application/json';
