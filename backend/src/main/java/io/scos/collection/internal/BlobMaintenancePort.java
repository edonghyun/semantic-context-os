package io.scos.collection.internal;

import java.time.Instant;

/**
 * F9 고아 blob 정리 포트: 참조(raw_items.content_sha256·raw_rejections.quarantined_sha256) 밖이고 cutoff보다 오래된
 * blob.
 */
public interface BlobMaintenancePort {
  /** 한 번의 집계: 전체·참조로 보존·유예로 보존·지울 수 있는 수와 그 바이트. */
  record BlobStats(
      long scanned, long retainedByReference, long retainedByGrace, long deletable, long bytes) {}

  BlobStats stats(Instant cutoff);

  /** 참조 밖이고 stored_at < cutoff인 blob 삭제(SQL 한 문장 — FK가 참조 중 삭제를 막는다). 지운 수. */
  int deleteUnreferencedBefore(Instant cutoff);
}
