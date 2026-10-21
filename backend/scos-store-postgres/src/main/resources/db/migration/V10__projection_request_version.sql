-- 리뷰 B-C-1·B-I-7: 투영 요청 세대(version)와 parked. 완료·실패 기록은 처리 시작 시 읽은 version일 때만 적용된다.
ALTER TABLE projection_requests
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN parked  BOOLEAN NOT NULL DEFAULT FALSE;
