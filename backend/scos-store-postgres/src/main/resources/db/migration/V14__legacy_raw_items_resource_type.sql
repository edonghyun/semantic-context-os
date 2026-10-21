-- 이전 리허설(ADR-0020 §6, MigrationRehearsalTest)에서 드러난 결함: V1 형태(body JSONB만)의 raw_items 행은 V8이 더한 resource_type이
-- NULL이라 최신 어댑터가 읽지 못한다(RawItem은 resource_type을 요구). 그런 행은 'unknown'으로 표시해 읽히게 하고, 투영은 모르는
-- resource_type을 건너뛴다 — 원본은 재수집(스냅샷)으로 되살린다.
UPDATE raw_items SET resource_type = 'unknown' WHERE resource_type IS NULL AND state = 'active';
