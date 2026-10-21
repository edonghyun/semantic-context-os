-- 재검토2 F-3: 요청 세대(version)와 무관한 연속 실패 수. attempts는 새 데이터마다 0으로 돌아가(새 시도 예산) 활발한 소스는 parked에
-- 이르지 않으므로, 결정적으로 실패하는 투영의 운영 신호는 이 값이다. 요청 행이 완료(삭제)될 때만 0으로 돌아간다.
ALTER TABLE projection_requests ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
