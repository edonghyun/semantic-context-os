package io.scos.collection.internal.projection;

import io.micrometer.core.instrument.MeterRegistry;
import io.scos.collection.internal.CollectionMetrics;
import io.scos.collection.internal.LeaseHeartbeats;
import io.scos.collection.internal.OutboxPort;
import io.scos.collection.internal.OwnerId;
import io.scos.collection.internal.Progress;
import io.scos.collection.internal.ProjectionRetryPolicy;
import io.scos.collection.internal.RawItems;
import io.scos.collection.internal.SourceInstances;
import io.scos.collection.internal.memory.MemoryOutboxPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.slack.SlackProjector;
import io.scos.engine.internal.projection.DocumentIngestor;
import io.scos.engine.internal.projection.JtokkitTokenizer;
import io.scos.engine.internal.projection.ingest.IngestPipeline;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.projection.Projector;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemorySpaceMeta;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.ManualTaskScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * 메모리 포트 위의 투영 조립(단위 테스트용, DB 없음): Slack 소스 하나에 메시지 원본 한 건을 넣고 투영 요청을 등록한다. 임베더·지표 레지스트리는 호출자가 준다 —
 * 투영이 공급자 대기에서 멈추는 경우(종료 순서)와 지표 배선을 DB 없이 본다.
 */
public final class MemoryProjection {
  /** 투영은 자격 증명을 풀지 않는다 — 등록 형식만 맞춘 env: ref. */
  public static final String CREDENTIAL_REF = "env:PROJECTION_TEST_TOKEN";

  public static final Instant T0 = Instant.parse("2026-10-14T00:00:00Z");
  public static final SpaceId SPACE = new SpaceId("legacy");
  public static final Duration LEASE_TTL = Duration.ofSeconds(30);
  static final ObjectMapper JSON = new ObjectMapper();

  public final MutableClock clock;
  public final MemorySourceInstanceStore sources;
  public final MemoryOutboxPort outbox = new MemoryOutboxPort();
  public final MemoryRawItemStore rawItems = new MemoryRawItemStore();
  public final MemoryWorkClaimPort claims;
  public final MemoryUnitStore units = new MemoryUnitStore();
  public final ManualTaskScheduler scheduler = new ManualTaskScheduler();
  public final Progress progress;
  private final List<Projector> projectors = new ArrayList<>(List.of(new SlackProjector(JSON)));

  public MemoryProjection(
      MutableClock clock, MemorySourceInstanceStore sources, MemoryWorkClaimPort claims) {
    this.clock = clock;
    this.sources = sources;
    this.claims = claims;
    this.progress = new Progress(clock);
  }

  public MemoryProjection() {
    this(new MutableClock(T0), new MemorySourceInstanceStore(), new MemoryWorkClaimPort());
  }

  /** Slack 투영기 말고도 이 kind의 투영기를 등록한다(커넥터 투영 테스트). */
  public MemoryProjection alsoProjecting(Projector projector) {
    projectors.add(projector);
    return this;
  }

  /** 공개 채널 Slack 소스 + 메시지 한 건(본문 text) + 투영 요청. */
  public SourceInstanceId slackSourceWithOneMessage(String id, String text) {
    SourceInstanceId source = new SourceInstanceId(id);
    sources.save(
        SourceInstances.of(
            source,
            SlackProjector.KIND,
            "{\"conversationTypes\":[\"public_channel\"]}",
            CREDENTIAL_REF,
            T0));
    rawItems.apply(
        source,
        RawItems.item("C1:1760400000.000100")
            .version("1760400000.000100")
            .metadata("{\"channelId\":\"C1\",\"messageTs\":\"1760400000.000100\"}")
            .inlineJson("{\"text\":\"" + text + "\"}")
            .upsert());
    outbox.requestProjection(source, clock.instant());
    return source;
  }

  /** 기본 공간({@link #SPACE})에 쓴 유닛(적재 순서). */
  public List<ContextUnit> storedUnits() {
    return units.listAll(SPACE);
  }

  /** 지금 시각에 시도할(due) 투영 요청의 소스. */
  public List<SourceInstanceId> dueNow() {
    return outbox.due(clock.instant());
  }

  /** 남아 있는 투영 요청의 소스(parked 포함). */
  public List<SourceInstanceId> pendingRequests() {
    return outbox.pending();
  }

  /** 소스의 투영 요청 행. */
  public Optional<OutboxPort.Request> request(SourceInstanceId source) {
    return outbox.request(source);
  }

  /** 이 소스의 투영 점유가 지금 시각에 살아 있는가(점유 행이 없으면 given 준비 실패). */
  public boolean holdsActiveProjectionClaim(SourceInstanceId source) {
    return claims
        .current(ProjectionRun.projectionKey(source))
        .orElseThrow(() -> new IllegalStateException("given 준비 실패: 투영 점유 행이 없다 — 투영이 점유하지 않았다"))
        .isActiveAt(clock.instant());
  }

  /** 마지막 작업 진행 신호 시각. */
  public Optional<Instant> lastProgressAt() {
    return progress.lastAt();
  }

  public ProjectionRun projection(Embedder embedder, MeterRegistry registry) {
    DocumentIngestor ingestor =
        new DocumentIngestor(
            TransactionOperations.withoutTransaction(),
            units,
            new MemoryKeywordIndex(),
            new MemoryVectorIndex(),
            new MemorySpaceMeta(),
            embedder,
            new IngestPipeline(new JtokkitTokenizer()),
            clock);
    return new ProjectionRun(
        outbox,
        sources,
        rawItems,
        Projectors.of(projectors),
        ingestor,
        new ProjectionSpaces(Map.of(), SPACE, JSON),
        ProjectionRetryPolicy.defaults(),
        claims,
        new OwnerId("projection-test"),
        new LeaseHeartbeats(claims, LEASE_TTL, clock, scheduler, CollectionMetrics.of(registry)),
        clock,
        ProjectionMetrics.of(registry),
        progress);
  }
}
