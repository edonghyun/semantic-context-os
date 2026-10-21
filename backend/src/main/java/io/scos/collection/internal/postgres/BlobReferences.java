package io.scos.collection.internal.postgres;

import java.util.Set;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * blob GC 경합의 번역(ADR-0020 §4): 원본·rejection이 blob을 가리키는 순간 다른 트랜잭션의 GC가 그 blob을 지웠으면 blob FK
 * 제약(V11)이 23503으로 거부한다. 다음 시도가 blob을 다시 쓰므로 일시 장애다 — 이 두 제약일 때만 {@link
 * TransientDataAccessResourceException}으로 바꾼다. 다른 FK 위반은 그대로 전파된다(결함).
 */
final class BlobReferences {
  static final Set<String> BLOB_FOREIGN_KEYS =
      Set.of("raw_items_content_blob_fk", "raw_rejections_quarantined_blob_fk");

  private BlobReferences() {}

  static <T> T translating(Supplier<T> write) {
    try {
      return write.get();
    } catch (DataIntegrityViolationException e) {
      if (violatesBlobForeignKey(e))
        throw new TransientDataAccessResourceException(
            "blob referenced while being garbage-collected — retry rewrites it", e);
      throw e;
    }
  }

  static boolean violatesBlobForeignKey(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof PSQLException p && "23503".equals(p.getSQLState())) {
        ServerErrorMessage detail = p.getServerErrorMessage();
        return detail != null && BLOB_FOREIGN_KEYS.contains(detail.getConstraint());
      }
    }
    return false;
  }
}
