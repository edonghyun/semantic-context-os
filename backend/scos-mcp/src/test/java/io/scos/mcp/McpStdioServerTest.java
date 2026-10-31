package io.scos.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.engine.ContextEngineBuilder;
import io.scos.spi.embedding.Embedder;
import io.scos.spi.embedding.EmbedderIdentity;
import io.scos.spi.store.SpaceId;
import io.scos.store.memory.MemoryKeywordIndex;
import io.scos.store.memory.MemoryUnitMaintenance;
import io.scos.store.memory.MemoryUnitStore;
import io.scos.store.memory.MemoryVectorIndex;
import io.scos.support.TestExecutors;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * stdio 수명주기(SDK 전송 위): 클라이언트가 요청을 보내고 곧바로 stdin을 닫아도 읽은 요청의 응답을 다 쓴 뒤에야 EOF를 알린다. SDK 전송은 EOF를 읽는
 * 순간 닫힘 상태가 되어 아직 처리 중인 응답을 버리고, 조립 루트는 EOF 알림 직후 JVM을 끝낸다(McpCdsProcessTest 간헐 실패의 원인).
 */
@DisplayName("MCP stdio 서버는 stdin EOF를 읽은 요청에 다 답한 뒤에 알린다")
@Timeout(value = McpStdioServerTest.LIMIT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class McpStdioServerTest {
  static final String INITIALIZE =
      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
  static final String INITIALIZED =
      "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
  static final String SEARCH =
      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"search_context\",\"arguments\":{\"query\":\"정산\"}}}";
  static final ObjectMapper JSON = new ObjectMapper();

  /** 테스트 대기 상한(초) — hang 방지용이며 성공 근거가 아니다. */
  static final long LIMIT_SECONDS = 10;

  static final Duration WAIT_LIMIT = Duration.ofSeconds(LIMIT_SECONDS);

  /** 테스트 상한보다 긴 응답 대기 상한 — 이것까지 기다리면 테스트가 상한에 걸린다. */
  static final Duration LONGER_THAN_TEST_LIMIT = Duration.ofMinutes(1);

  /** 도구 처리보다 짧은 응답 대기 상한. */
  static final Duration SHORT_DRAIN_LIMIT = Duration.ofMillis(200);

  final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  final CountDownLatch stdinEofRead = new CountDownLatch(1);
  final CountDownLatch neverOpened = new CountDownLatch(1);
  McpStdioServer server;

  /** 주어진 줄을 \n으로 끝내 담은 stdin. */
  ByteArrayInputStream stdin(String... lines) {
    return stdinEndingWith("\n", lines);
  }

  /** 줄 사이는 \n, 마지막 줄 뒤는 lastTerminator인 stdin. 바닥 스트림이 EOF를 돌려준 순간을 알린다. */
  ByteArrayInputStream stdinEndingWith(String lastTerminator, String... lines) {
    byte[] bytes = (String.join("\n", lines) + lastTerminator).getBytes(StandardCharsets.UTF_8);
    return new ByteArrayInputStream(bytes) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        int n = super.read(b, off, len);
        if (n < 0) stdinEofRead.countDown();
        return n;
      }
    };
  }

  /** stdin EOF를 서버가 읽은 뒤에야 벡터를 돌려주는 임베더 — 도구 처리가 EOF보다 늦게 끝나는 상황. */
  Embedder answeringAfterStdinEof() {
    return answeringAfter(stdinEofRead);
  }

  /** gate가 열린 뒤에야(최대 WAIT_LIMIT) 영벡터를 돌려주는 임베더. */
  static Embedder answeringAfter(CountDownLatch gate) {
    return new Embedder() {
      @Override
      public EmbedderIdentity identity() {
        return new EmbedderIdentity("test", "after-eof", 2);
      }

      @Override
      public List<double[]> embed(List<String> texts) {
        try {
          gate.await(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return texts.stream().map(t -> new double[2]).toList();
      }
    };
  }

  McpTools tools(Embedder embedder) {
    var units = new MemoryUnitStore();
    var vectors = new MemoryVectorIndex();
    return new McpTools(
        new ContextEngineBuilder()
            .units(units)
            .unrestricted(new MemoryUnitMaintenance(units, vectors))
            .keywords(new MemoryKeywordIndex())
            .vectors(vectors)
            .embedder(embedder)
            .defaultSpace(new SpaceId("legacy"))
            .clock(Clock.systemUTC())
            .lexicalExecutor(TestExecutors.CALLER_THREAD)
            .build(),
        JSON,
        QueryLog.NONE);
  }

  List<Integer> answeredIds() {
    return stdout
        .toString(StandardCharsets.UTF_8)
        .lines()
        .map(line -> JSON.readTree(line).path("id"))
        .filter(id -> !id.isMissingNode())
        .map(id -> id.asInt())
        .toList();
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop();
  }

  @Test
  @DisplayName("요청 직후 stdin이 닫혀도 EOF 알림 전에 모든 요청의 응답이 stdout에 있다")
  void answersEveryRequestBeforeSignallingEof() throws Exception {
    // given
    server =
        new McpStdioServer(
            tools(answeringAfterStdinEof()),
            McpServerProfile.ORGANIZATION,
            stdin(INITIALIZE, INITIALIZED, SEARCH),
            stdout,
            WAIT_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(answeredIds()).containsExactlyInAnyOrder(1, 2);
  }

  @Test
  @Timeout(LIMIT_SECONDS)
  @DisplayName("답할 요청이 없으면 응답 대기 상한을 기다리지 않고 stdin EOF를 알린다")
  void signalsEofWhenNothingToAnswer() throws Exception {
    // given
    server =
        new McpStdioServer(
            tools(answeringAfterStdinEof()),
            McpServerProfile.ORGANIZATION,
            stdin(INITIALIZED),
            stdout,
            LONGER_THAN_TEST_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(answeredIds()).isEmpty();
  }

  @Test
  @Timeout(LIMIT_SECONDS)
  @DisplayName("응답이 대기 상한 안에 나오지 않으면 상한 뒤 stdin EOF를 알린다")
  void signalsEofAfterDrainLimit() throws Exception {
    // given
    server =
        new McpStdioServer(
            tools(answeringAfter(neverOpened)),
            McpServerProfile.ORGANIZATION,
            stdin(INITIALIZE, INITIALIZED, SEARCH),
            stdout,
            SHORT_DRAIN_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(answeredIds()).doesNotContain(2);
  }

  @ParameterizedTest(name = "마지막 줄 끝: {0}")
  @ValueSource(strings = {"", "\r", "\r\n"})
  @Timeout(LIMIT_SECONDS)
  @DisplayName("마지막 요청 줄이 \\n으로 끝나지 않아도 응답 대기 상한 전에 답한 뒤 stdin EOF를 알린다")
  void answersUnterminatedLastRequestBeforeSignallingEof(String lastTerminator) throws Exception {
    // given
    server =
        new McpStdioServer(
            tools(answeringAfterStdinEof()),
            McpServerProfile.ORGANIZATION,
            stdinEndingWith(lastTerminator, INITIALIZE, INITIALIZED, SEARCH),
            stdout,
            LONGER_THAN_TEST_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(answeredIds()).containsExactlyInAnyOrder(1, 2);
  }

  @ParameterizedTest(name = "마지막 줄 뒤: {0}")
  @ValueSource(strings = {"\n ", "\nx", "\n\r"})
  @Timeout(LIMIT_SECONDS)
  @DisplayName("마지막 줄이 요청이 아니면 읽은 요청에 답한 뒤 바로 stdin EOF를 알린다")
  void signalsEofAfterAnsweringWhenLastLineIsNotRequest(String tail) throws Exception {
    // given
    server =
        new McpStdioServer(
            tools(answeringAfterStdinEof()),
            McpServerProfile.ORGANIZATION,
            stdinEndingWith(tail, INITIALIZE, INITIALIZED, SEARCH),
            stdout,
            LONGER_THAN_TEST_LIMIT);

    // when
    server.start();
    server.awaitStdinEof();

    // then
    assertThat(answeredIds()).containsExactlyInAnyOrder(1, 2);
  }
}
