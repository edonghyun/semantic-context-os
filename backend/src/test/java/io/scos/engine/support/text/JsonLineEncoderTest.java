package io.scos.engine.support.text;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 데몬 JSON 로그 한 줄(TS source-sync-runtime JsonLineLogger — 운영 serve.log, status.sh가
 * `"level":"warn|error"`를 grep한다): `{"ts","level","event","sourceInstanceId"?,"runId"?,"detail"?}`.
 * 이벤트 로그(키-값 event)는 TS가 같은 엔트리에서 쓴 줄과 바이트 동등하다(harness/fixtures/ops/expected-log-lines.json). 이벤트가
 * 아닌 로그는 event "log"로 같은 모양을 지킨다.
 */
@DisplayName("데몬 JSON 로그 한 줄은 TS JsonLineLogger와 같은 모양이다")
class JsonLineEncoderTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path FIXTURES =
      Path.of(System.getProperty("scos.fixtures.ops.dir", "../harness/fixtures/ops"));
  static final LoggerContext CONTEXT = new LoggerContext();
  static final Logger LOGGER = CONTEXT.getLogger("io.scos.collection.internal.CollectionRuntime");

  /** JSON 값이 아닌 객체(문자열 표기 "PT3S"로 쓰인다). */
  static final Duration NOT_JSON_VALUE = Duration.ofSeconds(3);

  final JsonLineEncoder encoder = new JsonLineEncoder();

  static JsonNode golden() {
    try {
      return JSON.readTree(Files.readString(FIXTURES.resolve("expected-log-lines.json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static final Instant NOW = Instant.parse(golden().get("now").asString());

  static LoggingEvent event(Level level, String message, Throwable error) {
    LoggingEvent e = new LoggingEvent(Logger.FQCN, LOGGER, level, message, error, null);
    e.setInstant(NOW);
    return e;
  }

  /** TS 엔트리 → 같은 내용의 이벤트 로그(키-값 event·sourceInstanceId·runId·detail). */
  static LoggingEvent fromTsEntry(JsonNode entry) {
    LoggingEvent e =
        event(
            Level.toLevel(entry.get("level").asString().toUpperCase(Locale.ROOT)), "message", null);
    for (String key : new String[] {"event", "sourceInstanceId", "runId"})
      if (entry.has(key)) e.addKeyValuePair(new KeyValuePair(key, entry.get(key).asString()));
    if (entry.has("detail")) e.addKeyValuePair(new KeyValuePair("detail", entry.get("detail")));
    return e;
  }

  String encode(LoggingEvent e) {
    return new String(encoder.encode(e), StandardCharsets.UTF_8);
  }

  static Stream<Arguments> tsCases() {
    return StreamSupport.stream(golden().get("cases").spliterator(), false)
        .map(
            c -> Arguments.of(c.get("label").asString(), c.get("entry"), c.get("line").asString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tsCases")
  @DisplayName("이벤트 로그를 TS와 바이트 단위로 같은 JSON 한 줄로 쓴다")
  void writesTsLine(String label, JsonNode entry, String line) {
    // when
    String written = encode(fromTsEntry(entry));

    // then
    assertThat(written).isEqualTo(line + "\n");
  }

  @Test
  @DisplayName("detail은 Map이면 넣은 순서대로 쓴다")
  void writesMapDetailInOrder() {
    // given
    LoggingEvent e = event(Level.WARN, "m", null);
    e.addKeyValuePair(new KeyValuePair("event", "lease.heartbeat.slow"));
    e.addKeyValuePair(new KeyValuePair("detail", EventLog.detail("b", 1, "a", "x")));

    // when
    String written = encode(e);

    // then
    assertThat(written).endsWith("\"detail\":{\"b\":1,\"a\":\"x\"}}\n");
  }

  @Test
  @DisplayName("detail 값은 null·불리언·목록·그 밖의 객체(문자열)도 JSON으로 쓴다")
  void writesDetailValueKinds() {
    // given
    LoggingEvent e = event(Level.INFO, "m", null);
    e.addKeyValuePair(new KeyValuePair("event", "x"));
    e.addKeyValuePair(
        new KeyValuePair(
            "detail",
            EventLog.detail(
                "none", null, "flag", true, "ids", List.of("a", 2), "wait", NOT_JSON_VALUE)));

    // when
    String written = encode(e);

    // then
    assertThat(written)
        .endsWith(
            "\"detail\":{\"none\":null,\"flag\":true,\"ids\":[\"a\",2],\"wait\":\"PT3S\"}}\n");
  }

  @Test
  @DisplayName("메시지 없는 예외는 error.message를 null로 쓴다")
  void writesErrorWithoutMessage() {
    // when
    JsonNode line = JSON.readTree(encode(event(Level.ERROR, "m", new IllegalStateException())));

    // then
    assertThat(line.get("detail").get("error").get("message").isNull()).isTrue();
  }

  @Test
  @DisplayName("이벤트가 아닌 로그는 event \"log\"와 로거·메시지를 detail에 담는다")
  void writesPlainLogAsLogEvent() {
    // when
    String written = encode(event(Level.INFO, "daemon stopped cleanly", null));

    // then
    assertThat(written)
        .isEqualTo(
            "{\"ts\":\"2026-10-16T01:02:03.456Z\",\"level\":\"info\",\"event\":\"log\",\"detail\":"
                + "{\"logger\":\"io.scos.collection.internal.CollectionRuntime\",\"message\":\"daemon stopped cleanly\"}}\n");
  }

  @Test
  @DisplayName("예외는 detail.error.type에 예외 형식으로 남긴다")
  void writesError() {
    // when
    JsonNode line =
        JSON.readTree(encode(event(Level.ERROR, "loop died", new IllegalStateException("boom"))));

    // then
    assertThat(line.get("detail").get("error").get("type").asString())
        .isEqualTo("java.lang.IllegalStateException");
  }

  @Test
  @DisplayName("TRACE는 TS 수준 이름 debug로 쓴다")
  void mapsTraceToDebug() {
    // when
    JsonNode line = JSON.readTree(encode(event(Level.TRACE, "m", null)));

    // then
    assertThat(line.get("level").asString()).isEqualTo("debug");
  }

  @Test
  @DisplayName("MDC와 알 수 없는 키-값은 싣지 않는다(넘긴 detail만 — 비밀값이 섞여 들어갈 통로가 없다)")
  void writesOnlyKnownFields() {
    // given
    LoggingEvent e = event(Level.WARN, "m", null);
    e.setMDCPropertyMap(Map.of("authorization", "Bearer secret-token"));
    e.addKeyValuePair(new KeyValuePair("event", "collection.item.rejected"));
    e.addKeyValuePair(new KeyValuePair("token", "secret-token"));

    // when
    String written = encode(e);

    // then
    assertThat(written).doesNotContain("secret-token");
  }
}
