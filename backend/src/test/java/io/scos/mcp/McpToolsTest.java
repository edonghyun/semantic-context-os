package io.scos.mcp;

import static io.scos.spi.store.ContextUnits.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.scos.engine.ContextEngineBuilder;
import io.scos.mcp.wire.Wire;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.FixedEmbedder;
import io.scos.spi.store.ContextUnit;
import io.scos.spi.store.IndexedText;
import io.scos.spi.store.IndexedUnit;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.TestExecutors;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 도구 응답 매핑(CONTRACTS B·D·E·H): 키·null·반올림·발췌·출처·클램프·오류 문구. SDK 없이 메모리 조립. */
@DisplayName("MCP 도구 응답은 TS wire 모양을 따른다(CONTRACTS B·D·E·H)")
class McpToolsTest {
  static final SpaceId S = new SpaceId("legacy");

  /** 고정 시각(2026-10-13T00:00Z) 1시간 전 — freshness staleHours 1. */
  static final String CAPTURED_AT = "2026-10-12T23:00:00.000Z";

  /** CAPTURED_AT보다 이른 시각 — "최신"을 고르는지 가르는 fixture. */
  static final String EARLIER_CAPTURED_AT = "2026-10-12T20:00:00.000Z";

  /** 기본 유닛 2개의 토큰 수 — assemble_context 예산 사례가 합산한다. */
  static final int BASE_TOKENS = 5;

  static final ObjectMapper JSON = new ObjectMapper();
  MemoryUnitStore units = new MemoryUnitStore();
  MemoryKeywordIndex keywords = new MemoryKeywordIndex();
  MemoryVectorIndex vectors = new MemoryVectorIndex();
  McpTools tools;

  /** 도구 하나를 부르는 사례. {@code @MethodSource}는 static이라 사례는 도구를 받아 부르고, 테스트가 자기 인스턴스의 도구를 넘긴다. */
  interface ToolCall {
    String on(McpTools tools);
  }

  static final Embedder ZERO = FixedEmbedder.zero(2);

  void load(ContextUnit... list) {
    units.upsert(S, List.of(list));
    keywords.upsert(
        S,
        List.of(list).stream()
            .map(
                u ->
                    new IndexedText(
                        u.id(), u.documentId(), u.title(), u.content(), u.partition(), false))
            .toList());
    vectors.upsert(
        S,
        List.of(list).stream()
            .filter(u -> u.embedding().isPresent())
            .map(
                u ->
                    new IndexedUnit(
                        u.id(), u.documentId(), u.partition(), false, u.embedding().get()))
            .toList());
  }

  /** 기본 유닛 s-1(slack-doc, notion-doc을 링크한다). */
  static ContextUnit slackUnit(String capturedAt) {
    return unit("s-1")
        .inDocument("slack-doc")
        .titled("정산 배치")
        .content("정산 배치는 매일 자정에\n실행된다 " + "가".repeat(300))
        .uri("slack://C01/p1")
        .linksTo("notion-doc")
        .capturedAt(capturedAt)
        .tokens(BASE_TOKENS)
        .embedding(1, 0)
        .build();
  }

  @BeforeEach
  void setUp() {
    load(
        slackUnit(CAPTURED_AT),
        unit("n-1")
            .inDocument("notion-doc")
            .titled("정산 규정")
            .content("정산 규정 본문")
            .uri("https://www.notion.so/page-1")
            .capturedAt(CAPTURED_AT)
            .tokens(BASE_TOKENS)
            .embedding(1, 0)
            .build());
    Clock clock = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
    tools =
        new McpTools(
            new ContextEngineBuilder()
                .units(units)
                .keywords(keywords)
                .vectors(vectors)
                .embedder(ZERO)
                .defaultSpace(S)
                .clock(clock)
                .lexicalExecutor(TestExecutors.CALLER_THREAD)
                .build(),
            JSON,
            (query, hits) -> logged.add(new Logged(query, hits)));
  }

  /** 질의 로그에 넘긴 것(질의·응답 hits). */
  record Logged(String query, List<Wire.SearchHit> hits) {}

  final List<Logged> logged = new ArrayList<>();

  JsonNode search(Map<String, Object> args) {
    return JSON.readTree(tools.searchContext(args));
  }

  JsonNode read(Map<String, Object> args) {
    return JSON.readTree(tools.readDocument(args));
  }

  @Nested
  @DisplayName("search_context로 '정산'을 찾으면")
  class SearchContext {
    /** 키워드 동점(정산 2회)이지만 slack-doc이 notion-doc을 링크해 링크 확장(+1/66)으로 notion이 앞선다. */
    JsonNode hits() {
      return search(Map.of("query", "정산", "limit", 10)).get("hits");
    }

    @Test
    @DisplayName("후보마다 hit를 출처와 함께 점수 순으로 싣는다")
    void listsHitsWithSource() {
      // when
      JsonNode hits = hits();

      // then
      assertThat(hits)
          .extracting(h -> h.get("source").asString())
          .containsExactly("notion", "slack");
    }

    @Test
    @DisplayName("발췌는 개행을 지운 200자다")
    void excerptsTwoHundredChars() {
      // when
      JsonNode slack = hits().get(1);

      // then
      assertThat(slack.get("excerpt").asString()).hasSize(200).doesNotContain("\n");
    }

    @Test
    @DisplayName("점수는 소수 셋째 자리로 반올림한다")
    void roundsScores() {
      // when
      JsonNode hits = hits();

      // then: 키워드 동점(rank 0 → 1/61 ≈ 0.0164, rank 1 → 1/62) + notion의 링크 가산 1/66
      assertThat(hits).extracting(h -> h.get("score").asDouble()).containsExactly(0.031, 0.016);
    }

    @Test
    @DisplayName("질의와 응답 hits를 질의 로그에 한 번 넘긴다")
    void logsQueryWithResponseHits() {
      // when
      JsonNode response = hits();

      // then
      assertThat(logged)
          .singleElement()
          .satisfies(
              l -> {
                assertThat(l.query()).isEqualTo("정산");
                assertThat(l.hits())
                    .extracting(Wire.SearchHit::documentId)
                    .containsExactlyElementsOf(
                        StreamSupport.stream(response.spliterator(), false)
                            .map(h -> h.get("documentId").asString())
                            .toList());
              });
    }

    @Test
    @DisplayName("freshness는 최신 capturedAt과 경과 시간(1h, stale 아님)이다")
    void reportsFreshness() {
      // when
      JsonNode freshness = search(Map.of("query", "정산", "limit", 10)).get("freshness");

      // then
      assertThat(freshness.get("latestCapturedAt").asString()).isEqualTo(CAPTURED_AT);
      assertThat(freshness.get("staleHours").asDouble()).isEqualTo(1.0);
      assertThat(freshness.get("stale").asBoolean()).isFalse();
    }

    @Nested
    @DisplayName("두 유닛의 capturedAt이 다르면")
    class DistinctCapturedAt {
      @BeforeEach
      void givenSlackUnitEarlier() {
        load(slackUnit(EARLIER_CAPTURED_AT));
      }

      @Test
      @DisplayName("freshness의 latestCapturedAt은 그중 최신 시각이다")
      void reportsLatestCapturedAt() {
        // when
        JsonNode freshness = search(Map.of("query", "정산", "limit", 10)).get("freshness");

        // then
        assertThat(textFields(freshness, "latestCapturedAt")).containsExactly(CAPTURED_AT);
      }
    }
  }

  @Test
  @DisplayName("질의가 빈 문자열이라 입력 오류면 질의 로그에 남기지 않는다")
  void doesNotLogRejectedQuery() {
    // when
    Throwable rejected = catchThrowable(() -> search(Map.of("query", "")));

    // then
    if (!(rejected instanceof ToolArguments.Invalid))
      throw new IllegalStateException("given 준비 실패: 빈 질의는 입력 오류여야 한다 — " + rejected);
    assertThat(logged).isEmpty();
  }

  @Nested
  @DisplayName("후보가 11개인 공간에서 search_context를 부르면")
  class ElevenCandidates {
    /** 기본 유닛 2개 + 문서별 유닛 9개 = 후보 11개 — 상한 10이 관찰된다. */
    @BeforeEach
    void givenElevenCandidates() {
      for (int i = 1; i <= 9; i++)
        load(unit("x-" + i).inDocument("x-" + i).content("정산 후보 " + i).build());
    }

    @ParameterizedTest(name = "limit {0} → hits {1}")
    @CsvSource({"0, 1", "1, 1", "10, 10", "50, 10"})
    @DisplayName("limit는 1~10으로 클램프된다")
    void clampsLimit(int limit, int hits) {
      // when
      JsonNode r = search(Map.of("query", "정산", "limit", limit));

      // then
      assertThat(r.get("hits").size()).isEqualTo(hits);
    }

    @Test
    @DisplayName("limit를 주지 않으면 기본 5개다")
    void defaultLimitIsFive() {
      // when
      JsonNode r = search(Map.of("query", "정산"));

      // then
      assertThat(r.get("hits").size()).isEqualTo(5);
    }
  }

  static Stream<Arguments> invalidArguments() {
    return Stream.of(
        Arguments.of(
            Named.of("search_context 빈 질의", (ToolCall) t -> t.searchContext(Map.of("query", ""))),
            "MCP error -32602: Input validation error: Invalid arguments for tool search_context: Too small: expected string to have >=1 characters at query"),
        Arguments.of(
            Named.of(
                "search_context limit 타입 오류",
                (ToolCall) t -> t.searchContext(Map.of("query", "정산", "limit", "x"))),
            "Invalid input: expected number, received string at limit"),
        Arguments.of(
            Named.of("read_document documentId 누락", (ToolCall) t -> t.readDocument(Map.of())),
            "expected string, received undefined at documentId"),
        Arguments.of(
            Named.of(
                "read_document limit 범위 밖",
                (ToolCall) t -> t.readDocument(Map.of("documentId", "slack-doc", "limit", 1000))),
            "Too big: expected number to be <=100 at limit"),
        Arguments.of(
            Named.of(
                "read_document offset -1",
                (ToolCall) t -> t.readDocument(Map.of("documentId", "slack-doc", "offset", -1))),
            "Too small: expected number to be >=0 at offset"),
        Arguments.of(
            Named.of("assemble_context query 누락", (ToolCall) t -> t.assembleContext(Map.of())),
            "expected string, received undefined at query"));
  }

  static Stream<Arguments> wrongTypes() {
    return Stream.of(
        Arguments.of(Named.of("boolean", true), "received boolean at limit"),
        Arguments.of(Named.of("object", Map.of("a", 1)), "received object at limit"),
        Arguments.of(Named.of("array", List.of(1)), "received array at limit"),
        Arguments.of(Named.of("소수", 1.5), "received number at limit"));
  }

  @Nested
  @DisplayName("인자가 도구 스키마에 맞지 않으면")
  class InvalidArguments {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.scos.mcp.McpToolsTest#invalidArguments")
    @DisplayName("빈 값·타입 오류·누락·범위 밖은 TS zod 문구의 검증 오류다")
    void rejectsInvalidArguments(ToolCall call, String message) {
      // when·then
      assertThatThrownBy(() -> call.on(tools))
          .isInstanceOf(ToolArguments.Invalid.class)
          .hasMessageContaining(message);
    }

    @ParameterizedTest(name = "limit = {0}")
    @MethodSource("io.scos.mcp.McpToolsTest#wrongTypes")
    @DisplayName("숫자 인자에 boolean·object·array·소수가 오면 타입 이름을 붙인 검증 오류다")
    void numberArgumentTypeNames(Object value, String message) {
      // when·then
      assertThatThrownBy(() -> tools.searchContext(Map.of("query", "정산", "limit", value)))
          .isInstanceOf(ToolArguments.Invalid.class)
          .hasMessageContaining(message);
    }
  }

  @Nested
  @DisplayName("read_document로 문서를 읽으면")
  class ReadDocument {
    @Test
    @DisplayName("문서 제목·출처와 유닛을 싣는다")
    void returnsDocument() {
      // when
      JsonNode r = read(Map.of("documentId", "slack-doc"));

      // then
      assertThat(r.get("title").asString()).isEqualTo("정산 배치");
      assertThat(r.get("source").asString()).isEqualTo("slack");
      assertThat(r.get("units").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("다음 페이지가 없으면 nextOffset 키를 null로 싣는다")
    void nextOffsetIsNull() {
      // when
      JsonNode r = read(Map.of("documentId", "slack-doc"));

      // then
      assertThat(r.has("nextOffset")).isTrue();
      assertThat(r.get("nextOffset").isNull()).isTrue();
    }

    @Test
    @DisplayName("문서 끝을 넘는 offset은 빈 유닛과 null nextOffset이다")
    void beyondEndIsEmpty() {
      // when
      JsonNode beyond = read(Map.of("documentId", "slack-doc", "offset", 100));

      // then
      assertThat(beyond.get("units").size()).isZero();
      assertThat(beyond.get("nextOffset").isNull()).isTrue();
    }

    @Test
    @DisplayName("없는 문서는 '문서를 찾을 수 없음' ToolError다")
    void missingDocumentIsToolError() {
      // when·then
      assertThatThrownBy(() -> tools.readDocument(Map.of("documentId", "missing-doc")))
          .isInstanceOf(McpTools.ToolError.class)
          .hasMessage("문서를 찾을 수 없음: missing-doc");
    }

    @ParameterizedTest(name = "limit {0}")
    @CsvSource({"1", "100"})
    @DisplayName("limit는 1과 100(경계)을 허용한다")
    void readDocumentLimitBoundsInclusive(int limit) {
      // when
      JsonNode r = read(Map.of("documentId", "slack-doc", "limit", limit));

      // then
      assertThat(r.get("units").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("offset 0은 허용된다")
    void readDocumentOffsetZero() {
      // when
      JsonNode r = read(Map.of("documentId", "slack-doc", "offset", 0));

      // then
      assertThat(r.get("units").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("유닛은 title·content를 그대로 싣는다")
    void readDocumentUnitFields() {
      // when
      JsonNode unit = read(Map.of("documentId", "notion-doc")).get("units").get(0);

      // then
      assertThat(unit.get("title").asString()).isEqualTo("정산 규정");
      assertThat(unit.get("content").asString()).isEqualTo("정산 규정 본문");
    }
  }

  @Nested
  @DisplayName("related_documents로 slack-doc의 관련 문서를 찾으면")
  class RelatedDocumentsTool {
    JsonNode first() {
      return JSON.readTree(tools.relatedDocuments(Map.of("documentId", "slack-doc", "limit", 3)))
          .get("related")
          .get(0);
    }

    @Test
    @DisplayName("관련 문서의 id·제목·출처·via를 싣는다")
    void describesRelatedDocument() {
      // when
      JsonNode first = first();

      // then
      assertThat(textFields(first, "documentId", "title", "source", "via"))
          .containsExactly("notion-doc", "정산 규정", "notion", "both");
    }

    @Test
    @DisplayName("점수는 소수 셋째 자리로 반올림한다")
    void roundsScore() {
      // when
      JsonNode first = first();

      // then
      assertThat(first.get("score").asDouble()).isEqualTo(0.033);
    }

    @Test
    @DisplayName("없는 문서는 ToolError다")
    void missingDocumentIsToolError() {
      // when·then
      assertThatThrownBy(() -> tools.relatedDocuments(Map.of("documentId", "nope")))
          .isInstanceOf(McpTools.ToolError.class);
    }
  }

  @Nested
  @DisplayName("assemble_context로 '정산'을 조립하면")
  class AssembleContext {
    JsonNode assemble() {
      return JSON.readTree(tools.assembleContext(Map.of("query", "정산")));
    }

    @Test
    @DisplayName("찾은 유닛을 모두 싣는다")
    void listsUnits() {
      // when
      JsonNode r = assemble();

      // then
      assertThat(r.get("units").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("유닛마다 그 유닛의 제목과 본문을 싣는다")
    void unitCarriesTitleAndContent() {
      // when
      JsonNode notion = unitOf(assemble(), "notion-doc");

      // then
      assertThat(textFields(notion, "title", "content")).containsExactly("정산 규정", "정산 규정 본문");
    }

    @Test
    @DisplayName("유닛마다 자기 문서 id와 그 문서의 출처를 짝으로 싣는다")
    void unitCarriesSourceAndDocument() {
      // when
      JsonNode units = assemble().get("units");

      // then
      assertThat(
              StreamSupport.stream(units.spliterator(), false)
                  .map(u -> u.get("documentId").asString() + " ← " + u.get("source").asString())
                  .toList())
          .containsExactlyInAnyOrder("slack-doc ← slack", "notion-doc ← notion");
    }

    @Test
    @DisplayName("tokensUsed는 담긴 유닛 토큰 합이다")
    void reportsBudgetUse() {
      // when
      JsonNode r = assemble();

      // then
      assertThat(r.get("tokensUsed").asInt()).isEqualTo(2 * BASE_TOKENS);
    }

    @Test
    @DisplayName("다 담겼으면 truncated가 아니다")
    void notTruncatedWhenAllFit() {
      // when
      JsonNode r = assemble();

      // then
      assertThat(r.get("truncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("freshness는 stale이 아니다")
    void reportsFreshFreshness() {
      // when
      JsonNode freshness = assemble().get("freshness");

      // then
      assertThat(freshness.get("stale").asBoolean()).isFalse();
    }

    @Nested
    @DisplayName("두 유닛의 capturedAt이 다르면")
    class DistinctCapturedAt {
      @BeforeEach
      void givenSlackUnitEarlier() {
        load(slackUnit(EARLIER_CAPTURED_AT));
      }

      @Test
      @DisplayName("freshness의 latestCapturedAt은 그중 최신 시각이다")
      void reportsLatestCapturedAt() {
        // when
        JsonNode freshness = assemble().get("freshness");

        // then
        assertThat(textFields(freshness, "latestCapturedAt")).containsExactly(CAPTURED_AT);
      }
    }
  }

  /**
   * 클램프 경계를 관찰 가능한 결과로: 기본 유닛 2개(5+5) 위에 300·300·4500·4500 토큰 유닛을 두면 유효 예산 500은 300 하나까지(310),
   * 8000은 300 둘 + 4500 하나까지(5110) 담고 나머지를 건너뛴다. 클램프가 없다면 10은 기본 유닛만(10), 90000은 전부(9610) 담긴다.
   */
  @ParameterizedTest(name = "tokenBudget {0} → 유효 예산 {1}, tokensUsed {2}")
  @CsvSource({"10, 500, 310", "90000, 8000, 5110"})
  @DisplayName("tokenBudget은 500..8000으로 클램프된 예산까지만 채운다")
  void clampsTokenBudget(int requested, int effective, int expectedUsed) {
    // given
    load(
        unit("big-1").inDocument("big-1").content("정산 300").tokens(300).build(),
        unit("big-2").inDocument("big-2").content("정산 300").tokens(300).build(),
        unit("huge-1").inDocument("huge-1").content("정산 4500").tokens(4500).build(),
        unit("huge-2").inDocument("huge-2").content("정산 4500").tokens(4500).build());

    // when
    JsonNode r =
        JSON.readTree(tools.assembleContext(Map.of("query", "정산", "tokenBudget", requested)));

    // then
    assertThat(r.get("tokensUsed").asInt()).isEqualTo(expectedUsed).isLessThanOrEqualTo(effective);
    assertThat(r.get("truncated").asBoolean()).isTrue();
  }

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "slack://C01/p1, slack",
    "https://www.notion.so/x, notion",
    "notion://page, notion",
    "https://team.atlassian.net/browse/OPS-1, jira",
    "https://docs.google.com/d/1, google-drive",
    "https://docs.example.test/a.md, unknown",
    "fixture://x, unknown",
    "not a uri, unknown",
    "jira://PROJ-1, jira",
    "gdrive://file-1, google-drive",
    "relative/path, unknown",
    "https://team.atlassian.net, jira",
    "https://team.atlassian.net/, jira",
    "https://team.atlassian.net/jira/software, jira",
    "https://team.atlassian.net/wiki/x, unknown",
    "https://drive.google.com/file/d/1, google-drive",
    "http://docs.google.com/d/1, unknown",
    "http://www.notion.so/x, notion",
    "https://notion.site/x, notion",
    "ftp://notion.so/x, unknown",
    "https://notnotion.so/x, unknown"
  })
  @DisplayName("출처 분류는 TS classifySource와 같다")
  void sourceKinds(String uri, String wire) {
    // when
    String kind = SourceKind.of(uri).wire();

    // then
    assertThat(kind).isEqualTo(wire);
  }

  @Nested
  @DisplayName("발췌를 만들면")
  class Excerpt {
    static final String EXACT = "가".repeat(200);

    @Test
    @DisplayName("정확히 200 UTF-16 단위면 그대로다")
    void excerptAtLimitIsKept() {
      // when
      String excerpt = McpTools.excerpt(EXACT);

      // then
      assertThat(excerpt).isEqualTo(EXACT);
    }

    @Test
    @DisplayName("201 UTF-16 단위면 200으로 자른다")
    void excerptOverLimitIsCut() {
      // when
      String excerpt = McpTools.excerpt(EXACT + "나");

      // then
      assertThat(excerpt).isEqualTo(EXACT);
    }

    @Test
    @DisplayName("개행(CRLF·LF)을 공백으로 바꾼다")
    void excerptReplacesNewlines() {
      // when
      String excerpt = McpTools.excerpt("a\r\nb\nc");

      // then
      assertThat(excerpt).isEqualTo("a b c");
    }
  }

  /** assemble_context 응답에서 그 문서의 유닛(정확히 하나가 아니면 전제 실패). */
  static JsonNode unitOf(JsonNode assembled, String documentId) {
    List<JsonNode> found =
        StreamSupport.stream(assembled.get("units").spliterator(), false)
            .filter(u -> u.get("documentId").asString().equals(documentId))
            .toList();
    if (found.size() != 1)
      throw new IllegalStateException("given 준비 실패: " + documentId + " 유닛이 하나가 아니다 — " + found);
    return found.getFirst();
  }

  /** 객체 노드의 문자열 필드들을 순서대로. */
  static List<String> textFields(JsonNode node, String... names) {
    return Arrays.stream(names).map(n -> node.get(n).asString()).toList();
  }
}
