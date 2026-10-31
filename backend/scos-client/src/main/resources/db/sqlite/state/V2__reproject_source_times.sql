-- 기존 설치의 문서 시각을 바로잡는다: 메모리 원본에는 파일 수정 시각(source_updated_at)이 없었고 트랜스크립트 문서에는 captured_at이 빈 것이 있었다
-- 메모리: 파일 식별자는 삭제 추적에 남기고 지문만 무효화해 모든 현존 파일의 수정 시각을 다시 수집한다
UPDATE checkpoints
SET value_json = json_set(value_json, '$.files', json((
  SELECT json_group_object(key, json_set(value, '$.sha', ''))
  FROM json_each(checkpoints.value_json, '$.files')
)))
WHERE source = 'claude-memory';

-- 트랜스크립트: 저장된 문서를 투영 대기로 올려 다음 run의 투영이 다시 하게 한다
INSERT OR IGNORE INTO pending_projection (source, document_id)
SELECT DISTINCT source, document_id FROM raw_items WHERE source = 'claude-transcript';
