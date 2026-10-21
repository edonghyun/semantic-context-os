package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.scos.mcp.wire.Wire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 질의 로그(TS mcp-server `SCOS_QUERY_LOG`, JsonlQueryLogWriter): 검색 한 번에 JSONL 한 줄 `{ts, query,
 * topDocumentIds(≤5), topScore}`. 기대 줄은 TS SearchToolHandler가 같은 응답에서 쓴
 * 것이다(harness/fixtures/ops/expected-query-log.json) — 키 순서·수 표기·문자열 이스케이프까지 바이트 동등. 쓰기 실패는 검색을 막지
 * 않는다.
 */
@DisplayName("질의 로그는 검색마다 TS와 같은 JSONL 한 줄을 덧붙인다")
class QueryLogTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.ops.dir", "../harness/fixtures/ops"));

  static JsonNode golden() {
    try {
      return JSON.readTree(Files.readString(FIXTURES.resolve("expected-query-log.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final Instant NOW = Instant.parse(golden().get("now").asString());

  /** TS 응답 hits → Java 응답 hits(로그가 보는 문서 id·점수만 의미 있다). */
  static List<Wire.SearchHit> hits(JsonNode tsHits) {
    return StreamSupport.stream(tsHits.spliterator(), false)
        .map(
            h ->
                new Wire.SearchHit(
                    "t",
                    "c",
                    "slack",
                    h.get("documentId").asString(),
                    "",
                    h.get("score").asDouble()))
        .toList();
  }

  static Stream<Arguments> cases() {
    return StreamSupport.stream(golden().get("cases").spliterator(), false)
        .map(
            c ->
                Arguments.of(
                    c.get("label").asString(),
                    c.get("query").asString(),
                    hits(c.get("hits")),
                    c.get("line").asString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("검색 한 번을 TS와 바이트 단위로 같은 JSONL 한 줄로 쓴다")
  void writesTsLine(
      String label, String query, List<Wire.SearchHit> hits, String line, @TempDir Path dir)
      throws IOException {
    // given
    Path file = dir.resolve("queries.jsonl");

    // when
    new JsonlQueryLog(file, Clock.fixed(NOW, ZoneOffset.UTC)).append(query, hits);

    // then
    assertThat(Files.readString(file)).isEqualTo(line);
  }

  @Test
  @DisplayName("검색마다 줄을 덧붙인다(기존 내용을 지우지 않는다)")
  void appends(@TempDir Path dir) throws IOException {
    // given
    Path file = dir.resolve("queries.jsonl");
    JsonlQueryLog log = new JsonlQueryLog(file, Clock.fixed(NOW, ZoneOffset.UTC));

    // when
    log.append("첫 질의", List.of());
    log.append("둘째 질의", List.of());

    // then
    assertThat(Files.readAllLines(file)).hasSize(2);
  }

  @Test
  @DisplayName("쓸 수 없는 경로여도 예외를 던지지 않는다(검색 응답을 막지 않는다)")
  void writeFailureIsHarmless(@TempDir Path dir) {
    // given: 디렉터리에는 덧붙일 수 없다
    Path notAFile = dir;
    JsonlQueryLog log = new JsonlQueryLog(notAFile, Clock.fixed(NOW, ZoneOffset.UTC));

    // when·then
    assertThatCode(() -> log.append("질의", List.of())).doesNotThrowAnyException();
  }
}
