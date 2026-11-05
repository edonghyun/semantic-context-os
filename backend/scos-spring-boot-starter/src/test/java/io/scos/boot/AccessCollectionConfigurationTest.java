package io.scos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.scos.collection.internal.AccessCollection;
import io.scos.collection.internal.AccessResult;
import io.scos.collection.internal.CollectionRuntime;
import io.scos.spi.access.AccessKey;
import io.scos.spi.source.SourceInstanceId;
import io.scos.support.LogCapture;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;

@DisplayName("접근 수집은 모드와 읽기에 공유한 도메인 정책을 따른다")
class AccessCollectionConfigurationTest {
  private static final Duration SIX_HOURS = Duration.ofHours(6);

  @Test
  @DisplayName("접근 수집을 끈 단일 사용자 기동은 도메인 경고를 남기지 않는다")
  void singleUserWithoutCollectionDoesNotWarn() {
    try (var logs = LogCapture.of(AccessDomainConfiguration.class)) {
      // when
      AccessConfigurationScenario.singleUser()
          .driveAccount("A@example.com")
          .run(scenario -> assertThat(scenario.context()).hasNotFailed());

      // then
      assertThat(logs.events("access.domain.unavailable")).isEmpty();
    }
  }

  @Test
  @DisplayName("접근 수집을 켠 단일 사용자는 조직 도메인 누락을 경고한다")
  void singleUserCollectionWarnsWhenOrganizationIsMissing() {
    try (var logs = LogCapture.of(AccessDomainConfiguration.class)) {
      // when
      AccessConfigurationScenario.singleUser()
          .withAccessCollection()
          .driveAccount("A@example.com")
          .run(scenario -> assertThat(scenario.context()).hasNotFailed());

      // then
      assertThat(logs.events("access.domain.unavailable"))
          .extracting(event -> event.path("detail").path("reason").asString())
          .containsExactly("outside_org_domains");
    }
  }

  @Test
  @DisplayName("무효 계정도 명시 도메인 오버레이를 유지하며 경고하지 않는다")
  void invalidAccountKeepsExplicitOverlay() {
    try (var logs = LogCapture.of(AccessDomainConfiguration.class)) {
      // when
      AccessConfigurationScenario.multiUser()
          .sourceConfiguration("google-drive", "{\"accountEmail\":\"invalid\"}")
          .organization("example.com")
          .overlay("example.com")
          .run(
              scenario ->
                  assertThat(scenario.domains().instanceDomains())
                      .containsEntry(new SourceInstanceId("drive-a"), "example.com"));

      // then
      assertThat(logs.events("access.domain.unavailable")).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"{}", "{\"accountEmail\":42}", "{\"accountEmail\":\"invalid\"}", "{", "null"})
  @DisplayName("계정 설정을 읽을 수 없으면 경고와 함께 도메인 grant를 닫는다")
  void missingAccountLogsWarningAndGrantsNothing(String config) {
    try (var logs = LogCapture.of(AccessDomainConfiguration.class)) {
      AccessConfigurationScenario.multiUser()
          .sourceConfiguration("google-drive", config)
          .organization("example.com")
          .run(scenario -> assertThat(scenario.resolveDomainKeys("member@example.com")).isEmpty());
      // then
      assertThat(logs.events("access.domain.unavailable"))
          .extracting(
              event -> event.path("level").asString(),
              event -> event.path("sourceInstanceId").asString(),
              event -> event.path("detail").path("reason").asString())
          .containsExactly(tuple("warn", "drive-a", "missing_instance_domain"));
    }
  }

  @Test
  @DisplayName("조직 허용 목록이 없으면 도메인 grant가 없다는 경고를 남긴다")
  void missingOrganizationLogsWarning() {
    try (var logs = LogCapture.of(AccessDomainConfiguration.class)) {
      AccessConfigurationScenario.multiUser()
          .driveAccount("A@example.com")
          .run(scenario -> assertThat(scenario.resolveDomainKeys("member@example.com")).isEmpty());
      // then
      assertThat(logs.events("access.domain.unavailable"))
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.path("detail").path("reason").asString())
                      .isEqualTo("outside_org_domains"));
    }
  }

  @Test
  @DisplayName("다른 종류의 accountEmail은 Drive 도메인 기본값으로 쓰지 않는다")
  void otherSourceAccountIsIgnored() {
    // when·then
    AccessConfigurationScenario.multiUser()
        .sourceConfiguration("slack", "{\"accountEmail\":\"A@example.com\"}")
        .organization("example.com")
        .run(scenario -> assertThat(scenario.domains().instanceDomains()).isEmpty());
  }

  @Test
  @DisplayName("Drive 계정 도메인은 인스턴스 도메인의 기본값이다")
  void driveAccountDomainIsFallback() {
    // when·then
    AccessConfigurationScenario.multiUser()
        .driveAccount("A@EXAMPLE.COM")
        .organization("example.com")
        .run(
            scenario ->
                assertThat(scenario.domains().instanceDomains())
                    .containsEntry(new SourceInstanceId("drive-a"), "example.com"));
  }

  @Test
  @DisplayName("명시 도메인 오버레이는 Drive 계정 도메인보다 우선한다")
  void explicitDomainOverlayWins() {
    // when·then
    AccessConfigurationScenario.multiUser()
        .driveAccount("A@example.com")
        .organization("other.example")
        .overlay("other.example")
        .run(
            scenario ->
                assertThat(scenario.domains().instanceDomains())
                    .containsEntry(new SourceInstanceId("drive-a"), "other.example"));
  }

  @Test
  @DisplayName("읽기와 수집은 같은 도메인 정책 객체를 공유한다")
  void resolverAndCollectorShareDomainPolicy() {
    AccessConfigurationScenario.multiUser()
        .driveAccount("A@example.com")
        .organization("example.com")
        .run(
            scenario -> {
              scenario.collect();
              // then
              assertThat(scenario.collectedDomains()).isSameAs(scenario.domains());
            });
  }

  @Test
  @DisplayName("조직 허용 목록 밖의 계정 도메인은 접근 키를 만들지 않는다")
  void outsideOrganizationDomainGrantsNothing() {
    // when·then
    AccessConfigurationScenario.multiUser()
        .driveAccount("A@outside.example")
        .organization("example.com")
        .run(scenario -> assertThat(scenario.resolveDomainKeys("A@outside.example")).isEmpty());
  }

  @Test
  @DisplayName("허용된 Drive 계정 도메인은 읽기 도메인 키로 연결된다")
  void driveDomainReachesResolver() {
    // when·then
    AccessConfigurationScenario.multiUser()
        .driveAccount("A@example.com")
        .organization("example.com")
        .run(
            scenario ->
                assertThat(scenario.resolveDomainKeys("member@example.com"))
                    .containsExactly(AccessKey.parse("domain:drive-a:example.com")));
  }

  @Test
  @DisplayName("단일 사용자 기본 설정은 접근 호출과 접근 쓰기를 하지 않는다")
  void defaultSingleUserCallsNoAccessApi() {
    AccessConfigurationScenario.singleUser()
        .run(
            scenario -> {
              scenario.collect();
              // then
              assertThat(scenario.accessCalls()).isZero();
              assertThat(scenario.lastRun().accessResult()).isEqualTo(AccessResult.SKIPPED);
              assertThat(scenario.rawAcls()).isEmpty();
            });
  }

  @Test
  @DisplayName("다중 사용자는 접근 수집을 항상 실행한다")
  void multiUserAlwaysCollectsAccess() {
    AccessConfigurationScenario.multiUser()
        .run(
            scenario -> {
              scenario.collect();
              // then
              assertThat(scenario.accessCalls()).isEqualTo(1);
              assertThat(scenario.lastRun().accessResult()).isEqualTo(AccessResult.COMPLETE);
              assertThat(scenario.rawAcls()).containsOnlyKeys("item");
            });
  }

  @Test
  @DisplayName("토큰 스케줄러가 함께 있어도 heartbeat는 명시한 스케줄러를 쓴다")
  void heartbeatSchedulerIsQualified() {
    AccessConfigurationScenario.multiUser()
        .run(
            scenario -> {
              scenario.collect();
              // then
              assertThat(scenario.tokenSchedules()).isZero();
              assertThat(scenario.context()).hasSingleBean(CollectionRuntime.class);
            });
  }

  @Test
  @DisplayName("접근 관측 상한은 설정값을 바인딩한다")
  void configuredMaxAgeIsUsed() {
    AccessConfigurationScenario.multiUser()
        .maxAge("6h")
        .run(
            scenario -> {
              // then
              assertThat(scenario.context().getBean(AccessCollection.Options.class).maxAge())
                  .isEqualTo(SIX_HOURS);
            });
  }

  @Test
  @DisplayName("0초 상한은 기동 시 거부한다")
  void zeroMaxAgeIsRejected() {
    AccessConfigurationScenario.multiUser()
        .maxAge("0s")
        .run(
            scenario -> {
              // then
              assertThat(scenario.context())
                  .getFailure()
                  .rootCause()
                  .isInstanceOf(BindValidationException.class)
                  .hasMessageContaining("maxAge");
            });
  }
}
