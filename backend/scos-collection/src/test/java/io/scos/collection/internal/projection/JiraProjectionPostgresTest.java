package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.jira.internal.JiraDatasetCollection;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.store.ContextUnit;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 * Jira 종단(PostgreSQL): 가짜 Jira에서 실제 런타임으로 수집한 원본을 제품 투영 조립(kind 레지스트리에 등록된 JiraProjector)이 엔진 유닛으로
 * 만든다. 64 KiB를 넘는 이슈(40011)는 원본이 blob으로 저장되고 투영이 blob에서 읽는다(ProjectionRun.readContent의 blob 갈래 — 첨부
 * blob은 저장만 되고 투영 대상이 아니다). 유닛은 TS 기대(expected-units.json)와 같다. Jira는 명시 공간 매핑 없이 기본 공간에 투영한다(TS도
 * 소스별 규칙이 없다). 수집·투영은 @BeforeAll에서 한 번 하고 결과를 계약별로 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@TestInstance(PER_CLASS)
@DisplayName("Jira 데이터셋을 수집해 투영하면")
class JiraProjectionPostgresTest {
  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
  static final ObjectMapper JSON = new ObjectMapper();

  /** 첨부·필드 원본을 뺀 문서 수(TS expected-documents.json). */
  static final int TS_DOCUMENTS = 18;

  ProjectionRun.Report report;
  List<ContextUnit> units;

  @BeforeAll
  void collectThenProject() {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    MutableClock clock = new MutableClock(NOW);
    ManualTaskScheduler scheduler = new ManualTaskScheduler();
    JiraDatasetCollection.collectInto(ds, clock, scheduler);
    List<ProjectionRun.Report> reports =
        PostgresProjection.over(ds, clock, new ScriptedEmbedder())
            .run("p1", scheduler)
            .runDue(CancellationToken.none());
    if (reports.size() != 1)
      throw new IllegalStateException("given 준비 실패: 투영 요청이 하나여야 한다 — " + reports);
    report = reports.getFirst();
    units = PostgresTestAdapters.unitStore(ds).listAll(PostgresProjection.SPACE);
  }

  static List<JsonNode> expectedUnits() {
    try {
      List<JsonNode> out = new ArrayList<>();
      JSON.readTree(Files.readString(JiraAssembly.FIXTURES.resolve("expected-units.json")))
          .get("units")
          .forEach(out::add);
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  @DisplayName("jira 투영기로 문서 18건을 기본 공간에 쓴다")
  void projectsDocuments() {
    // then
    assertThat(report)
        .extracting(ProjectionRun.Report::kind, ProjectionRun.Report::documents)
        .containsExactly("jira", TS_DOCUMENTS);
  }

  @Test
  @DisplayName("엔진 유닛 id가 TS 기대와 같다")
  void unitIdsMatchTs() {
    // then
    assertThat(units.stream().map(ContextUnit::id))
        .containsExactlyInAnyOrderElementsOf(
            expectedUnits().stream().map(u -> u.get("id").asString()).toList());
  }

  @Test
  @DisplayName("엔진 유닛 본문이 TS 기대와 같다")
  void unitContentsMatchTs() {
    // then
    assertThat(units.stream().map(ContextUnit::content))
        .containsExactlyInAnyOrderElementsOf(
            expectedUnits().stream().map(u -> u.get("content").asString()).toList());
  }
}
