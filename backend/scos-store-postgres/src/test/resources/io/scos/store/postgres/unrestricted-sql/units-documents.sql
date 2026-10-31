-- 무제한 범위의 SQL — 0.2 접근 필터 뒤에도 바뀌면 안 된다(설계 §4.3)
SELECT s.document_id, f.title, f.uri, f.source_id, s.unit_count, s.latest
FROM (SELECT document_id, count(*) AS unit_count, max(NULLIF(captured_at, '') COLLATE "C") AS latest
      FROM units WHERE space_id = :space AND document_id = ANY(:docs) GROUP BY document_id) s
JOIN LATERAL (SELECT title, uri, source_id FROM units u WHERE u.space_id = :space AND u.document_id = s.document_id ORDER BY seq LIMIT 1) f ON TRUE
