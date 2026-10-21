-- F9 임베딩 캐시(ADR-0026): (임베딩 세대, sha256(임베딩 입력)) → 벡터. TS 엔진의 벡터를 가져와 재수집 투영이 같은 입력이면 Gemini 대신 쓴다.
-- 세대 문자열은 CONTRACTS G9(`GeminiEmbedder:<dim>:title+aliases+content`) — 차원·입력 형식이 다르면 다른 키다. 차원은 열에 고정하지 않는다.
CREATE TABLE embedding_cache (
  generation   TEXT NOT NULL,
  input_sha256 TEXT NOT NULL,
  embedding    vector NOT NULL,
  PRIMARY KEY (generation, input_sha256)
);
