package io.scos.harness.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** fixture 파일이 언어 중립 형식을 지키는지 + Java 플랫폼이 TS 기대값과 같은 값을 내는 F-2·F-3·F-4(오프셋 있는 날짜)·F-7 검증. */
@DisplayName("fixture는 언어 중립 형식이고 Java 산술은 TS 기대값과 같다(ADR-0016)")
class FixtureSchemaTest {
  private static final ObjectMapper M = new ObjectMapper();
  private static final Path DIR =
      Path.of(System.getProperty("harness.root", "."), "fixtures", "p1");

  /** fixture 규모 하한 — 이보다 작으면 비교가 공허해진다. */
  static final int MIN_UNITS = 40;

  static final int MIN_CALLS = 30;

  /** F-7 Math.pow: JS(V8)와 Java 사이 ULP 차이 허용 — 상대 오차. */
  static final double POW_RELATIVE_TOLERANCE = 1e-12;

  static final int RRF_K = 60;
  static final int ROUND_TO = 1000;

  final FixtureSpec fixture = load();

  static FixtureSpec load() {
    try {
      return FixtureSpec.load(DIR);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<JsonNode> list(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).toList();
  }

  @Nested
  @DisplayName("documents.json의 유닛을 보면")
  class Documents {
    List<String> ids() {
      return list(fixture.units()).stream().map(u -> u.get("id").asText()).toList();
    }

    @Test
    @DisplayName("유닛 id가 유일하다")
    void idsAreUnique() {
      // when·then
      assertThat(ids()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("모든 임베딩의 차원이 선언과 같다")
    void embeddingDimensionsMatch() {
      // when·then
      assertThat(list(fixture.units()))
          .allSatisfy(u -> assertThat(u.get("embedding").size()).isEqualTo(fixture.embeddingDim()));
    }

    @Test
    @DisplayName("tier는 raw·compacted·archived 중 하나다")
    void tiersAreKnown() {
      // when·then
      assertThat(list(fixture.units()))
          .extracting(u -> u.get("tier").asText())
          .allMatch(t -> List.of("raw", "compacted", "archived").contains(t));
    }

    @Test
    @DisplayName("partition은 알려진 네 값 중 하나다")
    void partitionsAreKnown() {
      // when·then
      assertThat(list(fixture.units()))
          .extracting(u -> u.get("partition").asText())
          .allMatch(p -> List.of("/knowledge", "/rules", "/environment", "/memory").contains(p));
    }

    @Test
    @DisplayName("동일 임베딩 쌍(fx-dup-a-1·fx-dup-b-1)이 있다")
    void hasDuplicateEmbeddingPair() {
      // when
      JsonNode a = embeddingOf("fx-dup-a-1");

      // then
      assertThat(a).isNotNull().isEqualTo(embeddingOf("fx-dup-b-1"));
    }

    @Test
    @DisplayName("유닛이 비교에 충분할 만큼 있다")
    void hasEnoughUnits() {
      // when·then
      assertThat(fixture.unitCount()).isGreaterThan(MIN_UNITS);
    }

    @Test
    @DisplayName("호출이 비교에 충분할 만큼 있다")
    void hasEnoughCalls() {
      // when·then
      assertThat(fixture.calls()).hasSizeGreaterThan(MIN_CALLS);
    }

    JsonNode embeddingOf(String id) {
      return list(fixture.units()).stream()
          .filter(u -> u.get("id").asText().equals(id))
          .findFirst()
          .map(u -> u.get("embedding"))
          .orElse(null);
    }
  }

  /** 임베딩되는 search/assemble 질의와 그 cassette embed 항목(없으면 null). */
  Map<String, JsonNode> givenEmbeddedQueries() throws Exception {
    JsonNode embed = M.readTree(Files.readString(fixture.cassettePath())).get("embed");
    Map<String, JsonNode> byCall = new LinkedHashMap<>();
    for (FixtureSpec.Call c : fixture.calls())
      if (embedsQuery(c)) byCall.put(c.name(), embed.get(sha256(c.args().get("query").asText())));
    if (byCall.isEmpty()) throw new IllegalStateException("given 준비 실패: 임베딩되는 질의가 없다(0이면 공허한 통과)");
    return byCall;
  }

  @Nested
  @DisplayName("임베딩되는 질의를 cassette.json에서 찾으면")
  class Cassette {
    Map<String, JsonNode> queries;

    @BeforeEach
    void givenQueries() throws Exception {
      queries = givenEmbeddedQueries();
    }

    @Test
    @DisplayName("모든 질의의 embed 항목이 있다")
    void cassetteCoversQueries() {
      // then
      assertThat(queries).doesNotContainValue(null);
    }

    @Test
    @DisplayName("embed 항목은 모두 fixture 임베딩 차원이다")
    void cassetteEmbeddingsHaveFixtureDim() {
      // then
      assertThat(queries.values()).extracting(JsonNode::size).containsOnly(fixture.embeddingDim());
    }
  }

  /** 스키마를 통과해 실제로 질의를 임베딩하는 호출(문자열 질의, 숫자 limit·tokenBudget). */
  static boolean embedsQuery(FixtureSpec.Call c) {
    if (!c.tool().equals("search_context") && !c.tool().equals("assemble_context")) return false;
    JsonNode q = c.args().get("query");
    if (q == null || !q.isTextual() || q.asText().isEmpty()) return false;
    if (c.args().has("limit") && !c.args().get("limit").isNumber()) return false;
    return !c.args().has("tokenBudget") || c.args().get("tokenBudget").isNumber();
  }

  /**
   * 플랫폼 산술(제품 코드 없음): fixture의 기대값을 Java 표준 라이브러리로 다시 계산해 TS(V8)와 같은지 본다. 제품 함수가 fixture를 지키는지는
   * backend의 TextDeterminismTest·SearchDeterminismTest·WireDeterminismTest가 본다.
   */
  @Nested
  @DisplayName("determinism.json 사례를 Java 표준 라이브러리로 다시 계산하면")
  class PlatformArithmetic {
    final JsonNode d = read();

    JsonNode read() {
      try {
        return M.readTree(Files.readString(DIR.resolve("determinism.json")));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    /** 사례마다 (기대, 실제)가 다른 것만 모은다. 사례가 0개면 given 준비 실패. */
    List<String> mismatches(
        JsonNode cases, Function<JsonNode, Object> expected, Function<JsonNode, Object> actual) {
      List<JsonNode> all = list(cases);
      if (all.isEmpty()) throw new IllegalStateException("given 준비 실패: fixture 사례가 없다(0이면 공허한 통과)");
      List<String> out = new ArrayList<>();
      for (JsonNode c : all)
        if (!expected.apply(c).equals(actual.apply(c))) out.add(c + " → " + actual.apply(c));
      return out;
    }

    @Test
    @DisplayName("UTF-16 길이가 TS 값과 같다(F-2)")
    void utf16Length() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F2_lengths"),
                  c -> c.get("utf16").asInt(),
                  c -> c.get("input").asText().length()))
          .isEmpty();
    }

    @Test
    @DisplayName("코드 포인트 수가 TS 값과 같다(F-2)")
    void codePointCount() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F2_lengths"),
                  c -> c.get("codePoints").asInt(),
                  FixtureSchemaTest::codePointsOfInput))
          .isEmpty();
    }

    @Test
    @DisplayName("UTF-8 바이트 수가 TS 값과 같다(F-2)")
    void utf8Bytes() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F2_lengths"),
                  c -> c.get("utf8Bytes").asInt(),
                  c -> c.get("input").asText().getBytes(StandardCharsets.UTF_8).length))
          .isEmpty();
    }

    @Test
    @DisplayName("sha256(utf8) hex가 TS 값과 같다(F-3)")
    void sha256Hex() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F3_sha256"),
                  c -> c.get("sha256").asText(),
                  c -> sha256(c.get("input").asText())))
          .isEmpty();
    }

    @Test
    @DisplayName("시간대가 있는 날짜의 Instant.parse가 TS 값과 같다(F-4)")
    void instantParse() {
      // given: 무효·시간대 없는 값은 Java가 거부하므로 제외한다
      List<JsonNode> parseable =
          list(d.get("F4_dates")).stream()
              .filter(c -> !c.hasNonNull("note") && !c.get("parseMs").isNull())
              .toList();

      if (parseable.isEmpty()) throw new IllegalStateException("given 준비 실패: 검사할 날짜 사례가 없다");

      // when·then
      assertThat(parseable)
          .allSatisfy(
              c ->
                  assertThat(Instant.parse(c.get("input").asText()).toEpochMilli())
                      .isEqualTo(c.get("parseMs").asLong()));
    }

    @Test
    @DisplayName("RRF weight / (60 + rank + 1)이 TS 값과 같다(F-7)")
    void rrf() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F7_rrf").get("values"),
                  c -> c.get("value").asDouble(),
                  c -> c.get("weight").asDouble() / (RRF_K + c.get("rank").asInt() + 1)))
          .isEmpty();
    }

    @Test
    @DisplayName("소수 셋째 자리 반올림이 TS 값과 같다(F-7)")
    void round3() {
      // when·then
      assertThat(
              mismatches(
                  d.get("F7_rounding"),
                  c -> c.get("rounded").asDouble(),
                  c -> Math.round(c.get("input").asDouble() * ROUND_TO) / (double) ROUND_TO))
          .isEmpty();
    }

    @Test
    @DisplayName("날짜 가중 1 + 0.1·0.99^days는 상대 오차 1e-12 안이다(F-7)")
    void freshnessPow() {
      // given
      List<JsonNode> cases = list(d.get("F7_pow"));
      if (cases.isEmpty()) throw new IllegalStateException("given 준비 실패: fixture 사례가 없다");

      // when·then
      assertThat(cases)
          .extracting(FixtureSchemaTest::freshnessRelativeError)
          .allMatch(error -> error <= POW_RELATIVE_TOLERANCE);
    }
  }

  static int codePointsOfInput(JsonNode c) {
    String s = c.get("input").asText();
    return s.codePointCount(0, s.length());
  }

  /** F-7 사례의 JS 값과 Java 계산 1 + 0.1·0.99^days의 상대 오차. */
  static double freshnessRelativeError(JsonNode c) {
    double expected = c.get("value").asDouble();
    double actual = 1 + 0.1 * Math.pow(0.99, c.get("days").asDouble());
    return Math.abs(actual - expected) / expected;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
