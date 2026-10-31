-- 무제한 범위의 SQL — 0.2 접근 필터 뒤에도 바뀌면 안 된다(설계 §4.3)
SELECT unit_id, document_id, 1 - (embedding <=> CAST(:q AS vector)) AS score
FROM unit_vectors
WHERE space_id = :space AND vector_dims(embedding) = :dim AND (:archived OR NOT archived)
  AND (:anyPartition OR partition = ANY(:partitions))
ORDER BY embedding <=> CAST(:q AS vector), seq
LIMIT :k
