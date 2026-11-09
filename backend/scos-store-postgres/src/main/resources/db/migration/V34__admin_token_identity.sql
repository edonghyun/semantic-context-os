-- 토큰 해시를 공개하지 않고 메타데이터를 지정한다. volatile default는 기존 표를 재작성한다.
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '1s';

ALTER TABLE user_tokens
  ADD COLUMN token_id UUID NOT NULL DEFAULT gen_random_uuid(),
  ADD CONSTRAINT user_tokens_token_id_unique UNIQUE (token_id);

CREATE INDEX audit_events_keyset ON audit_events (at DESC, id DESC);
