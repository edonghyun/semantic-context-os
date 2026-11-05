-- 배포 창에는 쓰기 작업자를 정지한다. 획득 대기와 생성 문장 모두 1초 안에 끝나지 않으면 롤백한다.
-- CONCURRENTLY의 비트랜잭션 실행·Flyway 잠금 정책 변경은 피하고 현재 원자적 마이그레이션 계약을 유지한다.
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '1s';
CREATE INDEX document_spaces_source_space
  ON document_spaces (source_id, space_id) INCLUDE (document_id);
