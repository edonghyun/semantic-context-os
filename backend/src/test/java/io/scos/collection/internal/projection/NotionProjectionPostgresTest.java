package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.notion.internal.FakeNotionServer;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.store.ContextUnit;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Notion 수집 → 투영 종단(TESTING §3.6 6층, PostgreSQL): roots 첫 수집이 저장한 원본(64 KiB 넘는 root-retro 페이지는 blob
 * 테이블)을 실제 ProjectionRun이 읽어(readContent — inline·blob) Notion 투영기로 문서를 만들고, 엔진 유닛이 TS 기대(TS가 같은
 * 원본을 RawContentReader로 읽어 만든 expected-units.json)와 같다. 실행은 @BeforeAll에서 한 번(규칙 6).
 */
@Tag("db")
@TestInstance(PER_CLASS)
@DisplayName("Notion roots 데이터셋을 수집해 투영하면")
class NotionProjectionPostgresTest {
  /** TS가 투영한 문서 수: expected-documents.json의 문서 6건, 건너뜀 0건(손으로 센 값). */
  static final int TS_DOCUMENTS = 6;

  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.notion.dir", "../harness/fixtures/notion"));

  ProjectionRun.Report report;
  List<ContextUnit> units;

  @BeforeAll
  void collectThenProject() {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    MutableClock clock = new MutableClock(NOW);
    ManualTaskScheduler scheduler = new ManualTaskScheduler();
    try (FakeNotionServer server = NotionAssembly.server()) {
      PostgresCollectionConfiguration.adapters(ds)
          .sources()
          .save(NotionAssembly.instance(NotionAssembly.config("roots"), NOW));
      PostgresCollectionRuntime.over(ds, clock)
          .adapter(NotionAssembly.adapter(server.baseUrl(), server.token(), clock))
          .heartbeats(scheduler)
          .build()
          .collect(NotionAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
    }
    List<ProjectionRun.Report> reports =
        PostgresProjection.over(ds, clock, new ScriptedEmbedder())
            .run("p1", scheduler)
            .runDue(CancellationToken.none());
    if (reports.size() != 1)
      throw new IllegalStateException("given 준비 실패: 투영 요청이 하나여야 한다 — " + reports);
    report = reports.getFirst();
    units = PostgresTestAdapters.unitStore(ds).listAll(PostgresProjection.SPACE);
  }

  static JsonNode expected(String name) {
    try {
      return JSON.readTree(Files.readString(FIXTURES.resolve(name)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<String> expectedUnits(String field) {
    List<String> out = new ArrayList<>();
    expected("expected-units.json").get("units").forEach(u -> out.add(u.get(field).asString()));
    return out;
  }

  @Test
  @DisplayName("notion 투영기로 TS와 같은 수의 문서를 쓴다")
  void projectsTsDocumentCount() {
    // then
    assertThat(report)
        .extracting(ProjectionRun.Report::kind, ProjectionRun.Report::documents)
        .containsExactly("notion", TS_DOCUMENTS);
  }

  @Test
  @DisplayName("엔진 유닛 id가 TS 기대와 같다")
  void unitIdsMatchTs() {
    // then
    assertThat(units.stream().map(ContextUnit::id))
        .containsExactlyInAnyOrderElementsOf(expectedUnits("id"));
  }

  @Test
  @DisplayName("엔진 유닛 제목이 TS 기대와 같다(blob 페이지 원본의 제목 포함)")
  void unitTitlesMatchTs() {
    // then
    assertThat(units.stream().map(ContextUnit::title))
        .containsExactlyInAnyOrderElementsOf(expectedUnits("title"));
  }

  @Test
  @DisplayName("엔진 유닛 본문이 TS 기대와 같다")
  void unitContentsMatchTs() {
    // then
    assertThat(units.stream().map(ContextUnit::content))
        .containsExactlyInAnyOrderElementsOf(expectedUnits("content"));
  }
}
