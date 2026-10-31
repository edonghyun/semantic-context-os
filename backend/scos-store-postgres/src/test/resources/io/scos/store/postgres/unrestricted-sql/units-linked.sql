-- 무제한 범위의 SQL — 0.2 접근 필터 뒤에도 바뀌면 안 된다(설계 §4.3)
SELECT t.id, t.document_id FROM units t
WHERE t.space_id = :space
  AND t.document_id = ANY(SELECT unnest(o.links) FROM units o WHERE o.space_id = :space AND o.id = ANY(:ids))
  AND NOT (t.id = ANY(:ids))
ORDER BY t.seq
