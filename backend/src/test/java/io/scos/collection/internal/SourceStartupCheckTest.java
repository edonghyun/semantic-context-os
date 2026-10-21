package io.scos.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.scos.collection.internal.memory.MemorySourceInstanceStore;
import io.scos.spi.connector.CallFailure;
import io.scos.spi.connector.CallFailureException;
import io.scos.spi.connector.CredentialResolver;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 데몬 기동 검증(TS source-service-runner validateStartup, ADR-0022 §5): 켜진 소스마다 어댑터가 있고 설정이 맞고, 서로 다른
 * credentialRef가 모두 풀려야 데몬을 띄운다. 실패 메시지는 소스 id·kind·검증 실패 사유만 싣는다 — 자격 증명 값은 싣지 않는다.
 */
@DisplayName("데몬 기동 검사 — 켜진 소스의 어댑터·설정·자격 증명(ADR-0022 §5)")
class SourceStartupCheckTest {
  static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");
  static final SourceInstanceId A = new SourceInstanceId("a");
  static final SourceInstanceId B = new SourceInstanceId("b");
  static final String STRICT_KIND = SourceInstances.STRICT_KIND;
  static final String SECRET = "secret-token-value";

  final MemorySourceInstanceStore sources = new MemorySourceInstanceStore();
  final List<String> resolved = new ArrayList<>();
  final Map<String, String> credentials = Map.of("env:NOOP_TOKEN", SECRET, "env:EMPTY", "");

  SourceStartupCheck check() {
    CredentialResolver resolver =
        ref -> {
          resolved.add(ref);
          String value = credentials.get(ref);
          if (value == null)
            throw new CallFailureException(
                "credentials",
                CallFailure.of(
                    CallFailure.Kind.INVALID_CONFIGURATION, "credential missing: " + ref));
          return value;
        };
    return new SourceStartupCheck(
        sources,
        SourceAdapters.of(List.of(SourceInstances.NOOP_ADAPTER, SourceInstances.strictAdapter())),
        resolver);
  }

  void givenSource(SourceInstanceId id, String kind, String config, String credentialRef) {
    sources.save(SourceInstances.of(id, kind, config, credentialRef, T0));
  }

  void givenDisabledSource(SourceInstanceId id, String kind, String credentialRef) {
    sources.save(SourceInstances.disabled(SourceInstances.of(id, kind, "{}", credentialRef, T0)));
  }

  IllegalStateException failure() {
    return catchThrowableOfType(IllegalStateException.class, () -> check().verify());
  }

  @Test
  @DisplayName("켜진 소스가 모두 맞으면 통과한다")
  void passesWhenEverySourceIsValid() {
    // given
    givenSource(A, SourceInstances.NOOP_KIND, "{}", "env:NOOP_TOKEN");
    givenSource(B, STRICT_KIND, "{}", "env:NOOP_TOKEN");

    // when·then
    assertThatCode(() -> check().verify()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("꺼진 소스는 검사하지 않는다")
  void ignoresDisabledSources() {
    // given
    givenDisabledSource(A, "jira", "env:MISSING");

    // when·then
    assertThatCode(() -> check().verify()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("같은 credentialRef는 한 번만 푼다")
  void resolvesEachRefOnce() {
    // given
    givenSource(A, SourceInstances.NOOP_KIND, "{}", "env:NOOP_TOKEN");
    givenSource(B, SourceInstances.NOOP_KIND, "{}", "env:NOOP_TOKEN");

    // when
    check().verify();

    // then
    assertThat(resolved).containsExactly("env:NOOP_TOKEN");
  }

  @Nested
  @DisplayName("켜진 소스가 검증에 실패하면")
  class InvalidSource {
    @Test
    @DisplayName("등록된 어댑터가 없는 kind면 소스와 kind를 밝혀 거부한다")
    void rejectsUnknownKind() {
      // given
      givenSource(A, "jira", "{}", "env:NOOP_TOKEN");

      // when
      IllegalStateException failure = failure();

      // then
      assertThat(failure)
          .hasMessage(
              "source a (jira): Source adapter not found: jira. Registered kinds: noop, strict");
    }

    @Test
    @DisplayName("설정이 틀리면 소스와 검증 사유를 밝혀 거부한다")
    void rejectsInvalidConfig() {
      // given
      givenSource(A, STRICT_KIND, "{\"bad\":true}", "env:NOOP_TOKEN");

      // when
      IllegalStateException failure = failure();

      // then
      assertThat(failure).hasMessage("source a (strict): strict config rejected");
    }

    @Test
    @DisplayName("자격 증명을 풀 수 없으면 소스와 사유를 밝혀 거부한다")
    void rejectsUnresolvableCredential() {
      // given
      givenSource(A, SourceInstances.NOOP_KIND, "{}", "env:MISSING");

      // when
      IllegalStateException failure = failure();

      // then
      assertThat(failure).hasMessage("source a (noop): credential missing: env:MISSING");
    }

    @Test
    @DisplayName("빈 자격 증명은 ref만 밝혀 거부한다")
    void rejectsEmptyCredential() {
      // given
      givenSource(A, SourceInstances.NOOP_KIND, "{}", "env:EMPTY");

      // when
      IllegalStateException failure = failure();

      // then
      assertThat(failure).hasMessage("source a (noop): Source credential is empty: env:EMPTY");
    }

    @Test
    @DisplayName("거부 메시지에 자격 증명 값을 싣지 않는다")
    void failureCarriesNoCredentialValue() {
      // given
      givenSource(A, SourceInstances.NOOP_KIND, "{}", "env:NOOP_TOKEN");
      givenSource(B, STRICT_KIND, "{\"bad\":true}", "env:NOOP_TOKEN");

      // when
      IllegalStateException failure = failure();

      // then
      assertThat(failure.getMessage()).doesNotContain(SECRET);
    }
  }
}
