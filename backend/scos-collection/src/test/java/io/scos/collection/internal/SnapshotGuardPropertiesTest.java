package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.scos.boot.CollectionConfiguration;
import io.scos.collection.internal.memory.MemorySnapshotGuardStore;
import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * `scos.collection.snapshot-guard.*`(환경변수 SCOS_SNAPSHOT_GUARD_*)가 축소 보호에 닿는다(ADR-0028 §2·§4). 범위 밖
 * 값은 기동 실패다(ADR-0009).
 */
@DisplayName("축소 보호 설정 — 기준과 허용 유효 기간이 보호에 닿는다(ADR-0028)")
class SnapshotGuardPropertiesTest {
  static final Duration THREE_DAYS = Duration.ofDays(3);
  static final Duration SEVEN_DAYS = Duration.ofDays(7);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CollectionConfiguration.CollectionProperties.class)
  static class Properties {}

  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(Properties.class);

  /** 설정으로 조립한 보호. */
  static SnapshotShrinkGuard guard(AssertableApplicationContext ctx) {
    return ctx.getBean(CollectionConfiguration.CollectionProperties.class)
        .snapshotGuard()
        .guard(
            new MemorySourceInstanceStore(),
            new MemorySnapshotGuardStore(),
            CollectionMetrics.of(new SimpleMeterRegistry()));
  }

  static void assertBindFailure(AssertableApplicationContext ctx, String property) {
    assertThat(ctx)
        .getFailure()
        .rootCause()
        .isInstanceOf(BindValidationException.class)
        .hasMessageContaining(property);
  }

  @Nested
  @DisplayName("값을 주면")
  class Given {
    @Test
    @DisplayName("허용 유효 기간이 보호에 닿는다")
    void appliesAcceptTtl() {
      // when·then
      runner
          .withPropertyValues("scos.collection.snapshot-guard.accept-ttl=3d")
          .run(ctx -> assertThat(guard(ctx).acceptTtl()).isEqualTo(THREE_DAYS));
    }

    @Test
    @DisplayName("최소 규모와 상한이 보호 기준이 된다")
    void appliesLimits() {
      // when·then
      runner
          .withPropertyValues(
              "scos.collection.snapshot-guard.min-active=20",
              "scos.collection.snapshot-guard.max-delete-percent=70")
          .run(
              ctx -> assertThat(guard(ctx).defaults()).isEqualTo(new SnapshotShrinkLimits(20, 70)));
    }
  }

  @Nested
  @DisplayName("값을 주지 않으면")
  class Defaults {
    @Test
    @DisplayName("기준은 10건·50%다")
    void defaultLimits() {
      // when·then
      runner.run(
          ctx -> assertThat(guard(ctx).defaults()).isEqualTo(new SnapshotShrinkLimits(10, 50)));
    }

    @Test
    @DisplayName("허용 유효 기간은 7일이다")
    void defaultAcceptTtl() {
      // when·then
      runner.run(ctx -> assertThat(guard(ctx).acceptTtl()).isEqualTo(SEVEN_DAYS));
    }
  }

  @Nested
  @DisplayName("범위 밖 값을 주면")
  class OutOfRange {
    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
      "min-active, 0, minActive",
      "max-delete-percent, 0, maxDeletePercent",
      "max-delete-percent, 101, maxDeletePercent",
      "accept-ttl, 0s, acceptTtl"
    })
    @DisplayName("그 프로퍼티의 검증 오류로 기동에 실패한다")
    void failsStartup(String key, String value, String property) {
      // when·then
      runner
          .withPropertyValues("scos.collection.snapshot-guard." + key + "=" + value)
          .run(ctx -> assertBindFailure(ctx, property));
    }
  }
}
