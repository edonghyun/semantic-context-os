-- 리뷰 M-1: VectorIndex도 KeywordIndex와 같은 SearchFilter(archived·partition)를 받는다 — partition을 벡터 색인 행에 둔다.
ALTER TABLE unit_vectors ADD COLUMN partition TEXT NOT NULL DEFAULT '/knowledge';
