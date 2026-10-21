package io.scos.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import io.scos.harness.testing.FakeServerLauncher;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 하네스가 소유하는 수명주기: 시작·readiness·호출·정상 종료·재시작·강제 종료·타임아웃. 가짜 서버로 검증한다. */
@DisplayName("stdio 클라이언트는 서버 수명주기를 소유하고 실패를 숨기지 않는다(ADR-0016)")
class JsonRpcStdioClientTest {
  static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

  /** 강제 종료·타임아웃 사례가 빨리 끝나도록 짧은 상한. */
  static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);

  static final int INVALID_PARAMS = -32602;

  @TempDir Path tmp;
  JsonRpcStdioClient client;

  /** 가짜 서버(환경변수로 행동 선택)에 붙은 클라이언트. 테스트가 끝나면 닫는다. */
  JsonRpcStdioClient givenClient(Map<String, String> fakeBehavior, Duration timeout) {
    client = new JsonRpcStdioClient(FakeServerLauncher.launch(tmp, fakeBehavior), timeout);
    return client;
  }

  JsonRpcStdioClient givenStartedClient(Map<String, String> fakeBehavior, Duration timeout)
      throws Exception {
    givenClient(fakeBehavior, timeout).start();
    return client;
  }

  @AfterEach
  void close() {
    if (client != null) client.close();
  }

  JsonRpcStdioClient givenStoppedClient() throws Exception {
    JsonRpcStdioClient c = givenStartedClient(Map.of(), CALL_TIMEOUT);
    c.stop();
    return c;
  }

  @Nested
  @DisplayName("정상 가짜 서버로 start하면")
  class Starting {
    @BeforeEach
    void givenNotStarted() {
      givenClient(Map.of(), CALL_TIMEOUT);
    }

    @Test
    @DisplayName("start는 initialize 응답을 돌려준다")
    void startReturnsInitializeResult() throws Exception {
      // when
      JsonNode init = client.start();

      // then
      assertThat(init.path("serverInfo").path("name").asText()).isEqualTo("fake");
    }

    @Test
    @DisplayName("start 뒤 프로세스는 살아 있다")
    void aliveAfterStart() throws Exception {
      // when
      client.start();

      // then
      assertThat(client.isAlive()).isTrue();
    }

    @Test
    @DisplayName("서버 stderr는 작업 디렉터리의 로그 파일로 간다")
    void routesStderrToLog() throws Exception {
      // when
      client.start();

      // then
      assertThat(tmp.resolve("fake-stderr.log")).exists();
    }
  }

  @Nested
  @DisplayName("시작한 클라이언트에서")
  class Started {
    @BeforeEach
    void givenStarted() throws Exception {
      givenStartedClient(Map.of(), CALL_TIMEOUT);
    }

    @Test
    @DisplayName("tools/call은 서버의 result를 가공 없이 돌려준다")
    void callToolReturnsResult() throws Exception {
      // when
      JsonNode r =
          client.callTool("echo", JsonRpcStdioClient.mapper().createObjectNode().put("q", "결제"));

      // then
      assertThat(r.path("content").get(0).path("text").asText()).contains("\"q\":\"결제\"");
    }

    @Test
    @DisplayName("tools/list는 tools 배열을 돌려준다")
    void listsTools() throws Exception {
      // when
      JsonNode tools = client.listTools();

      // then
      assertThat(tools.path("tools").isArray()).isTrue();
    }

    @Test
    @DisplayName("stdin EOF로 정상 종료한다(exit 0, forced=false)")
    void stopsGracefully() throws Exception {
      // when
      JsonRpcStdioClient.Shutdown s = client.stop();

      // then
      assertThat(s)
          .extracting(JsonRpcStdioClient.Shutdown::exitCode, JsonRpcStdioClient.Shutdown::forced)
          .containsExactly(0, false);
    }

    @Test
    @DisplayName("정상 종료 뒤 프로세스는 살아 있지 않다")
    void notAliveAfterStop() throws Exception {
      // when
      client.stop();

      // then
      assertThat(client.isAlive()).isFalse();
    }
  }

  @Nested
  @DisplayName("정상 종료한 클라이언트에서")
  class Stopped {
    @BeforeEach
    void givenStopped() throws Exception {
      givenStoppedClient();
    }

    @Test
    @DisplayName("같은 클라이언트로 다시 start할 수 있다")
    void restartsAfterStop() throws Exception {
      // when
      JsonNode init = client.start();

      // then
      assertThat(init.path("serverInfo").path("name").asText()).isEqualTo("fake");
    }

    @Test
    @DisplayName("다시 시작한 프로세스도 정상 종료한다")
    void restartedProcessStopsGracefully() throws Exception {
      // given
      client.start();

      // when
      JsonRpcStdioClient.Shutdown again = client.stop();

      // then
      assertThat(again.exitCode()).isZero();
    }
  }

  @Nested
  @DisplayName("EOF를 무시하는 서버를 stop하면")
  class IgnoresEof {
    @BeforeEach
    void givenStubbornServer() throws Exception {
      givenStartedClient(Map.of("FAKE_IGNORE_EOF", "1"), SHORT_TIMEOUT);
    }

    @Test
    @DisplayName("종료는 forced=true로 보고된다")
    void forcedShutdown() throws Exception {
      // when
      JsonRpcStdioClient.Shutdown s = client.stop();

      // then
      assertThat(s.forced()).isTrue();
    }

    @Test
    @DisplayName("서버는 강제로 종료된다")
    void forcedShutdownKills() throws Exception {
      // when
      client.stop();

      // then
      assertThat(client.isAlive()).isFalse();
    }
  }

  @Nested
  @DisplayName("응답하지 않는 서버를 start하면")
  class Hangs {
    @BeforeEach
    void givenHangingServer() {
      givenClient(Map.of("FAKE_HANG", "1"), SHORT_TIMEOUT);
    }

    @Test
    @DisplayName("시간 상한에서 예외로 드러낸다(대기·재시도로 숨기지 않음)")
    void timeoutFails() {
      // when·then
      assertThatThrownBy(client::start)
          .isInstanceOf(JsonRpcStdioClient.ProtocolException.class)
          .hasMessageContaining("no response within");
    }

    @Test
    @DisplayName("시간 상한에서 프로세스를 죽인다")
    void timeoutKills() {
      // when
      Throwable failure = catchThrowable(client::start);

      // then
      if (!(failure instanceof JsonRpcStdioClient.ProtocolException))
        throw new IllegalStateException("given 준비 실패: 시간 상한 예외여야 한다 — " + failure);
      assertThat(client.isAlive()).isFalse();
    }
  }

  @Test
  @DisplayName("JSON-RPC error 응답은 예외가 아니라 jsonrpcError로 감싸 돌려준다")
  void wrapsJsonRpcError() throws Exception {
    // given
    JsonRpcStdioClient c = givenStartedClient(Map.of("FAKE_CALL_ERROR", "1"), CALL_TIMEOUT);

    // when
    JsonNode r = c.callTool("echo", JsonRpcStdioClient.mapper().createObjectNode());

    // then
    assertThat(r.path("jsonrpcError").path("code").asInt()).isEqualTo(INVALID_PARAMS);
    assertThat(r.path("jsonrpcError").path("message").asText()).isEqualTo("invalid params (fake)");
    assertThat(r.has("content")).isFalse();
  }
}
