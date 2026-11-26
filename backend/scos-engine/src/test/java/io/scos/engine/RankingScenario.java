package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.engine.internal.search.SearchFixture;
import io.scos.spi.store.Principal;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 공개 빌더로 키워드 전용 엔진을 메모리 어댑터 위에 조립하고, 고정 시각에서 검색한다. */
final class RankingScenario {
  static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
  static final String TEN_DAYS_AGO = "2026-10-10T00:00:00Z";
  private final SearchFixture fixture = new SearchFixture();
  private final UnaryOperator<ContextEngineBuilder> assembly;

  RankingScenario() {
    this(UnaryOperator.identity());
  }

  RankingScenario(UnaryOperator<ContextEngineBuilder> assembly) {
    this.assembly = assembly;
  }

  static RankingScenario with(RankingSettings ranking) {
    return new RankingScenario(b -> b.ranking(ranking));
  }

  /** "older"가 키워드 1위(정산 두 번, 10일 전), "newer"가 2위(정산 한 번, 지금). */
  void givenOlderMatchesMore() {
    fixture.load(
        SearchFixture.S,
        unit("older").content("정산 정산").capturedAt(TEN_DAYS_AGO).build(),
        unit("newer").content("정산").capturedAt(NOW.toString()).build());
  }

  /** 문서 d의 조각 셋이 키워드 1~3위, 다른 문서 x가 4위. */
  void givenOneDocumentFillsTheTop() {
    fixture.load(
        SearchFixture.S,
        unit("d-1").inDocument("d").content("정산 정산 정산").build(),
        unit("d-2").inDocument("d").content("정산 정산").build(),
        unit("d-3").inDocument("d").content("정산").build(),
        unit("x").inDocument("x").content("정산").build());
  }

  /** 원천 beta의 유닛이 키워드 1위, 원천 alpha의 유닛이 2위. */
  void givenTwoSources() {
    fixture.load(
        SearchFixture.S,
        unit("beta-unit").source("beta").content("정산 정산").build(),
        unit("alpha-unit").source("alpha").content("정산").build());
  }

  SearchResult search(String query) {
    return search(query, Profile.AUTO, 5);
  }

  SearchResult search(String query, Profile profile, int limit) {
    return search(query, profile, limit, Effort.MEDIUM);
  }

  SearchResult search(String query, Profile profile, int limit, Effort effort) {
    return engine()
        .search(
            Principal.singleUser(),
            new SearchQuery(Optional.empty(), query, limit, effort, profile));
  }

  ContextBundle assemble(String query) {
    return engine()
        .assemble(
            Principal.singleUser(),
            new AssembleQuery(
                Optional.empty(),
                query,
                AssembleQuery.MAX_TOKEN_BUDGET,
                Effort.MEDIUM,
                Profile.AUTO));
  }

  private ContextEngine engine() {
    return assembly
        .apply(
            ContextEngine.builder()
                .units(fixture.units)
                .unrestricted(fixture.maintenance)
                .keywords(fixture.keywords)
                .keywordOnly()
                .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                .lexicalExecutor(TestExecutors.CALLER_THREAD))
        .build();
  }
}
