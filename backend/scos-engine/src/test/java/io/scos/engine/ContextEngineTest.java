package io.scos.engine;

import static io.scos.engine.internal.search.SearchFixture.S;
import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import io.scos.engine.internal.search.QuestionSearch;
import io.scos.engine.internal.search.Reranker;
import io.scos.engine.internal.search.SearchFixture;
import io.scos.engine.internal.search.SearchSettings;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.Embedder.EmbedderException;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.embedding.TextGenerator;
import io.scos.spi.store.AccessScope;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.ContextUnits;
import io.scos.spi.store.IndexCapabilities;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.KeywordIndex;
import io.scos.spi.store.Principal;
import io.scos.spi.store.ScoredRef;
import io.scos.spi.store.SearchFilter;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.LogCapture;
import io.scos.support.TestExecutors;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 읽기 파사드(단계 2 §1.1–§1.3): 질의 범위는 엔진이 검사하고, 계약 위반은 ContextEngineException 하위로, 공간을 주지 않으면 기본 공간을 쓴다.
 * 검색 레시피는 내부 QuestionSearch 그대로다. 메모리 스토어로 조립한다.
 */
@DisplayName("ContextEngine은 질의를 검사하고 내부 레시피에 맡긴다(단계 2 §1)")
class ContextEngineTest {
  static final Principal ALICE = new Principal("alice");
  static final SpaceId OTHER = new SpaceId("other");
  static final Embedder ZERO = FixedEmbedder.zero(2);
  static final ObjectMapper JSON = new ObjectMapper();

  /** 실행기 스레드를 못 만들어 키워드 채널이 멈춰도 PIT 시간 초과보다 먼저 단언으로 실패하게 하는 상한. */
  static final Duration HANG_LIMIT = Duration.ofSeconds(10);

  SearchFixture fx = new SearchFixture();

  /** 질의 임베딩이 HTTP 503으로 실패하는 공급자. */
  static final class UnavailableEmbedder implements Embedder {
    static final int STATUS = 503;

    @Override
    public int dim() {
      return 2;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
      throw new EmbedderException("Gemini embeddings " + STATUS, STATUS);
    }
  }

  ContextEngineBuilder builder(Embedder embedder) {
    return new ContextEngineBuilder()
        .units(fx.units)
        .keywords(fx.keywords)
        .vectors(fx.vectors)
        .embedder(embedder)
        .lexicalExecutor(TestExecutors.CALLER_THREAD);
  }

  ContextEngine engine() {
    return builder(ZERO).build();
  }

  /** 재정렬 생성기가 늘 이 응답을 내는 엔진. */
  ContextEngine rerankingEngine(String response) {
    TextGenerator generator = (prompt, maxTokens) -> response;
    return builder(ZERO).reranker(generator, JSON).build();
  }

  /** 키워드 검색을 부른 스레드를 남기는 색인(나머지는 fx.keywords에 맡긴다). */
  final class ThreadRecordingKeywords implements KeywordIndex {
    volatile Thread searchedOn;

    @Override
    public IndexCapabilities capabilities() {
      return fx.keywords.capabilities();
    }

    @Override
    public List<ScoredRef> search(SpaceId space, String text, int k, SearchFilter filter) {
      searchedOn = Thread.currentThread();
      return fx.keywords.search(space, text, k, filter);
    }

    @Override
    public void upsert(SpaceId space, List<IndexedText> units) {
      fx.keywords.upsert(space, units);
    }

    @Override
    public void delete(SpaceId space, List<String> unitIds) {
      fx.keywords.delete(space, unitIds);
    }
  }

  /** 엔진 메서드 하나를 부르는 사례({@code @MethodSource}가 static이라 엔진을 받아 부른다). */
  interface EngineCall {
    Object on(ContextEngine engine);
  }

  static final String MISSING = "missing-doc";

  static Arguments call(String name, EngineCall call, String expected) {
    return Arguments.of(Named.of(name, call), expected);
  }

  static Stream<Arguments> outOfRange() {
    return Stream.of(
        call("search limit 0", e -> e.search(ALICE, query("정산", 0)), "limit"),
        call("search limit 51", e -> e.search(ALICE, query("정산", 51)), "limit"),
        call("search 빈 질의", e -> e.search(ALICE, query("", 5)), "text"),
        call("read 빈 documentId", e -> e.read(ALICE, read("", 0, 20)), "documentId"),
        call("read offset -1", e -> e.read(ALICE, read("doc", -1, 20)), "offset"),
        call("read limit 101", e -> e.read(ALICE, read("doc", 0, 101)), "limit"),
        call("related 빈 documentId", e -> e.related(ALICE, related("", 5)), "documentId"),
        call("related limit -1", e -> e.related(ALICE, related("doc", -1)), "limit"),
        call("assemble 빈 질의", e -> e.assemble(ALICE, assemble("", 4000)), "text"),
        call("assemble budget 499", e -> e.assemble(ALICE, assemble("정산", 499)), "tokenBudget"));
  }

  static Stream<Named<EngineCall>> missingDocument() {
    return Stream.of(
        Named.of("read", e -> e.read(ALICE, ReadRequest.of(MISSING))),
        Named.of("related", e -> e.related(ALICE, RelatedQuery.of(MISSING))));
  }

  static Stream<Named<EngineCall>> embeddingQueries() {
    return Stream.of(
        Named.of("search", e -> e.search(ALICE, SearchQuery.of("정산"))),
        Named.of("assemble", e -> e.assemble(ALICE, AssembleQuery.of("정산"))));
  }

  /** 필수 포트 하나만 빠진 builder. */
  static Stream<Arguments> missingPorts() {
    return Stream.of(
        Arguments.of(
            "units",
            new ContextEngineBuilder()
                .keywords(new MemoryKeywordIndex())
                .vectors(new MemoryVectorIndex())
                .embedder(ZERO)),
        Arguments.of(
            "keywords",
            new ContextEngineBuilder()
                .units(new MemoryUnitStore())
                .vectors(new MemoryVectorIndex())
                .embedder(ZERO)),
        Arguments.of(
            "vectors",
            new ContextEngineBuilder()
                .units(new MemoryUnitStore())
                .keywords(new MemoryKeywordIndex())
                .embedder(ZERO)),
        Arguments.of(
            "embedder",
            new ContextEngineBuilder()
                .units(new MemoryUnitStore())
                .keywords(new MemoryKeywordIndex())
                .vectors(new MemoryVectorIndex())));
  }

  static SearchQuery query(String text, int limit) {
    return new SearchQuery(Optional.empty(), text, limit);
  }

  static ReadRequest read(String documentId, int offset, int limit) {
    return new ReadRequest(Optional.empty(), documentId, offset, limit);
  }

  static RelatedQuery related(String documentId, int limit) {
    return new RelatedQuery(Optional.empty(), documentId, limit);
  }

  static AssembleQuery assemble(String text, int tokenBudget) {
    return new AssembleQuery(Optional.empty(), text, tokenBudget);
  }

  /** 정산 유닛 n개(문서마다 하나 — 문서당 상한에 걸리지 않는다). */
  void givenSettlementUnits(SpaceId space, int n) {
    fx.load(
        space,
        IntStream.rangeClosed(1, n)
            .mapToObj(i -> unit(space + "-" + i).inDocument(space + "-doc-" + i).content("정산 " + i))
            .map(b -> b.build())
            .toArray(ContextUnit[]::new));
  }

  @Nested
  @DisplayName("범위를 벗어난 질의를 받으면")
  class OutOfRange {
    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("io.scos.engine.ContextEngineTest#outOfRange")
    @DisplayName("InvalidQueryException이고 field()는 어긋난 인자 이름이다")
    void rejectsWithField(EngineCall call, String field) {
      // when
      Throwable thrown = catchThrowable(() -> call.on(engine()));

      // then
      assertThat(thrown)
          .asInstanceOf(type(InvalidQueryException.class))
          .extracting(InvalidQueryException::field)
          .isEqualTo(field);
    }
  }

  @Nested
  @DisplayName("공간에 문서가 없을 때")
  class MissingDocument {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.engine.ContextEngineTest#missingDocument")
    @DisplayName("DocumentNotFoundException이고 documentId()는 입력과 같다")
    void reportsDocumentId(EngineCall call) {
      // when
      Throwable thrown = catchThrowable(() -> call.on(engine()));

      // then
      assertThat(thrown)
          .asInstanceOf(type(DocumentNotFoundException.class))
          .extracting(DocumentNotFoundException::documentId)
          .isEqualTo(MISSING);
    }
  }

  @Nested
  @DisplayName("질의 임베딩이 503으로 실패할 때")
  class EmbeddingFails {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.engine.ContextEngineTest#embeddingQueries")
    @DisplayName("EmbeddingFailedException이 상태와 원인 예외를 싣는다")
    void carriesStatusAndCause(EngineCall call) {
      // when
      Throwable thrown = catchThrowable(() -> call.on(builder(new UnavailableEmbedder()).build()));

      // then
      assertThat(thrown)
          .asInstanceOf(type(EmbeddingFailedException.class))
          .extracting(EmbeddingFailedException::httpStatus, e -> e.getCause().getClass())
          .containsExactly(OptionalInt.of(UnavailableEmbedder.STATUS), EmbedderException.class);
    }
  }

  @Test
  @DisplayName("공간을 지정하지 않으면 defaultSpace의 유닛만 찾는다")
  void unspecifiedSpaceUsesDefaultSpace() {
    // given
    givenSettlementUnits(S, 1);
    givenSettlementUnits(OTHER, 1);
    ContextEngine engine = builder(ZERO).defaultSpace(OTHER).build();

    // when
    List<ContextHit> hits = engine.search(ALICE, SearchQuery.of("정산")).hits();

    // then
    assertThat(hits).extracting(ContextHit::unitId).containsExactly("other-1");
  }

  @Test
  @DisplayName("defaultSpace는 builder에 준 공간이다")
  void defaultSpaceIsBuilderSpace() {
    // when
    SpaceId space = builder(ZERO).defaultSpace(OTHER).build().defaultSpace();

    // then
    assertThat(space).isEqualTo(OTHER);
  }

  @Test
  @DisplayName("freshness는 그 공간에서 가장 늦게 수집한 시각을 돌려준다")
  void freshnessReadsGivenSpace() {
    // given
    givenSettlementUnits(OTHER, 1);

    // when
    Freshness freshness = engine().freshness(ALICE, OTHER);

    // then
    assertThat(freshness.latestCapturedAt()).contains(ContextUnits.DEFAULT_CAPTURED_AT);
  }

  @Test
  @DisplayName("search는 레시피 순서의 앞에서 limit개까지만 돌려준다")
  void searchCutsRecipeOrderAtLimit() {
    // given
    givenSettlementUnits(S, 7);
    List<String> recipeOrder =
        recipe().search(AccessScope.singleUser(), S, "정산").candidates().stream()
            .map(c -> c.unit().id())
            .toList();

    // when
    List<ContextHit> hits = engine().search(ALICE, query("정산", 3)).hits();

    // then
    assertThat(hits)
        .extracting(ContextHit::unitId)
        .containsExactlyElementsOf(recipeOrder.subList(0, 3));
  }

  @Test
  @DisplayName("재정렬을 지정하지 않으면 rerank는 SKIPPED다")
  void rerankIsSkippedWithoutReranker() {
    // given
    givenSettlementUnits(S, 2);

    // when
    RerankStatus rerank = engine().search(ALICE, SearchQuery.of("정산")).rerank();

    // then
    assertThat(rerank).isEqualTo(new RerankStatus(RerankStatus.Kind.SKIPPED, false));
  }

  @Test
  @DisplayName("재정렬을 지정하면 search의 rerank는 APPLIED다")
  void rerankIsAppliedWithReranker() {
    // given
    givenSettlementUnits(S, 2);

    // when
    RerankStatus rerank = rerankingEngine("2,1").search(ALICE, SearchQuery.of("정산")).rerank();

    // then
    assertThat(rerank).isEqualTo(new RerankStatus(RerankStatus.Kind.APPLIED, false));
  }

  @Nested
  @DisplayName("재정렬 응답이 형식에 맞지 않으면")
  class RerankFormatFallback {
    final LogCapture log = LogCapture.of(DefaultContextEngine.class);

    @AfterEach
    void close() {
      log.close();
    }

    @Test
    @DisplayName("search는 형식 폴백 경고를 남긴다")
    void warnsFormatFallback() {
      // given
      givenSettlementUnits(S, 2);

      // when
      rerankingEngine("가장 관련 있는 것은 2").search(ALICE, SearchQuery.of("정산"));

      // then
      assertThat(log.events("log"))
          .extracting(n -> n.path("level").asString(), ContextEngineTest::message)
          .contains(tuple("warn", "rerank fallback (invalid selection format)"));
    }
  }

  static String message(JsonNode event) {
    return event.path("detail").path("message").asString();
  }

  @Test
  @DisplayName("키워드 실행기를 지정하지 않으면 엔진이 소유한 데몬 스레드에서 키워드 채널을 돌린다")
  void ownedLexicalExecutorRunsOnDaemonThread() {
    // given
    givenSettlementUnits(S, 1);
    ThreadRecordingKeywords keywords = new ThreadRecordingKeywords();
    ContextEngine engine =
        new ContextEngineBuilder()
            .units(fx.units)
            .keywords(keywords)
            .vectors(fx.vectors)
            .embedder(ZERO)
            .build();

    // when — 새 스레드는 만든 스레드의 데몬 여부를 물려받으므로 데몬 아닌 스레드에서 부른다
    CompletableFuture<SearchResult> search =
        CompletableFuture.supplyAsync(
            () -> engine.search(ALICE, SearchQuery.of("정산")),
            Thread.ofPlatform().daemon(false)::start);

    // then
    assertThat(search).succeedsWithin(HANG_LIMIT);
    assertThat(keywords.searchedOn).extracting(Thread::isDaemon).isEqualTo(true);
  }

  @Test
  @DisplayName("search가 받은 principal은 검색 레시피의 접근 범위까지 간다")
  void principalReachesSearchRecipe() {
    // given
    givenSettlementUnits(S, 1);
    DefaultContextEngine engine = (DefaultContextEngine) engine();

    // when
    QuestionSearch.Result result = engine.searchRecipe(ALICE, SearchQuery.of("정산"));

    // then
    assertThat(result.access()).isEqualTo(AccessScope.unfiltered(ALICE));
  }

  @ParameterizedTest(name = "{0} 없음")
  @MethodSource("io.scos.engine.ContextEngineTest#missingPorts")
  @DisplayName("필수 포트 없이 build하면 그 이름의 IllegalStateException이다")
  void requiresPorts(String missing, ContextEngineBuilder builder) {
    // when
    Throwable thrown = catchThrowable(builder::build);

    // then
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(missing + " is required");
  }

  QuestionSearch recipe() {
    return new QuestionSearch(
        fx.units,
        fx.keywords,
        fx.vectors,
        ZERO,
        Reranker.DISABLED,
        SearchSettings.DEFAULT,
        Clock.systemUTC(),
        TestMetrics.search(),
        TestExecutors.CALLER_THREAD);
  }
}
