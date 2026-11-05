package io.scos.collection.internal;

import io.scos.spi.connector.RawMutation;
import io.scos.spi.source.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 한 페이지의 mutation 적용 결과를 모은다: 결과별 개수, 관측한 externalId, 변경 로그 항목. */
final class PageTally {
  private final SourceInstanceId source;
  private final Instant at;
  private final List<String> observed;
  private final List<SourceChangeLog.Entry> changes = new ArrayList<>();
  private int upserted;
  private int deleted;
  private int unchanged;

  PageTally(SourceInstanceId source, Instant at, int mutations) {
    this.source = source;
    this.at = at;
    this.observed = new ArrayList<>(mutations);
  }

  void record(RawMutation mutation, RawItemStore.Outcome outcome) {
    switch (outcome) {
      case UPSERTED -> {
        upserted++;
        changes.add(changeOf(mutation));
      }
      case DELETED -> {
        deleted++;
        changes.add(changeOf(mutation));
      }
      case UNCHANGED -> unchanged++;
    }
    observed.add(mutation.externalId());
  }

  void recordAbsences(List<String> ids) {
    deleted += ids.size();
    ids.forEach(id -> changes.add(SourceChangeLog.Entry.snapshotAbsent(source, id, at)));
  }

  List<String> observed() {
    return observed;
  }

  List<SourceChangeLog.Entry> changes() {
    return changes;
  }

  /** 원본이 바뀐 페이지인가 — 바뀐 것이 없으면 투영을 요청하지 않는다. */
  boolean changedAny() {
    return upserted + deleted > 0;
  }

  PageCommitResult result(long revision, int rejected) {
    return new PageCommitResult(revision, upserted, deleted, unchanged, rejected);
  }

  private SourceChangeLog.Entry changeOf(RawMutation mutation) {
    return switch (mutation) {
      case RawMutation.Upsert u ->
          SourceChangeLog.Entry.upsert(source, u.externalId(), u.item().sourceVersion(), at);
      case RawMutation.Delete d ->
          SourceChangeLog.Entry.providerDelete(source, d.externalId(), d.sourceVersion(), at);
    };
  }
}
