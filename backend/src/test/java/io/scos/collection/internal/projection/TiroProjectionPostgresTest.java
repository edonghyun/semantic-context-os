package io.scos.collection.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import io.scos.boot.PostgresCollectionConfiguration;
import io.scos.collection.internal.postgres.PostgresCollectionRuntime;
import io.scos.collection.internal.postgres.PostgresSupport;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.connector.tiro.internal.FakeTiroServer;
import io.scos.engine.support.text.MutableClock;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.store.ContextUnit;
import io.scos.store.postgres.PostgresTestAdapters;
import io.scos.support.ManualTaskScheduler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * Tiro 종단(PostgreSQL): 가짜 Tiro에서 실제 런타임으로 수집한 원본을 제품 투영 조립(kind 레지스트리에 등록된 TiroProjector)이 엔진 유닛으로
 * 만든다. 유닛은 TS 기대(expected-units.json)와 같다. Tiro는 명시 공간 매핑이 필수다(A5) — 매핑이 없으면 투영 run이 실패하고 기본 공간에
 * 아무것도 쓰지 않는다. 상황마다 수집·투영을 @BeforeAll에서 한 번 하고 결과를 계약별로 읽는다(TESTING §2.5 규칙 6).
 */
@Tag("db")
@DisplayName("Tiro 데이터셋을 수집해 투영하면")
class TiroProjectionPostgresTest {
  static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

  /** 한 상황의 기록: 투영 보고와 기본 공간({@link PostgresProjection#SPACE})의 유닛. */
  record Projected(ProjectionRun.Report report, List<ContextUnit> units) {}

  /** DB를 비우고 가짜 Tiro를 수집한 뒤, 주어진 투영 조립으로 한 번 투영한다. */
  static Projected collectThenProject(UnaryOperator<PostgresProjection> configure) {
    PostgresSupport.truncateAll();
    DataSource ds = PostgresSupport.dataSource();
    MutableClock clock = new MutableClock(NOW);
    ManualTaskScheduler scheduler = new ManualTaskScheduler();
    try (FakeTiroServer server = TiroAssembly.server()) {
      PostgresCollectionConfiguration.adapters(ds)
          .sources()
          .save(TiroAssembly.instance(server.configJson(), NOW));
      PostgresCollectionRuntime.over(ds, clock)
          .adapter(TiroAssembly.adapter(server.baseUrl(), server.token(), clock))
          .heartbeats(scheduler)
          .build()
          .collect(TiroAssembly.SOURCE, CollectionMode.INCREMENTAL, CancellationToken.none());
      server.assertNoUnmatched();
    }
    List<ProjectionRun.Report> reports =
        configure
            .apply(PostgresProjection.over(ds, clock, new ScriptedEmbedder()))
            .run("p1", scheduler)
            .runDue(CancellationToken.none());
    if (reports.size() != 1)
      throw new IllegalStateException("given 준비 실패: 투영 요청이 하나여야 한다 — " + reports);
    return new Projected(
        reports.getFirst(), PostgresTestAdapters.unitStore(ds).listAll(PostgresProjection.SPACE));
  }

  static List<JsonNode> expectedUnits() {
    try {
      List<JsonNode> out = new ArrayList<>();
      TiroAssembly.JSON
          .readTree(Files.readString(TiroAssembly.FIXTURES.resolve("expected-units.json")))
          .get("units")
          .forEach(out::add);
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("소스를 공간에 명시 매핑했으면")
  class Mapped {
    Projected projected;

    @BeforeAll
    void run() {
      projected = collectThenProject(p -> p.explicitlyMapped(TiroAssembly.SOURCE.value()));
    }

    @Test
    @DisplayName("tiro 투영기로 노트 문서 4건을 쓴다")
    void projectsFourNotes() {
      // then
      assertThat(projected.report())
          .extracting(ProjectionRun.Report::kind, ProjectionRun.Report::documents)
          .containsExactly("tiro", 4);
    }

    @Test
    @DisplayName("엔진 유닛 id가 TS 기대와 같다")
    void unitIdsMatchTs() {
      // then
      assertThat(projected.units().stream().map(ContextUnit::id))
          .containsExactlyInAnyOrderElementsOf(
              expectedUnits().stream().map(u -> u.get("id").asString()).toList());
    }

    @Test
    @DisplayName("엔진 유닛 본문이 TS 기대와 같다")
    void unitContentsMatchTs() {
      // then
      assertThat(projected.units().stream().map(ContextUnit::content))
          .containsExactlyInAnyOrderElementsOf(
              expectedUnits().stream().map(u -> u.get("content").asString()).toList());
    }
  }

  @Nested
  @TestInstance(PER_CLASS)
  @DisplayName("명시 공간 매핑이 없으면(A5)")
  class Unmapped {
    Projected projected;

    @BeforeAll
    void run() {
      projected = collectThenProject(p -> p);
    }

    @Test
    @DisplayName("투영 run은 실패한다")
    void projectionFails() {
      // then
      assertThat(projected.report().outcome()).isEqualTo(ProjectionRun.Outcome.FAILED);
    }

    @Test
    @DisplayName("실패 사유가 고칠 환경변수 SCOS_SOURCE_SPACES를 말한다")
    void failureNamesSourceSpaces() {
      // then
      assertThat(projected.report().error())
          .hasValueSatisfying(e -> assertThat(e).contains("SCOS_SOURCE_SPACES"));
    }

    @Test
    @DisplayName("기본 공간에 유닛을 쓰지 않는다")
    void writesNothingToDefaultSpace() {
      // then
      assertThat(projected.units()).isEmpty();
    }
  }
}
