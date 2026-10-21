package io.scos.connector.notion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.boot.NotionCollectionConfiguration;
import io.scos.collection.internal.RawContentCapture;
import io.scos.collection.internal.TsRawViews;
import io.scos.connector.notion.NotionAdapter;
import io.scos.connector.notion.NotionAssembly;
import io.scos.engine.support.call.ProviderCallExecutor;
import io.scos.engine.support.call.ProviderCallPolicy;
import io.scos.spi.connector.CancellationToken;
import io.scos.spi.connector.CollectionInput;
import io.scos.spi.connector.CollectionMode;
import io.scos.spi.connector.CollectionPage;
import io.scos.spi.connector.CredentialResolver;
import io.scos.support.TestMetrics;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/** Notion 커넥터 조립(F6 T6.9, ADR-0022 §5): API origin 설정 검증과 어댑터 빈. 잘못된 값은 기동 시 거부한다(ADR-0009). */
@DisplayName("Notion 조립은 API origin을 검증하고 운영 어댑터 빈을 만든다(F6 T6.9)")
class NotionCollectionConfigurationTest {
  /** Jackson·검증 자동 구성과 Notion 조립, fixture 시각에 고정한 시계 — 자격 증명·실행기는 사례마다. */
  private final ApplicationContextRunner assembly =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class, ValidationAutoConfiguration.class))
          .withUserConfiguration(NotionCollectionConfiguration.class)
          .withBean(Clock.class, () -> Clock.fixed(NotionScenario.NOW, ZoneOffset.UTC));

  private final ApplicationContextRunner notion =
      assembly
          .withBean(CredentialResolver.class, () -> ref -> "unused")
          .withBean(
              ProviderCallExecutor.class,
              () -> ProviderCallExecutor.defaults(Clock.systemUTC(), TestMetrics.providerCalls()));

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"api.notion.com", "ftp://api.notion.com", ""})
  @DisplayName("base-url이 http(s) URL이 아니면 기동에 실패한다")
  void rejectsBadBaseUrl(String url) {
    // when·then
    notion
        .withPropertyValues("scos.collection.notion.base-url=" + url)
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(BindValidationException.class)
                    .hasMessageContaining("baseUrl"));
  }

  @Test
  @DisplayName("기본값이면 kind notion 어댑터 빈 하나를 조립한다")
  void assemblesNotionAdapter() {
    // when·then
    notion.run(ctx -> assertThat(ctx.getBean(NotionAdapter.class).kind()).isEqualTo("notion"));
  }

  @Test
  @DisplayName("기본 API origin은 api.notion.com이다")
  void defaultsToNotionApi() {
    // when·then
    notion.run(
        ctx ->
            assertThat(ctx.getBean(NotionCollectionConfiguration.NotionProperties.class).baseUrl())
                .isEqualTo("https://api.notion.com"));
  }

  /**
   * 운영 조립(base-url 배선·제품 ObjectMapper·제품 HTTP 클라이언트)으로 가짜 Notion을 수집한 원본이 TS와 바이트 단위로 같다. 테스트 조립
   * (NotionAssembly)이 아니라 빈이 만든 어댑터다 — 운영 매퍼 설정이 원본 캡처 바이트를 바꾸면 여기서 드러난다.
   */
  @Test
  @DisplayName("운영 조립 어댑터로 수집한 원본이 TS와 같다")
  void beanAdapterCollectsLikeTs() {
    // when
    List<CollectionPage> pages = collectWithBeanAdapter();

    // then
    assertThat(TsRawViews.lastItems(pages))
        .containsExactlyInAnyOrderElementsOf(NotionTsRuns.TsRun.firstRoots().items());
  }

  private final FakeNotionServer server = NotionAssembly.server();

  @AfterEach
  void closeServer() {
    server.close();
  }

  /** 운영 조립(빈) 어댑터로 가짜 Notion의 roots 첫 수집을 돌린 페이지. */
  private List<CollectionPage> collectWithBeanAdapter() {
    List<CollectionPage> pages = new ArrayList<>();
    assembly
        .withPropertyValues("scos.collection.notion.base-url=" + server.baseUrl())
        .withBean(CredentialResolver.class, () -> ref -> server.token())
        .withBean(ProviderCallExecutor.class, NotionCollectionConfigurationTest::noWait)
        .run(
            ctx ->
                ctx.getBean(NotionAdapter.class)
                    .collect(
                        new CollectionInput(
                            NotionAssembly.instance(
                                    NotionAssembly.config("roots"), NotionScenario.NOW)
                                .descriptor(),
                            Optional.empty(),
                            CollectionMode.INCREMENTAL,
                            ctx.getBean(ProviderCallExecutor.class),
                            RawContentCapture.defaults(),
                            CancellationToken.none()),
                        pages::add));
    return pages;
  }

  /** 재시도 대기를 기다리지 않는 실행기(Retry-After 1초 각본). */
  static ProviderCallExecutor noWait() {
    return new ProviderCallExecutor(
        ProviderCallPolicy.defaults(() -> 0.0),
        Clock.fixed(NotionScenario.NOW, ZoneOffset.UTC),
        (delay, cancel) -> cancel.isCancelled(),
        TestMetrics.providerCalls());
  }
}
