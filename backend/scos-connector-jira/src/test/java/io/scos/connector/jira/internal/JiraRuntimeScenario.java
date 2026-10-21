package io.scos.connector.jira.internal;

import io.scos.collection.internal.CollectionRuntime;
import io.scos.collection.internal.MemoryCollectionRuntime;
import io.scos.collection.internal.RunStatus;
import io.scos.collection.internal.memory.MemoryCheckpointPort;
import io.scos.collection.internal.memory.MemoryRawItemStore;
import io.scos.collection.internal.memory.MemoryRunStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.collection.internal.memory.MemoryWorkClaimPort;
import io.scos.connector.jira.JiraAssembly;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.RawItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Jira 커넥터 위의 실제 수집 런타임(TESTING §3.6 5층, 메모리 포트): 점유·run 기록·페이지 커밋·완료가 제품 코드다. 가짜 Jira는 TS 데이터셋이고,
 * 페이지 중간 중단은 TS 생성기와 같게 그 요청 하나를 401(비재시도)로 끝낸다.
 */
final class JiraRuntimeScenario implements AutoCloseable {

  private final FakeJiraServer jira;
  private final MutableClock clock = new MutableClock(JiraScenario.NOW);
  private final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  private final MemoryRawItemStore rawItems = new MemoryRawItemStore();
  private final MemoryCheckpointPort checkpoints = new MemoryCheckpointPort();
  private final CollectionRuntime runtime;

  JiraRuntimeScenario() {
    try {
      jira = new FakeJiraServer(JiraFixtures.dataset());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    sources.save(JiraAssembly.instance(JiraAssembly.SYNTHETIC_SITE, JiraScenario.NOW));
    runtime =
        MemoryCollectionRuntime.over(
                clock, sources, new MemoryWorkClaimPort(), new MemoryRunStore())
            .rawItems(rawItems)
            .checkpoints(checkpoints)
            .adapter(JiraAssembly.adapter(jira.baseUrl(), jira.token(), clock))
            .build();
  }

  /** 다음 수집이 중단 지점의 요청에서 프로세스가 죽은 것처럼 끝난다(그 배치는 커밋되지 않는다). */
  void givenCrashAtIssue40005() {
    jira.failNext(JiraTsRuns.crashPath(), 1, 401);
  }

  /** 재개의 앞 단계: 40005 요청에서 끊긴 run 하나를 남긴다(그 run이 성공하면 given 준비 실패). */
  void givenCrashedRun() {
    givenCrashAtIssue40005();
    CollectionRuntime.Report crashed = collect();
    if (crashed.status() == RunStatus.SUCCEEDED)
      throw new IllegalStateException("given 준비 실패: 40005에서 끊긴 run이어야 한다 — " + crashed);
  }

  /** TS 장애 사례(expected-failures.json의 failure): 그 경로 접두의 요청은 늘 그 상태·헤더로 답한다. */
  void givenFailure(JsonNode failure) {
    jira.failAlways(failure);
  }

  /** 이 경로 접두로 보낸 요청 수(재시도 포함). */
  long requestsTo(String pathPrefix) {
    return jira.requests().stream().filter(r -> r.path().startsWith(pathPrefix)).count();
  }

  /** 저장된 체크포인트(없으면 JSON null) — TS getCheckpoint의 checkpoint 필드와 같은 모양. */
  JsonNode storedCheckpointOrNull() {
    return checkpoints
        .read(JiraAssembly.SOURCE)
        .checkpoint()
        .map(JiraTsRuns::checkpointJson)
        .orElse(JiraFixtures.JSON.nullNode());
  }

  CollectionRuntime.Report collect() {
    return runtime.collect(
        JiraAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
  }

  long checkpointRevision() {
    return checkpoints.read(JiraAssembly.SOURCE).revision();
  }

  /** active 원본의 외부 id → sourceVersion. */
  Map<String, String> activeVersions() {
    Map<String, String> out = new LinkedHashMap<>();
    Optional<String> cursor = Optional.empty();
    do {
      var page = rawItems.listActive(JiraAssembly.SOURCE, 100, cursor);
      for (RawItem item : page.items()) out.put(item.externalId(), item.sourceVersion());
      cursor = page.nextCursor();
    } while (cursor.isPresent());
    return out;
  }

  /** TS 보고와 같은 필드(상태·페이지·변경·거부·오류 수). */
  record ReportView(
      String status,
      int pagesApplied,
      int upserted,
      int deleted,
      int unchanged,
      int rejected,
      int errorCount) {
    static ReportView of(CollectionRuntime.Report r) {
      return new ReportView(
          r.status().wire(),
          r.pagesApplied(),
          r.upserted(),
          r.deleted(),
          r.unchanged(),
          r.rejected(),
          r.errorCount());
    }

    static ReportView expected(JsonNode r) {
      return new ReportView(
          r.get("status").asString(),
          r.get("pagesApplied").asInt(),
          r.get("upserted").asInt(),
          r.get("deleted").asInt(),
          r.get("unchanged").asInt(),
          r.get("rejected").asInt(),
          r.get("errorCount").asInt());
    }
  }

  /** TS 원본 목록의 외부 id → sourceVersion. */
  static Map<String, String> versionsOf(JsonNode items) {
    Map<String, String> out = new LinkedHashMap<>();
    items.forEach(
        i -> out.put(i.get("key").get("externalId").asString(), i.get("sourceVersion").asString()));
    return out;
  }

  @Override
  public void close() {
    jira.close();
  }
}
