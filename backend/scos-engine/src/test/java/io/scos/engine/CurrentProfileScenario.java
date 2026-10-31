package io.scos.engine;

import static io.scos.spi.store.ContextUnits.unit;

import io.scos.engine.internal.search.SearchFixture;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.Principal;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** 실제 메모리 어댑터로 관련도와 수집 시각이 어긋난 코퍼스를 조립한다. */
final class CurrentProfileScenario {
  static final String OLD = "2026-10-26T00:00:00Z";
  static final String NEW = "2026-10-27T00:00:00Z";
  static final String QUERY = "정산";
  static final int SMALL_BUDGET = 500;
  private static final int OLD_TOKENS = 300;
  private static final int NEW_TOKENS = 200;
  private static final int EMBEDDING_DIMENSIONS = 2;

  /** Presets.LOW의 lexicalK·vectorK 후보 폭을 무효 유닛으로 채워 MEDIUM 상향을 유도한다. */
  private static final int LOW_CANDIDATE_CAP = 20;

  private static final int BEYOND_CURRENT_WINDOW = 21;
  private static final Clock NOW = Clock.fixed(Instant.parse(NEW), ZoneOffset.UTC);
  private final SearchFixture fixture = new SearchFixture();

  void givenOlderRanksFirst() {
    fixture.load(
        SearchFixture.S,
        unit("old").content("정산 정산").capturedAt(OLD).build(),
        unit("new").content("정산").capturedAt(NEW).build());
  }

  void givenNewestOutsideWindow() {
    fixture.load(
        SearchFixture.S,
        unit("outside").content("정산").capturedAt("2026-10-27T00:00:00Z").build(),
        unit("new").content("정산 정산").capturedAt("2026-10-26T00:00:00Z").build(),
        unit("old").content("정산 정산 정산").capturedAt("2026-10-25T00:00:00Z").build());
  }

  void givenBudgetSelectsOlderAndNewer() {
    fixture.load(
        SearchFixture.S,
        unit("old").content("정산 정산").capturedAt(OLD).tokens(OLD_TOKENS).build(),
        unit("new").content("정산").capturedAt(NEW).tokens(NEW_TOKENS).build(),
        unit("over-budget")
            .content("정산")
            .capturedAt("2026-10-28T00:00:00Z")
            .tokens(SMALL_BUDGET)
            .build());
  }

  ContextBundle assemble(Profile profile) {
    return engine()
        .assemble(
            Principal.singleUser(),
            new AssembleQuery(Optional.empty(), QUERY, SMALL_BUDGET, Effort.LOW, profile));
  }

  void givenUnknownTimes() {
    fixture.load(
        SearchFixture.S,
        unit("invalid").content("정산 정산 정산 정산").capturedAt("not-an-instant").build(),
        unit("missing").content("정산 정산 정산").capturedAt("").build(),
        unit("old").content("정산 정산").capturedAt(OLD).build(),
        unit("new").content("정산").capturedAt(NEW).build());
  }

  void givenSameInstant() {
    fixture.load(
        SearchFixture.S,
        unit("first").content("정산 정산 정산").capturedAt("2026-10-26T09:00:00+09:00").build(),
        unit("second").content("정산 정산").capturedAt(OLD).build(),
        unit("third").content("정산").capturedAt(OLD).build());
  }

  void givenMoreThanTwentyCandidates() {
    for (int rank = 1; rank <= BEYOND_CURRENT_WINDOW; rank++) {
      fixture.load(
          SearchFixture.S,
          unit("rank-" + rank)
              .content("정산")
              .capturedAt(Instant.parse(OLD).plusSeconds(rank).toString())
              .build());
    }
  }

  void givenLowFindsOnlyInvalidatedUnits() {
    for (int rank = 1; rank <= LOW_CANDIDATE_CAP; rank++) {
      fixture.load(
          SearchFixture.S,
          unit("invalidated-" + rank)
              .content("정산 정산 정산")
              .capturedAt(OLD)
              .invalidatedAt(NEW)
              .build());
    }
    givenOlderRanksFirst();
  }

  SearchResult search(Profile profile, int limit, Effort effort) {
    return engine()
        .search(
            Principal.singleUser(),
            new SearchQuery(Optional.empty(), QUERY, limit, effort, profile));
  }

  SearchResult search(Profile profile, int limit) {
    return search(profile, limit, QUERY);
  }

  SearchResult search(Profile profile, int limit, String query) {
    return engine()
        .search(
            Principal.singleUser(),
            new SearchQuery(Optional.empty(), query, limit, Effort.LOW, profile));
  }

  private ContextEngine engine() {
    return ContextEngine.builder()
        .units(fixture.units)
        .unrestricted(fixture.maintenance)
        .keywords(fixture.keywords)
        .vectors(fixture.vectors)
        .embedder(FixedEmbedder.zero(EMBEDDING_DIMENSIONS))
        .clock(NOW)
        .lexicalExecutor(TestExecutors.CALLER_THREAD)
        .build();
  }
}
