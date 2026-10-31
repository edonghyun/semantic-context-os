package io.scos.engine;

import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.micrometer.core.instrument.Tags;
import io.scos.spi.embedding.FixedEmbedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("키워드 전용 조립은 임베더 없이 투영하고 검색한다(A2)")
class KeywordOnlyEngineTest {
  final KeywordOnlyScenario scenario = new KeywordOnlyScenario();

  @AfterEach
  void closeScenario() {
    scenario.close();
  }

  @Test
  @DisplayName("키워드 전용 투영기는 유닛 하나를 보고한다")
  void reportsProjectedUnit() {
    // given: 키워드 전용 투영기

    // when
    IngestReport report = scenario.ingest();

    // then
    assertThat(report.units()).isEqualTo(1);
  }

  @Test
  @DisplayName("키워드 전용 투영기는 저장한 유닛에 벡터 행을 남기지 않는다")
  void ingestWithoutVectors() {
    // given
    ContextIngestor ingestor = scenario.ingestorBuilder().keywordOnly().build();

    // when
    scenario.ingestWith(ingestor);

    // then
    assertThat(scenario.storedVectors()).isEmpty();
  }

  @Test
  @DisplayName("키워드 전용 투영기는 벡터 색인에 쓰지 않는다")
  void neverWritesVectors() {
    // given: 키워드 전용 투영기

    // when
    scenario.ingest();

    // then
    assertThat(scenario.vectorUpserts()).isZero();
  }

  @Test
  @DisplayName("키워드 전용 투영기는 기존 공간의 임베딩 세대를 유지한다")
  void recordsKeywordOnlyGeneration() {
    // given: 키워드 전용 투영기

    // when
    scenario.ingest();

    // then
    assertThat(scenario.generation()).contains("none:none:1:title+aliases+content");
  }

  @Nested
  @DisplayName("문서가 키워드 전용으로 투영된 상태에서")
  class DocumentIngested {
    @BeforeEach
    void givenDocumentIngested() {
      scenario.givenDocumentIngested();
    }

    @Test
    @DisplayName("키워드 전용 엔진은 키워드로 문서를 찾는다")
    void findsByKeyword() {
      // when
      SearchResult result = scenario.search();

      // then
      assertThat(result.hits())
          .extracting(ContextHit::documentId, ContextHit::title)
          .containsExactly(tuple("d1", "정산 배치"));
    }

    @Test
    @DisplayName("키워드 전용 엔진은 벡터 색인을 검색하지 않는다")
    void neverSearchesVectors() {
      // when
      scenario.search();

      // then
      assertThat(scenario.vectorSearches()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"variants", "lexical", "load", "links", "rerank", "rank", "freshness"})
    @DisplayName("키워드 전용 검색은 실행한 각 단계의 이름을 유지한다")
    void recordsExecutedStages(String stage) {
      // when
      scenario.search();

      // then
      assertThatMetrics(scenario.registry)
          .hasTimerCount("scos.search.stage", Tags.of("recipe", "search", "stage", stage), 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"embed", "vector"})
    @DisplayName("키워드 전용 검색은 실행하지 않은 단계를 기록하지 않는다")
    void omitsVectorStages(String stage) {
      // when
      scenario.search();

      // then
      assertThatMetrics(scenario.registry)
          .hasNoTimer("scos.search.stage", Tags.of("recipe", "search", "stage", stage));
    }

    @Test
    @DisplayName("검색 빌더에서 마지막 키워드 선택은 앞선 임베더 선택을 대신한다")
    void engineSelectsKeywordOnlyLast() {
      // given
      ContextEngine engine =
          scenario.engineBuilder().embedder(FixedEmbedder.zero(2)).keywordOnly().build();

      // when
      scenario.searchWith(engine);

      // then
      assertThat(scenario.vectorSearches()).isZero();
    }

    @Test
    @DisplayName("검색 빌더에서 마지막 임베더 선택은 앞선 키워드 선택을 대신한다")
    void engineSelectsEmbedderLast() {
      // given
      ContextEngine engine =
          scenario.engineBuilder().keywordOnly().embedder(FixedEmbedder.zero(2)).build();

      // when
      scenario.searchWith(engine);

      // then
      assertThat(scenario.vectorSearches()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("투영 빌더에서 마지막 키워드 선택은 앞선 임베더 선택을 대신한다")
  void ingestorSelectsKeywordOnlyLast() {
    // given
    ContextIngestor ingestor =
        scenario.ingestorBuilder().embedder(FixedEmbedder.zero(2)).keywordOnly().build();

    // when
    scenario.ingestWith(ingestor);

    // then
    assertThat(scenario.generation()).contains("none:none:1:title+aliases+content");
  }

  @Test
  @DisplayName("투영 빌더에서 마지막 임베더 선택은 앞선 키워드 선택을 대신한다")
  void ingestorSelectsEmbedderLast() {
    // given
    ContextIngestor ingestor =
        scenario.ingestorBuilder().keywordOnly().embedder(FixedEmbedder.zero(2)).build();

    // when
    scenario.ingestWith(ingestor);

    // then
    assertThat(scenario.storedVectors().values()).containsExactly(new double[] {0, 0});
  }
}
