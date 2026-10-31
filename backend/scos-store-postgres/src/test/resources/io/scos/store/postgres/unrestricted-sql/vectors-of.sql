-- 무제한 범위의 SQL — 0.2 접근 필터 뒤에도 바뀌면 안 된다(설계 §4.3)
SELECT unit_id, embedding::text AS v FROM unit_vectors WHERE space_id = :space AND unit_id = ANY(:ids) ORDER BY seq