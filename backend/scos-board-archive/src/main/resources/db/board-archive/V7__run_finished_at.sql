ALTER TABLE crawl_run ADD COLUMN finished_at TEXT;
UPDATE crawl_run SET finished_at=started_at WHERE finished=1;
