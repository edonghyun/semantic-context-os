package io.scos.collection.internal;

import io.scos.spi.connector.CollectionCoverage;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;

/** 수집 run 기록(TS SyncRun). 카운터는 페이지 커밋이 올리고, 완료가 상태·오류를 쓴다. */
public record CollectionRun(
    RunId id,
    SourceInstanceId source,
    RunStatus status,
    Instant startedAt,
    Optional<Instant> finishedAt,
    int pagesApplied,
    int upserted,
    int deleted,
    int unchanged,
    int rejected,
    int errorCount,
    Optional<String> errorKind,
    Optional<String> errorSummary,
    Optional<CollectionCoverage> coverage) {}
