package io.scos.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.scos.collection.internal.RunId;
import io.scos.collection.internal.SnapshotGuardStore;
import io.scos.collection.internal.SnapshotShrinkOverrides;
import io.scos.collection.internal.SourceQueries;
import io.scos.spi.connector.SourceInstanceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * `source status`·`status` JSON의 스냅샷 축소 보호 키(ADR-0028, TS에 없는 키). TS처럼 없는 값은 키째 뺀다 — 보호도 허용도 없는 소스의
 * 출력은 TS와 바이트로 같다(QueryCommandsTsEquivalenceTest).
 */
@DisplayName("source status 축소 보호 키 — 보호·허용 대기 중일 때만 나온다(ADR-0028)")
class SourceStatusShrinkGuardJsonTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final SourceInstanceId SOURCE = new SourceInstanceId("tiro-main");
  static final Instant GUARDED = Instant.parse("2026-10-16T01:02:03.456Z");
  static final Instant ACCEPTED = Instant.parse("2026-10-16T02:00:00Z");

  final SourceQueryJson render = new SourceQueryJson(JSON);

  static SourceQueries.SourceStatus status(
      Optional<SnapshotGuardStore.Guarded> guard, Optional<Instant> acceptedAt) {
    return status(guard, acceptedAt, SnapshotShrinkOverrides.DAEMON_DEFAULTS);
  }

  static SourceQueries.SourceStatus status(
      Optional<SnapshotGuardStore.Guarded> guard,
      Optional<Instant> acceptedAt,
      SnapshotShrinkOverrides limits) {
    return new SourceQueries.SourceStatus(
        SOURCE,
        true,
        false,
        20,
        0,
        0,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        guard,
        acceptedAt,
        limits);
  }

  @Nested
  @DisplayName("보호 판정이 남아 있는 소스를 출력하면")
  class Guarded {
    @Test
    @DisplayName("snapshotReconcileGuard에 run과 수를 싣는다")
    void rendersGuard() {
      // given
      var guard = new SnapshotGuardStore.Guarded(new RunId("run-7"), GUARDED, 20, 20, 2);

      // when
      JsonNode out = render.status(status(Optional.of(guard), Optional.empty()));

      // then
      assertThat(out.get("snapshotReconcileGuard"))
          .isEqualTo(
              JSON.readTree(
                  "{\"runId\":\"run-7\",\"guardedAt\":\"2026-10-16T01:02:03.456Z\","
                      + "\"unobservedCount\":20,\"activeCount\":20,\"consecutiveCount\":2,\"repeated\":false}"));
    }
  }

  @Nested
  @DisplayName("보호가 세 번 이어진 소스를 출력하면")
  class Repeated {
    @Test
    @DisplayName("snapshotReconcileGuard.repeated가 true다")
    void flagsRepeated() {
      // given
      var guard = new SnapshotGuardStore.Guarded(new RunId("run-9"), GUARDED, 20, 20, 3);

      // when
      JsonNode out = render.status(status(Optional.of(guard), Optional.empty()));

      // then
      assertThat(out.get("snapshotReconcileGuard").get("repeated").asBoolean()).isTrue();
    }
  }

  @Nested
  @DisplayName("소스별 기준이 있는 소스를 출력하면")
  class SourceLimits {
    @Test
    @DisplayName("snapshotGuardLimits에 등록한 값만 싣는다")
    void rendersOnlyGivenLimits() {
      // given
      var limits = new SnapshotShrinkOverrides(Optional.empty(), Optional.of(80));

      // when
      JsonNode out = render.status(status(Optional.empty(), Optional.empty(), limits));

      // then
      assertThat(out.get("snapshotGuardLimits"))
          .isEqualTo(JSON.readTree("{\"maxDeletePercent\":80}"));
    }
  }

  @Nested
  @DisplayName("허용 표시가 대기 중인 소스를 출력하면")
  class Accepted {
    @Test
    @DisplayName("shrinkAcceptedAt에 허용 시각을 싣는다")
    void rendersAcceptance() {
      // when
      JsonNode out = render.status(status(Optional.empty(), Optional.of(ACCEPTED)));

      // then
      assertThat(out.get("shrinkAcceptedAt").asString()).isEqualTo("2026-10-16T02:00:00.000Z");
    }
  }

  @Nested
  @DisplayName("보호도 허용도 없는 소스를 출력하면")
  class Untouched {
    @Test
    @DisplayName("축소 보호 키를 하나도 싣지 않는다")
    void omitsKeys() {
      // when
      JsonNode out = render.status(status(Optional.empty(), Optional.empty()));

      // then
      assertThat(out.propertyNames())
          .doesNotContain("snapshotReconcileGuard", "shrinkAcceptedAt", "snapshotGuardLimits");
    }
  }
}
