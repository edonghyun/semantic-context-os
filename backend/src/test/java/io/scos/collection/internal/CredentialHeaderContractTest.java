package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.connector.googledrive.GoogleDriveAssembly;
import io.scos.connector.jira.JiraAssembly;
import io.scos.connector.notion.NotionAssembly;
import io.scos.connector.slack.SlackAssembly;
import io.scos.connector.tiro.TiroAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceAdapter;
import io.scos.spi.connector.SourceInstanceId;
import io.scos.support.FakeHttpServer;
import io.scos.support.TestMetrics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 커넥터 공통 계약(ADR-0022 §2): 헤더에 실을 수 없는 자격 증명 값이면 헤더를 만들기 전에 {@link CredentialResolver#headerValue}로
 * 설정 오류(invalid_configuration, 저장 errorKind `Error`)로 끝나고 공급자를 부르지 않는다. 빠뜨리면 마지막
 * 방어(ProviderHttp·실행기)가 재시도 없이 막지만 자격 증명 실패가 아니라 저장 errorKind invalid_configuration이 된다. 새 커넥터는 이
 * 표에 행을 더한다.
 */
@DisplayName("커넥터는 헤더에 못 싣는 자격 증명을 설정 오류로 끝낸다(ADR-0022 §2)")
class CredentialHeaderContractTest {
  static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
  static final String BROKEN_TOKEN = "connector-token\r";
  static final String VALID_TOKEN = "connector-token";

  /** 커넥터 한 행: 이름, 소스 설정, (가짜 공급자 주소, 토큰) → 제품 어댑터. */
  record Connector(
      String name,
      String kind,
      String configJson,
      BiFunction<String, String, SourceAdapter> adapter) {
    @Override
    public String toString() {
      return name;
    }
  }

  /** 안쪽 상황 클래스에서 쓰는 사례 표의 이름. */
  static final String CONNECTORS =
      "io.scos.collection.internal.CredentialHeaderContractTest#connectors";

  static Stream<Arguments> connectors() {
    Clock clock = Clock.systemUTC();
    return Stream.of(
            new Connector(
                "Slack",
                SlackAssembly.KIND,
                "{}",
                (url, token) -> SlackAssembly.adapter(url, token, clock)),
            new Connector(
                "Jira",
                JiraAssembly.KIND,
                JiraAssembly.SYNTHETIC_SITE,
                (url, token) -> JiraAssembly.adapter(url, token, clock)),
            new Connector(
                "Notion",
                NotionAssembly.KIND,
                NotionAssembly.config("roots"),
                (url, token) -> NotionAssembly.adapter(url, token, clock)),
            new Connector(
                "Google Drive",
                GoogleDriveAssembly.KIND,
                GoogleDriveAssembly.SYNTHETIC_ACCOUNT,
                (url, token) ->
                    GoogleDriveAssembly.adapterWithTokenEndpoint(
                        url,
                        (token.equals(BROKEN_TOKEN) ? TOKENS : VALID_TOKENS).baseUrl(),
                        GoogleDriveAssembly.CREDENTIAL_JSON,
                        clock)),
            new Connector(
                "Tiro",
                TiroAssembly.KIND,
                "{\"workspaceGuid\":\"ws\",\"folderId\":\"folder\"}",
                (url, token) -> TiroAssembly.adapter(url, token, clock)))
        .map(Arguments::of);
  }

  /**
   * Drive의 헤더 자격 증명은 설정값이 아니라 OAuth 토큰 끝점이 준 access token이다 — 토큰 끝점(이 서버)은 깨진 토큰을 주고, 데이터 공급자
   * (provider)는 요청을 받지 않아야 한다.
   */
  static final FakeHttpServer TOKENS =
      tokenEndpoint("{\"access_token\":\"connector-token\\r\",\"expires_in\":3600}");

  /** 대조군용: 헤더에 실을 수 있는 access token을 주는 토큰 끝점. */
  static final FakeHttpServer VALID_TOKENS =
      tokenEndpoint("{\"access_token\":\"connector-token\",\"expires_in\":3600}");

  static FakeHttpServer tokenEndpoint(String body) {
    FakeHttpServer server = newServer();
    server.on("POST", "/_oauth/token", request -> FakeHttpServer.ok(body));
    return server;
  }

  @AfterAll
  static void closeTokens() {
    TOKENS.close();
    VALID_TOKENS.close();
  }

  final FakeHttpServer provider = newServer();

  static FakeHttpServer newServer() {
    try {
      return new FakeHttpServer();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterEach
  void close() {
    provider.close();
  }

  /** 깨진 토큰으로 한 번 수집하고 던진 공급자 호출 실패를 돌려준다(던지지 않으면 null). */
  CallFailureException collect(Connector connector) {
    return catchThrowableOfType(
        CallFailureException.class, () -> collectWith(connector, provider, BROKEN_TOKEN));
  }

  void collectWith(Connector connector, FakeHttpServer server, String token) {
    SourceAdapter adapter = connector.adapter().apply(server.baseUrl(), token);
    CollectionInput input =
        new CollectionInput(
            SourceInstances.of(
                    new SourceInstanceId("credential-contract"),
                    connector.kind(),
                    connector.configJson(),
                    "env:BROKEN",
                    NOW)
                .descriptor(),
            Optional.empty(),
            CollectionMode.INCREMENTAL,
            new ProviderCallExecutor(
                ProviderCallPolicy.defaults(() -> 0.0),
                Clock.systemUTC(),
                (delay, cancel) -> cancel.isCancelled(),
                TestMetrics.providerCalls()),
            RawContentCapture.defaults(),
            CancellationToken.none());
    adapter.collect(input, page -> {});
  }

  /**
   * 대조군: 같은 설정·같은 가짜 공급자에 헤더에 실을 수 있는 토큰이면 공급자까지 간다. 그래서 아래 깨진 토큰 사례의 "실패·요청 0"은 설정 오류가 아니라 토큰
   * 때문이다. 응답을 등록하지 않은 따로 띄운 공급자라 수집 결과는 보지 않고, 미등록 요청 검사 없이 닫는다.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("connectors")
  @DisplayName("대조군: 헤더에 실을 수 있는 토큰이면 공급자를 부른다")
  void sendsRequestWithValidToken(Connector connector) {
    // given
    FakeHttpServer reachable = newServer();
    try {
      // when
      Throwable unused = catchThrowable(() -> collectWith(connector, reachable, VALID_TOKEN));

      // then
      assertThat(reachable.received()).isNotEmpty();
    } finally {
      reachable.abandon();
    }
  }

  @Nested
  @DisplayName("헤더에 실을 수 없는 자격 증명으로 수집하면")
  class BrokenToken {
    @ParameterizedTest(name = "{0}")
    @MethodSource(CONNECTORS)
    @DisplayName("invalid_configuration으로 실패한다")
    void failsAsInvalidConfiguration(Connector connector) {
      // when
      CallFailureException failure = collect(connector);

      // then
      assertThat(failure)
          .isNotNull()
          .extracting(f -> f.failure().kind())
          .isEqualTo(CallFailure.Kind.INVALID_CONFIGURATION);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(CONNECTORS)
    @DisplayName("공급자를 부르지 않는다")
    void sendsNoRequest(Connector connector) {
      // when
      collect(connector);

      // then
      assertThat(provider.received()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(CONNECTORS)
    @DisplayName("TS와 같은 저장 errorKind Error로 남는다")
    void recordedAsTsError(Connector connector) {
      // when
      CallFailureException failure = collect(connector);

      // then: 헤더 지점(headerValue)을 빠뜨려도 마지막 방어가 재시도 없이 막지만 operation이 자격 증명이 아니라
      // invalid_configuration으로 남는다 — 이 단언이 헤더 지점 호출을 고정한다
      assertThat(failure).isNotNull();
      assertThat(CollectionRuntime.classify(failure)).isEqualTo("Error");
    }
  }
}
