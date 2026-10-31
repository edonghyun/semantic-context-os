package io.scos.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.provider.openai.OpenAiCompatibleEmbedder;
import io.scos.support.TestMetrics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 데모의 키 없는 임베더(demo/embedder/hashing_embedder.py)는 Java 대역 {@link HashingEmbedder}와 같은 벡터를 낸다(공개 배포
 * 단계 5 T6). 데모는 문서를 Python으로 임베딩하고 DemoCorpusLoadTest는 Java 대역으로 임베딩하므로, 둘이 같아야 그 테스트가 데모를 대신한다. 실제
 * Python 프로세스를 루프백 임시 포트로 띄우고 제품 {@link OpenAiCompatibleEmbedder}로 부른다. 외부 호출은 없다.
 */
@Tag("process")
@DisplayName("데모 Python 임베더는 Java 해싱 대역과 같은 벡터를 OpenAI 호환 형식으로 낸다")
class HashingEmbedderParityTest {
  static final String MODEL = "scos-demo-hashing"; // demo/compose.yaml SCOS_EMBEDDER_MODEL
  static final int DIM = 768;
  static final double TOLERANCE = 1e-12;
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  static final Duration BUDGET = Duration.ofSeconds(30);
  static final int MAX_ATTEMPTS = 1;
  static final ObjectMapper JSON = new ObjectMapper();

  final PythonEmbedder python = PythonEmbedder.start();
  final OpenAiCompatibleEmbedder embedder =
      new OpenAiCompatibleEmbedder(
          python.baseUrl(),
          Optional.empty(),
          MODEL,
          DIM,
          new ProviderCallExecutor(
              new ProviderCallPolicy(REQUEST_TIMEOUT, MAX_ATTEMPTS, BUDGET, () -> 0.0),
              Clock.systemUTC(),
              (delay, cancel) -> cancel.isCancelled(),
              TestMetrics.providerCalls()),
          JSON);

  @AfterEach
  void stop() {
    python.close();
  }

  // 한글 · 영어 · 서로게이트 쌍(Java는 UTF-16 단위로 자른다) · CRLF·탭(지운다)과 NBSP(Java \s가 아니라 남는다) · 빈 문자열 · 한 글자
  @ParameterizedTest
  @ValueSource(
      strings = {
        "결제 중복 승인 장애 원인",
        "How do clients send an idempotency key to the Order API?",
        "결제 😀 승인",
        "줄\r\n바꿈\t탭 공백",
        "",
        "가"
      })
  @DisplayName("Python 임베더의 벡터는 Java 대역의 벡터와 자리마다 1e-12 안에서 같다")
  void sameVectorAsJavaDouble(String text) {
    // given
    double[] java = new HashingEmbedder().embed(List.of(text)).getFirst();

    // when
    double[] fromPython = embedder.embed(List.of(text)).getFirst();

    // then
    assertThat(fromPython).containsExactly(java, within(TOLERANCE));
  }

  @Test
  @DisplayName("빈 입력은 0번 자리만 1인 벡터다")
  void emptyInputIsFirstAxis() {
    // given
    double[] expected = new double[DIM];
    expected[0] = 1.0;

    // when
    double[] fromPython = embedder.embed(List.of("")).getFirst();

    // then
    assertThat(fromPython).containsExactly(expected);
  }

  @Test
  @DisplayName("Python 임베더는 OpenAI 호환 응답 형식으로 답한다")
  void answersInOpenAiShape() throws Exception {
    // given
    String request = "{\"model\":\"" + MODEL + "\",\"input\":[\"결제 승인\"]}";

    // when
    JsonNode reply;
    try (HttpClient client = HttpClient.newHttpClient()) {
      String body =
          client
              .send(
                  HttpRequest.newBuilder(URI.create(python.baseUrl() + "/embeddings"))
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString(request))
                      .build(),
                  HttpResponse.BodyHandlers.ofString())
              .body();
      reply = JSON.readTree(body);
    }

    // then — 벡터 값은 위 사례가 본다. 여기서는 벡터를 뺀 봉투만 본다
    if (reply.path("data").path(0) instanceof ObjectNode item) item.remove("embedding");
    assertThat(reply)
        .isEqualTo(
            JSON.readTree(
                "{\"object\":\"list\",\"model\":\""
                    + MODEL
                    + "\",\"data\":[{\"object\":\"embedding\",\"index\":0}]}"));
  }

  /**
   * {@code python3 -I hashing_embedder.py --host 127.0.0.1 --port 0}: 첫 {@code listening on} 줄에서 임시
   * 포트를 읽는다.
   */
  record PythonEmbedder(Process process, String baseUrl) implements AutoCloseable {
    static final Pattern LISTENING = Pattern.compile("listening on ([^\\s:]+):(\\d+)");

    static PythonEmbedder start() {
      String script =
          DemoCorpus.defaultRoot()
              .resolve("embedder/hashing_embedder.py")
              .toAbsolutePath()
              .toString();
      Process process;
      try {
        process =
            new ProcessBuilder("python3", "-I", script, "--host", "127.0.0.1", "--port", "0")
                .redirectErrorStream(true)
                .start();
      } catch (IOException e) {
        throw new IllegalStateException("given 준비 실패: python3을 실행할 수 없다", e);
      }
      StringBuilder seen = new StringBuilder();
      try {
        BufferedReader out =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        for (String line = out.readLine(); line != null; line = out.readLine()) {
          Matcher m = LISTENING.matcher(line);
          if (m.find())
            return new PythonEmbedder(process, "http://" + m.group(1) + ":" + m.group(2) + "/v1");
          seen.append(line).append('\n');
        }
      } catch (IOException e) {
        process.destroyForcibly();
        throw new UncheckedIOException(e);
      }
      process.destroyForcibly();
      throw new IllegalStateException("given 준비 실패: Python 임베더가 listening 줄 없이 끝났다:\n" + seen);
    }

    @Override
    public void close() {
      process.destroy();
      try {
        process.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
