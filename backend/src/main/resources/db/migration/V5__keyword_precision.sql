-- 리뷰 I-5: 키워드 색인 정밀도 — bigram은 후보 가속기일 뿐, 후보는 질의 토큰을 실제로 포함해야 한다(원문 또는 공백 제거 본문).
-- ctext = 공백(JS \s 의미, 애플리케이션이 계산) 제거 본문: 3글자 이상 토큰의 붙여쓰기 매치용.
ALTER TABLE unit_texts ADD COLUMN ctext TEXT NOT NULL DEFAULT '';
-- 리뷰 M-10: linkedUnits는 `= ANY(subquery)`라 GIN(links)을 쓰지 않는다 — 쓰기 증폭만 남아 제거.
DROP INDEX IF EXISTS units_space_links;
