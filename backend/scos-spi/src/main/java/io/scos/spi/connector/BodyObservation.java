package io.scos.spi.connector;

import java.util.Optional;
import java.util.Set;

/** 한 커밋에서 확인한 페이지 본문. COMPLETE는 안정된 열거, DELETED는 같은 버전·순서의 명시적 삭제 증거다. */
public record BodyObservation(
    String fingerprint,
    String pageId,
    String version,
    SourceOrder order,
    Set<String> blocks,
    Outcome outcome,
    Optional<ReconciliationContext> reconciliation) {
  public BodyObservation(
      String fingerprint,
      String pageId,
      String version,
      SourceOrder order,
      Set<String> blocks,
      Outcome outcome) {
    this(fingerprint, pageId, version, order, blocks, outcome, Optional.empty());
  }

  public enum Outcome {
    CONFIRMED,
    ENUMERATING,
    COMPLETE,
    DELETED
  }

  public BodyObservation {
    blocks = Set.copyOf(blocks);
  }
}
