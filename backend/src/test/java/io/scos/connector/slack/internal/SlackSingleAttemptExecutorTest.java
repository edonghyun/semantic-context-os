package io.scos.connector.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.RawContentCapture;
import io.scos.connector.slack.SlackAdapter;
import io.scos.connector.slack.SlackAssembly;
import io.scos.spi.connector.CallExecutor;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 커넥터는 입력의 호출 실행기를 계약({@link CallExecutor})으로만 안다 — 재시도·대기·지표가 없는 실행기를 넘겨도 수집이 돈다. 제품 실행기 없이 어댑터를
 * 쓰는 바깥 사용자의 자리다(단계 2 API 설계 §1.2).
 */
@DisplayName("Slack 수집은 시도 한 번짜리 호출 실행기로도 돈다(실행기는 계약)")
class SlackSingleAttemptExecutorTest {
  static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** 시도 한 번: 실패는 분류만 해서 던진다. 재시도·대기·지표 없음. */
  static final CallExecutor SINGLE_ATTEMPT =
      new CallExecutor() {
        @Override
        public <T> T execute(
            String operation,
            Call<T> call,
            Function<Exception, CallFailure> classify,
            CancellationToken cancel) {
          try {
            return call.invoke(TIMEOUT);
          } catch (Exception e) {
            throw new CallFailureException(operation, classify.apply(e));
          }
        }

        @Override
        public Duration requestTimeout() {
          return TIMEOUT;
        }
      };

  final FakeSlackServer server = fakeSlack();

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  @DisplayName("공개 채널을 수집하면 원본 변경이 담긴 페이지가 온다")
  void collectsWithPlainExecutor() {
    // given
    Instant now = Instant.parse(server.now());
    SlackAdapter adapter =
        SlackAssembly.adapter(server.baseUrl(), server.token(), Clock.systemUTC());
    List<CollectionPage> pages = new ArrayList<>();

    // when
    adapter.collect(
        new CollectionInput(
            SlackAssembly.instance(SlackAssembly.PUBLIC_CHANNELS_NO_OVERLAP, now).descriptor(),
            Optional.empty(),
            CollectionMode.INCREMENTAL,
            SINGLE_ATTEMPT,
            RawContentCapture.defaults(),
            CancellationToken.none()),
        pages::add);

    // then
    assertThat(pages.stream().mapToInt(p -> p.mutations().size()).sum()).isPositive();
  }

  private static FakeSlackServer fakeSlack() {
    try {
      return new FakeSlackServer(
          Path.of(System.getProperty("scos.fixtures.p4.dir", "../harness/fixtures/p4"))
              .resolve("slack-dataset.json"));
    } catch (IOException e) {
      throw new IllegalStateException("given 준비 실패: 가짜 Slack을 띄우지 못했다", e);
    }
  }
}
