-- 운영 관측값은 본문 결과와 독립적이며 옛 바이너리는 새 열을 무시한다.
ALTER TABLE collection_runs ADD COLUMN acl_unreadable TEXT[] NOT NULL DEFAULT '{}';
