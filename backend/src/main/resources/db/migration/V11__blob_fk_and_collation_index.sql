-- 리뷰 B-I-9·B-M-4(결정 1·3): blob 참조는 FK로 강제(참조 중 blob은 GC가 지울 수 없다), listActive keyset 페이지네이션용 COLLATE "C" 인덱스.
-- 이 마이그레이션 이전 코드(I-9 결함)가 남긴 고아 blob 참조가 있을 수 있으므로 먼저 정리한 뒤 NOT VALID로 걸고 VALIDATE한다(ADR-0018 §11).
-- 고아 참조 정리: blob이 없는 격리 참조는 "격리 내용 없음"으로, 원본 콘텐츠 참조는 콘텐츠 없음으로 둔다(항목·거부 기록 자체는 보존)
UPDATE raw_rejections r SET quarantined_storage = NULL, quarantined_media_type = NULL, quarantined_sha256 = NULL, quarantined_size = NULL
  WHERE quarantined_sha256 IS NOT NULL AND NOT EXISTS (SELECT 1 FROM raw_blobs b WHERE b.sha256 = r.quarantined_sha256);
UPDATE raw_items i SET content_storage = NULL, content_media_type = NULL, content_sha256 = NULL, content_size = NULL
  WHERE content_sha256 IS NOT NULL AND NOT EXISTS (SELECT 1 FROM raw_blobs b WHERE b.sha256 = i.content_sha256);

ALTER TABLE raw_items
  ADD CONSTRAINT raw_items_content_blob_fk FOREIGN KEY (content_sha256) REFERENCES raw_blobs (sha256) NOT VALID;
ALTER TABLE raw_rejections
  ADD CONSTRAINT raw_rejections_quarantined_blob_fk FOREIGN KEY (quarantined_sha256) REFERENCES raw_blobs (sha256) NOT VALID;
ALTER TABLE raw_items VALIDATE CONSTRAINT raw_items_content_blob_fk;
ALTER TABLE raw_rejections VALIDATE CONSTRAINT raw_rejections_quarantined_blob_fk;

CREATE INDEX raw_items_source_state_external_c ON raw_items (source_instance_id, state, external_id COLLATE "C");
-- 참조 쪽 부분 인덱스: GC(F9)의 "참조 중" 검사
CREATE INDEX raw_items_content_sha256 ON raw_items (content_sha256) WHERE content_sha256 IS NOT NULL;
CREATE INDEX raw_rejections_quarantined_sha256 ON raw_rejections (quarantined_sha256) WHERE quarantined_sha256 IS NOT NULL;
