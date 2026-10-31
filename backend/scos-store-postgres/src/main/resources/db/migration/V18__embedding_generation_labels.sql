-- 임베딩 세대 라벨 일회성 이전(stage2 §3.2, D8·Q3 (b)): 옛 Gemini 라벨 한 쌍만 새 형식 <provider>:<model>:<dim>:<input-format>로
-- 고쳐 쓴다. 벡터는 같은 모델(gemini-embedding-001, 768차원)로 만든 것이라 그대로 둔다(재임베딩 0). 엔진에는 별칭표가 없다.
UPDATE space_meta SET value = 'gemini:gemini-embedding-001:768:title+aliases+content'
 WHERE key = 'embedding:version' AND value = 'GeminiEmbedder:768:title+aliases+content';
UPDATE embedding_cache SET generation = 'gemini:gemini-embedding-001:768:title+aliases+content'
 WHERE generation = 'GeminiEmbedder:768:title+aliases+content';
-- 마이그레이션이 모르는 값이 남아 있으면 실패한다(Flyway 실패 → 기동 거부). 새 형식: <provider>:<model>:<dim>:<input-format>
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM space_meta WHERE key = 'embedding:version'
               AND value !~ '^[a-z0-9][a-z0-9-]*:.+:[1-9][0-9]*:[a-z+]+$')
     OR EXISTS (SELECT 1 FROM embedding_cache
               WHERE generation !~ '^[a-z0-9][a-z0-9-]*:.+:[1-9][0-9]*:[a-z+]+$') THEN
    RAISE EXCEPTION 'unknown embedding generation label; expected <provider>:<model>:<dim>:<input-format>';
  END IF;
END $$;
