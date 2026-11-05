SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '1s';

-- 원천 관측 시각과 만료 처리 완료를 분리한다. 기존 행은 다음 sweep에서 한 번 처리한다.
ALTER TABLE raw_items
    ADD COLUMN access_expired_at TIMESTAMPTZ,
    ADD COLUMN access_expiry_space TEXT,
    ADD COLUMN access_expiry_document TEXT;
