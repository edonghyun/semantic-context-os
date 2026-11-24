package io.scos.collection.internal.projection;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.boot.PostgresIndexConfiguration;
import io.scos.collection.internal.CollectionRun;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.connector.tiro.internal.FakeTiroServer;
import io.scos.engine.ContextEngine;
import io.scos.engine.ContextHit;
import io.scos.engine.SearchQuery;
import io.scos.engine.support.time.MutableClock;
import io.scos.spi.access.DomainPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.source.SourceInstanceId;
import io.scos.spi.store.Principal;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import io.scos.support.TestExecutors;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 가짜 Tiro(합성 데이터셋)부터 실제 수집·접근 단계·투영·제한 검색까지 잇는다. 폴더 10(전원 공개)에 n-alpha, 폴더 20(PRIVATE)에 n-beta가 있고
 * 나머지 노트는 폴더 밖이다. 노트 협업자는 모두 private@example.test(U1)다. U1의 관리자 연결만 테스트 시드다.
 */
@Tag("db")
final class TiroAccessScenario implements AutoCloseable {
  private static final SourceInstanceId SOURCE = TiroAssembly.SOURCE;
  private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");
  private static final String FOLDERS = "/v1/external/workspaces/ws-synthetic/folders";
  private static final DomainPolicy DOMAINS =
      new DomainPolicy(Set.of("example.test"), Map.of(SOURCE, "example.test"));
  private final MutableClock clock = new MutableClock(T0);
  private final JdbcClient jdbc = JdbcClient.create(PostgresSupport.dataSource());
  private final PostgresCollectionConfiguration.Adapters stores =
      PostgresCollectionConfiguration.adapters(PostgresSupport.dataSource());
  private final FakeTiroServer server = TiroAssembly.server();
  private final PostgresCollectionRuntime collection;
  private final PostgresProjection projection;
  private final ContextEngine engine;

  TiroAccessScenario() {
    PostgresSupport.truncateAll();
    server.folders(
        "[{\"id\":10,\"parentId\":null,\"sharingType\":\"ALL_MEMBER_VIEWER\"},"
            + "{\"id\":20,\"parentId\":null,\"sharingType\":\"PRIVATE\"}]");
    server.folderNotes("10", List.of("n-alpha"));
    server.folderNotes("20", List.of("n-beta"));
    var adapter = TiroAssembly.adapter(server.baseUrl(), server.token(), observationClock());
    collection =
        PostgresCollectionRuntime.over(PostgresSupport.dataSource(), clock)
            .adapter(adapter)
            .collectAccess(true)
            .domains(DOMAINS);
    projection =
        PostgresProjection.collectingAccess(
                PostgresSupport.dataSource(), clock, new ScriptedEmbedder(), adapter)
            .explicitlyMapped(SOURCE.value());
    stores.sources().save(TiroAssembly.instance(server.configJson(), T0));
    seedUsers();
    engine = restrictedEngine();
  }

  /** 본문과 접근 단계 ACL은 실제로 다른 관측이다. 고정 시계의 같은 시각 상충을 만들지 않는다(JiraAccessScenario와 같다). */
  private Clock observationClock() {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return clock.getZone();
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public synchronized Instant instant() {
        clock.advance(Duration.ofMillis(1));
        return clock.instant();
      }
    };
  }

  private ContextEngine restrictedEngine() {
    return ContextEngine.builder()
        .units(PostgresTestAdapters.unitStore(PostgresSupport.dataSource()))
        .keywords(PostgresTestAdapters.keywordIndex(PostgresSupport.dataSource()))
        .keywordOnly()
        .access(
            new PostgresIndexConfiguration().newIdentityStore(PostgresSupport.dataSource()),
            PostgresCollectionRuntime.sourceFreshness(PostgresSupport.dataSource()),
            DOMAINS)
        .clock(clock)
        .lexicalExecutor(TestExecutors.CALLER_THREAD)
        .build();
  }

  /** U1 = 노트 작성자(관리자 연결), U2 = 같은 조직 도메인의 다른 사용자(연결 없음). */
  private void seedUsers() {
    for (var user : Map.of("U1", "private@example.test", "U2", "u2@example.test").entrySet())
      jdbc.sql("INSERT INTO users VALUES (:id,:email,true,'member',:at)")
          .param("id", user.getKey())
          .param("email", user.getValue())
          .param("at", Timestamp.from(T0))
          .update();
    jdbc.sql("INSERT INTO user_source_identities VALUES (:source,:email,'U1','admin',:at,'U1')")
        .param("source", SOURCE.value())
        .param("email", "private@example.test")
        .param("at", Timestamp.from(T0))
        .update();
  }

  /** 폴더 목록이 계속 500이다(재시도를 다 써도). */
  void folderListingFails() {
    server.failNext(FOLDERS, 20, FakeTiroServer.Fault.status(500));
  }

  /** 수집(본문·접근)한 뒤 투영한다. */
  CollectionRun collectAndProject() {
    clock.advance(Duration.ofSeconds(1));
    collection.build().collect(SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    var reports =
        projection.run("tiro-acl", new ManualTaskScheduler()).runDue(CancellationToken.none());
    if (reports.stream().anyMatch(report -> report.outcome() != ProjectionRun.Outcome.SUCCEEDED))
      throw new IllegalStateException("Tiro 투영 실패: " + reports);
    return stores.runs().latest(SOURCE).orElseThrow();
  }

  /** 이 사용자가 질의로 찾는 문서 id. */
  List<String> documentsFound(String user, String query) {
    return engine.search(new Principal(user), SearchQuery.of(query)).hits().stream()
        .map(ContextHit::documentId)
        .distinct()
        .toList();
  }

  @Override
  public void close() {
    try {
      server.assertNoUnmatched();
    } finally {
      server.close();
    }
  }
}
