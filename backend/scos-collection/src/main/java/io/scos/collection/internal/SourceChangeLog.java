package io.scos.collection.internal;

import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * F10 변경 로그 포트(CONTRACTS F10, ADR-0020 §5): 원본
 * 변경(upsert·provider_delete·snapshot_absent·collection_incomplete)을 커밋과 같은 트랜잭션에 append하고, 커서로 읽는다.
 * 메타데이터 관측 이력이지 본문 보존 약속이 아니다. 읽기 커서 규칙은 {@link ChangeCursor}.
 */
public interface SourceChangeLog {
  enum Kind {
    UPSERT,
    PROVIDER_DELETE,
    SNAPSHOT_ABSENT,
    COLLECTION_INCOMPLETE;

    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind fromWire(String wire) {
      return valueOf(wire.toUpperCase(Locale.ROOT));
    }
  }

  record Change(
      long seq,
      SourceInstanceId source,
      Optional<String> externalId,
      Kind kind,
      Optional<String> sourceVersion,
      Optional<String> metadataJson,
      Instant occurredAt) {}

  /** nextSeq = 마지막 항목의 seq(비어 있으면 head), headSeq = 읽기 전 head. */
  record Page(List<Change> changes, long nextSeq, long headSeq) {}

  /** 기록할 변경 하나(seq는 저장소가 붙인다). */
  record Entry(
      SourceInstanceId source,
      Optional<String> externalId,
      Kind kind,
      Optional<String> sourceVersion,
      Optional<String> metadataJson,
      Instant occurredAt) {}

  /**
   * 호출자 트랜잭션에 변경들을 순서대로 기록한다. 쓰기 잠금(ADR-0021)을 커밋까지 쥘 수 있으므로 호출자는 트랜잭션의 **마지막 작업으로 한 번** 부른다 — 잠금
   * 구간이 커밋 직전으로 줄어든다. 빈 목록은 아무것도 하지 않는다.
   */
  void appendAll(List<Entry> entries);

  /** {@link ChangeCursor#validate} 규칙으로 seq > after를 seq 순으로 limit개까지. */
  Page read(Optional<SourceInstanceId> source, long after, int limit);

  /** 마지막 seq(지워진 것 포함). */
  long head();

  /** occurredAt < cutoff인 마지막 변경까지(그 seq 이하 전부) 지우고 보존 하한을 그 seq로 올린다. 지운 수. */
  int pruneBefore(Instant cutoff);
}
