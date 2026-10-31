-- 사용자 토큰(0.2-d). 원문은 저장하지 않는다.
CREATE TABLE user_tokens (
  token_hash   TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users (id),
  label        TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ
);
CREATE INDEX user_tokens_user ON user_tokens (user_id);
