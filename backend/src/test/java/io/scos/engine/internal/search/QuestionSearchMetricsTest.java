package io.scos.engine.internal.search;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static io.scos.support.MeterRegistryAssert.assertThatMetrics;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.AccessScope;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** 검색 지표(ADR-0009): 단계 타이머가 레시피·단계마다 한 번씩 기록되고 재정렬 결과가 종류별로 세어진다. */
@DisplayName("검색 지표는 단계 타이머를 레시피·단계마다 한 번 기록한다(ADR-0009)")
class QuestionSearchMetricsTest {
  static final Clock NOW = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
  static final int DIM = 2;
  static final int RERANK_HEAD = 30;
  final SearchFixture fx = new SearchFixture();
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  QuestionSearch search(Embedder embedder, Reranker reranker) {
    return new QuestionSearch(
        fx.units,
        fx.keywords,
        fx.vectors,
        embedder,
        reranker,
        SearchSettings.DEFAULT,
        NOW,
        SearchMetrics.of(registry),
        TestExecutors.CALLER_THREAD);
  }

  QuestionSearch searchWithoutRerank() {
    return search(FixedEmbedder.zero(DIM), Reranker.DISABLED);
  }

  static Tags stage(String recipe, String stage) {
    return Tags.of("recipe", recipe, "stage", stage);
  }

  void givenOneUnit() {
    fx.load(S, unit("a-1").inDocument("a").content("정산").build());
  }

  @Nested
  @DisplayName("유닛 하나가 있는 공간에 search_context를 한 번 부르면")
  class SearchRecipe {
    @BeforeEach
    void oneUnit() {
      givenOneUnit();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "variants",
          "lexical",
          "embed",
          "vector",
          "load",
          "links",
          "rerank",
          "rank",
          "freshness"
        })
    @DisplayName("recipe=search의 각 단계 타이머를 한 번 기록한다")
    void recordsEveryStageOnce(String name) {
      // when
      searchWithoutRerank().search(AccessScope.singleUser(), S, "정산");

      // then
      assertThatMetrics(registry).hasTimerCount(SearchMetrics.STAGE, stage("search", name), 1);
    }

    @Test
    @DisplayName("assemble 레시피 단계는 기록하지 않는다")
    void doesNotRecordAssemble() {
      // when
      searchWithoutRerank().search(AccessScope.singleUser(), S, "정산");

      // then
      assertThatMetrics(registry).hasNoTimer(SearchMetrics.STAGE, stage("assemble", "lexical"));
    }

    @Test
    @DisplayName("재정렬이 꺼져 있으면 rerank{skipped}를 센다")
    void countsSkippedRerank() {
      // when
      searchWithoutRerank().search(AccessScope.singleUser(), S, "정산");

      // then
      assertThatMetrics(registry)
          .hasCounter(SearchMetrics.RERANK, Tags.of("outcome", "skipped"), 1);
    }
  }

  @Nested
  @DisplayName("유닛 하나가 있는 공간에 assemble_context 레시피를 부르면")
  class AssembleRecipe {
    @BeforeEach
    void oneUnit() {
      givenOneUnit();
    }

    @Test
    @DisplayName("후보를 돌려준다")
    void returnsCandidates() {
      // when
      List<QuestionSearch.Candidate> candidates =
          searchWithoutRerank().assembleRecipe(AccessScope.singleUser(), S, "정산");

      // then
      assertThat(candidates).extracting(c -> c.unit().id()).containsExactly("a-1");
    }

    @Test
    @DisplayName("단계를 recipe=assemble로만 기록한다")
    void recordsAssembleRecipe() {
      // when
      searchWithoutRerank().assembleRecipe(AccessScope.singleUser(), S, "정산");

      // then
      assertThatMetrics(registry)
          .hasTimerCount(SearchMetrics.STAGE, stage("assemble", "lexical"), 1)
          .hasNoTimer(SearchMetrics.STAGE, stage("search", "lexical"));
    }

    @Test
    @DisplayName("재정렬이 꺼져 있으면 rerank 단계를 기록하지 않는다")
    void recordsAssembleWithoutRerank() {
      // when
      searchWithoutRerank().assembleRecipe(AccessScope.singleUser(), S, "정산");

      // then
      assertThatMetrics(registry).hasNoTimer(SearchMetrics.STAGE, stage("assemble", "rerank"));
    }
  }

  @Test
  @DisplayName("재정렬 결과는 applied·fallback_format·fallback_error로 나뉘어 세어진다")
  void rerankOutcomesAreCounted() {
    // given: 재정렬 생성기가 "2,1"(적용) → "garbage"(형식 오류) → 공급자 오류 순으로 답한다
    fx.load(
        S,
        unit("a-1").inDocument("a").content("정산 1").build(),
        unit("b-1").inDocument("b").content("정산 2").build());
    QuestionSearch search =
        search(
            FixedEmbedder.zero(DIM),
            new LlmReranker(answersThenFails("2,1", "garbage"), RERANK_HEAD, new ObjectMapper()));

    // when
    search.search(AccessScope.singleUser(), S, "정산");
    search.search(AccessScope.singleUser(), S, "정산 배치");
    search.search(AccessScope.singleUser(), S, "정산 규정");

    // then
    assertThatMetrics(registry)
        .hasCounter(SearchMetrics.RERANK, Tags.of("outcome", "applied"), 1)
        .hasCounter(SearchMetrics.RERANK, Tags.of("outcome", "fallback_format"), 1)
        .hasCounter(SearchMetrics.RERANK, Tags.of("outcome", "fallback_error"), 1);
  }

  @Test
  @DisplayName("of(registry).registry()는 받은 레지스트리를 그대로 돌려준다")
  void registryIsShared() {
    // when
    var shared = SearchMetrics.of(registry).registry();

    // then
    assertThat(shared).isSameAs(registry);
  }

  @Test
  @DisplayName("변형 질의는 원질의와 함께 한 번의 임베딩 호출에 들어간다(원질의 + 변형 2)")
  void variantsAreEmbeddedWithTheQuery() {
    // given
    fx.load(S, unit("a-1").inDocument("a").content("결제 장애 배포 절차").build());
    List<Integer> batchSizes = new ArrayList<>();

    // when
    search(recordingBatchSizes(batchSizes), Reranker.DISABLED)
        .search(AccessScope.singleUser(), S, "결제 장애 그리고 배포 절차");

    // then
    assertThat(batchSizes).containsExactly(3);
  }

  /** 응답들을 차례로 돌려주고, 다 쓰면 공급자 오류를 던진다. */
  static TextGenerator answersThenFails(String... responses) {
    Deque<String> left = new ArrayDeque<>(List.of(responses));
    return (prompt, max) -> {
      if (left.isEmpty()) throw new TextGenerator.GenerationException("Gemini API 503");
      return left.removeFirst();
    };
  }

  /** 임베딩 호출마다 배치 크기를 기록하는 영벡터 임베더. */
  static Embedder recordingBatchSizes(List<Integer> sizes) {
    return new Embedder() {
      @Override
      public int dim() {
        return DIM;
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        sizes.add(texts.size());
        return FixedEmbedder.zero(DIM).embed(texts);
      }
    };
  }
}
